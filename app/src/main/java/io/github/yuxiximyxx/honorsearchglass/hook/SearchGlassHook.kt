package io.github.yuxiximyxx.honorsearchglass.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.WeakHashMap

/**
 * SearchGlassHook —— 把荣耀搜索首页底部搜索框底板换成毛玻璃。
 *
 * 目标结构（反编译自荣耀搜索 10.0.37.301，见 docs/stage0-findings.md）：
 *   HomeActivity 根布局底部是 SearchViewContainer（id/rl_search_view），
 *   里面依次叠着 GradientBorderLayout、BlurView（id/search_blur_view，
 *   背景是 drawable/search_blur_bg）、装药丸框的容器。
 *   BlurView 本是 App 自己的「贴背景模糊图」视图：onDraw 里把一张预模糊的
 *   背景位图按自身位置裁剪绘制。实机上这块底板呈不透明悬浮白板，与页面
 *   上半部分的模糊背景不统一——本模块把它的渲染换成系统实时背景模糊：
 *
 *   1) 给 BlurView 挂 HnBackgroundBlurDrawable（MagicOS 系统磨砂，
 *      createBlurDrawable → setBlurRadius/setSaturation/setColor → 设为背景）；
 *   2) hook 住 BlurView.onDraw，挂载成功后跳过它自带的位图绘制，免得盖住玻璃；
 *   3) 清掉同一叠层里其它不透明背景（容器自身、home_custom_bg 等），
 *      让模糊能透到药丸框下面；BlurView 联动的 navigationBar 视图
 *      （窗口最底部的导航条）也一并挂玻璃，底板直达窗口底边；
 *   4) App 会通过 BlurView.setSearchViewBgBlur（混淆名 a(ZZ)）在 IME/
 *      配置变化时重设底板背景——hook 住它，执行后立刻补挂；另在获焦/
 *      布局变化时校验背景，不是我们的 Drawable 就复用同一实例重挂。
 *
 * 定位以资源 id 名（rl_search_view / search_blur_view）为主锚点——资源名
 * 不会被 R8 改名，比写死混淆类名稳；id 找不到时退回结构性查找
 * （贴底、全宽、含 EditText 与「搜索」按钮的容器链）。
 * 所有步骤独立 runCatching + 写 LSPosed 日志，失败静默降级，绝不崩目标进程。
 */
object SearchGlassHook {

    private const val TAG = "HonorSearchGlass"
    private const val HOME_ACTIVITY =
        "com.hihonor.search.feature.mainpage.presentation.page.HomeActivity"
    private const val BLUR_VIEW_CLASS =
        "com.hihonor.search.feature.mainpage.presentation.view.BlurView"

    // 磨砂参数：对齐页面观感的中等磨砂（真机可再调，见 docs）
    private const val BLUR_RADIUS = 140
    private const val BLUR_SATURATION = 1.4f
    private const val TINT_LIGHT = 0x2EFFFFFF // 白天：白色薄纱
    private const val TINT_DARK = 0x33000000  // 夜间：黑色薄纱

    private lateinit var module: XposedModule

    /** 已挂上玻璃的 BlurView → 我们给它的 Drawable（复用同一实例重挂）。 */
    private val glassHosts = Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    /** 已安排过延时应用的 Activity，防 onResume 反复排队。 */
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    /** 已挂过布局监听的容器，防重复添加。 */
    private val watched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var onDrawHooked = false
    @Volatile
    private var bgResetHooked = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.module = module

        val activityClass = Class.forName("android.app.Activity", false, classLoader)

        val onResume = activityClass.getDeclaredMethod("onResume")
        module.hook(onResume).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.thisObject as? Activity
                if (act != null && act.javaClass.name == HOME_ACTIVITY) {
                    scheduleApply(act, 500)
                }
            }.onFailure { log("onResume 后处理异常（已吞）", it) }
            result
        }

        val onFocus = activityClass.getDeclaredMethod(
            "onWindowFocusChanged", Boolean::class.javaPrimitiveType
        )
        module.hook(onFocus).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.thisObject as? Activity
                val hasFocus = chain.args[0] as? Boolean ?: false
                if (act != null && hasFocus && act.javaClass.name == HOME_ACTIVITY) {
                    // 获焦时 App 可能刚把背景重置过，补查一次（已挂好的是廉价空转）
                    apply(act, "focus")
                }
            }.onFailure { log("onWindowFocusChanged 后处理异常（已吞）", it) }
            result
        }

        log("hook 已安装：等 HomeActivity 出现")
    }

    private fun scheduleApply(act: Activity, delayMs: Long) {
        if (!scheduled.add(act)) return
        val decor = act.window?.decorView ?: return
        decor.postDelayed({
            scheduled.remove(act)
            apply(act, "resume")
        }, delayMs)
    }

    /** 一次完整的「定位 + 挂玻璃」流程，可重复调用（内部有幂等检查）。 */
    private fun apply(act: Activity, from: String) {
        val decor = act.window?.decorView ?: return
        val panel = findPanel(decor)
        if (panel == null) {
            log("[$from] 未定位到底板容器，跳过（视图树可能还没长好）")
            return
        }
        val container = panel.container
        val blurView = panel.blurView

        // 键盘弹起/收起会让容器 bounds 变化，挂一次布局监听做兜底补挂
        watchLayout(container, act)

        // 1) 清掉叠层里的不透明背景（容器自身与药丸外层包装），让模糊透出来
        clearIfOpaque(container, "container")
        panel.clearTargets.forEach { clearIfOpaque(it, "sibling") }

        if (blurView == null) {
            // 没找到 BlurView：退而把玻璃挂在容器自身背景上
            attachGlass(container, act)
            return
        }
        // 2) 给 BlurView 挂玻璃背景 + 停掉它自带的位图绘制
        attachGlass(blurView, act)
        hookBlurViewOnDraw(blurView.javaClass.classLoader)
        hookBlurViewBgReset(blurView.javaClass.classLoader)
        clearBlurViewBitmap(blurView)
        // 3) BlurView 内部联动的 navigationBar 视图（底部导航条）一并挂玻璃，
        //    让底板直达窗口底边，而不是在导航条处断一截纯色
        navigationBarOf(blurView)?.let { nav ->
            runCatching { nav.alpha = 1f }
            attachGlass(nav, act)
        }
    }

    /** 取 BlurView 的 navigationBar 字段（按类型找 View 字段，不写死混淆名）。 */
    private fun navigationBarOf(blurView: View): View? {
        return runCatching {
            var cls: Class<*>? = blurView.javaClass
            while (cls != null && cls != View::class.java) {
                for (field in cls.declaredFields) {
                    if (field.type == View::class.java) {
                        field.isAccessible = true
                        val v = field.get(blurView) as? View
                        if (v != null && v !== blurView) return v
                    }
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()
    }

    // ---------------- 定位 ----------------

    private class Panel(
        val container: ViewGroup,
        val blurView: View?,
        val clearTargets: List<View>
    )

    private fun findPanel(decor: View): Panel? {
        // 主锚点：资源 id 名
        val container = findByIdName(decor, "rl_search_view") as? ViewGroup
        if (container != null) {
            val blurView = findByIdName(container, "search_blur_view")
            val clearTargets = mutableListOf<View>()
            findByIdName(container, "home_custom_bg")?.let { clearTargets.add(it) }
            findByIdName(container, "home_custom_container")?.let { clearTargets.add(it) }
            findByIdName(container, "gradient_layout")?.let { clearTargets.add(it) }
            findByIdName(container, "navigation_container")?.let { clearTargets.add(it) }
            log(
                "定位命中（id 名）: container=${container.javaClass.name} " +
                    "blurView=${blurView?.javaClass?.name} clear=${clearTargets.size}"
            )
            return Panel(container, blurView, clearTargets)
        }
        log("id 名 rl_search_view 未命中，退回结构性查找")
        return findPanelStructural(decor)
    }

    /** 结构性兜底：找贴底的 EditText，沿祖先链找到全宽的底部容器。 */
    private fun findPanelStructural(decor: View): Panel? {
        val edit = findSearchEditText(decor) ?: return null
        var v: View = edit
        var container: ViewGroup? = null
        var guard = 0
        while (guard < 10) {
            val parent = v.parent as? View ?: break
            if (parent.width >= decor.width * 0.85 && parent is ViewGroup) {
                container = parent
                // 再往上一层若仍全宽且其底边贴近窗口底部，优先用更外层
                val gp = parent.parent as? View
                if (gp != null && gp.width >= decor.width * 0.85) {
                    v = parent
                    guard++
                    continue
                }
                break
            }
            v = parent
            guard++
        }
        val c = container ?: return null
        log("定位命中（结构性）: container=${c.javaClass.name}")
        return Panel(c, null, emptyList())
    }

    private fun findSearchEditText(root: View): EditText? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 3000) {
            val v = stack.removeLast()
            guard++
            if (v is EditText && v.visibility == View.VISIBLE) {
                // 同一容器链里应有「搜索」文本按钮，避免误伤设置页等其它输入框
                var p: View? = v
                repeat(6) {
                    p = p?.parent as? View
                    if (p != null && subtreeHasText(p, "搜索")) return v
                }
            }
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    private fun subtreeHasText(root: View, text: String): Boolean {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 300) {
            val v = stack.removeLast()
            guard++
            if (v is TextView && v.text?.toString() == text) return true
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
        return false
    }

    private fun findByIdName(root: View, entryName: String): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 3000) {
            val v = stack.removeLast()
            guard++
            if (v.id != View.NO_ID) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name == entryName) return v
            }
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    // ---------------- 挂玻璃 ----------------

    /** 把系统背景模糊 Drawable 挂到 host 上；重复调用时校验/复用。 */
    private fun attachGlass(host: View, ctx: Context) {
        val existing = glassHosts[host]
        if (existing != null) {
            if (host.background !== existing) {
                host.background = existing
                log("背景被 App 重置过，已重挂玻璃: ${host.javaClass.name}")
            }
            return
        }
        val drawable = createBlurDrawable(host, ctx) ?: run {
            log("HnBackgroundBlurDrawable 创建失败，退回半透明底色: ${host.javaClass.name}")
            val night = isNight(ctx)
            val fallback = ColorDrawable(if (night) 0xB3242426.toInt() else 0xD9F2F2F4.toInt())
            host.background = fallback
            glassHosts[host] = fallback
            return
        }
        host.background = drawable
        glassHosts[host] = drawable
        log("玻璃已挂载: ${host.javaClass.name} size=${host.width}x${host.height}")
    }

    private fun createBlurDrawable(host: View, ctx: Context): Drawable? {
        val cl = host.javaClass.classLoader ?: ctx.classLoader
        val cls = loadClass(cl, "com.hihonor.android.graphics.drawable.HnBackgroundBlurDrawable")
            ?: loadClass(cl, "com.hihonor.android.graphics.HnBackgroundBlurDrawable")
            ?: return null
        val d = step("new HnBackgroundBlurDrawable") {
            cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } ?: return null
        step("createBlurDrawable(host)") {
            cls.getMethod("createBlurDrawable", View::class.java).invoke(d, host)
        } ?: return null
        step("setBlurRadius($BLUR_RADIUS)") {
            cls.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                .invoke(d, BLUR_RADIUS)
        }
        step("setSaturation($BLUR_SATURATION)") {
            cls.getMethod("setSaturation", Float::class.javaPrimitiveType)
                .invoke(d, BLUR_SATURATION)
        }
        val tint = if (isNight(ctx)) TINT_DARK else TINT_LIGHT
        step("setColor(0x${Integer.toHexString(tint)})") {
            cls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(d, tint)
        }
        // setBackgroundBlur 是 AOSP BackgroundBlurDrawable 的收尾步：
        // 有些 ROM 实现里 createBlurDrawable 只登记区域，这一步才真正启用。
        step("setBackgroundBlur(host)（可选）") {
            cls.getMethod("setBackgroundBlur", View::class.java).invoke(d, host)
        }
        @Suppress("UNCHECKED_CAST")
        return d as? Drawable
    }

    /**
     * hook BlurView.onDraw：玻璃挂上后跳过它自带的位图绘制，
     * 否则 App 的预模糊位图会盖在玻璃上面（双重渲染、观感发灰）。
     * 只对已挂玻璃的实例生效，其它 BlurView 实例照常放行。
     */
    private fun hookBlurViewOnDraw(classLoader: ClassLoader?) {
        if (onDrawHooked) return
        val cl = classLoader ?: return
        val cls = runCatching { Class.forName(BLUR_VIEW_CLASS, false, cl) }.getOrNull() ?: return
        val onDraw = runCatching {
            cls.getDeclaredMethod("onDraw", android.graphics.Canvas::class.java)
        }.getOrNull() ?: return
        runCatching {
            module.hook(onDraw).intercept { chain ->
                val self = chain.thisObject as? View
                if (self != null && glassHosts.containsKey(self)) {
                    null // 跳过原绘制：背景玻璃已经接管这块区域
                } else {
                    chain.proceed()
                }
            }
            onDrawHooked = true
            log("BlurView.onDraw hook 已安装")
        }.onFailure { log("BlurView.onDraw hook 失败（不致命，继续）", it) }
    }

    /**
     * hook BlurView 的 setSearchViewBgBlur（混淆名 a(ZZ)，按类+签名定位）：
     * App 在 IME 状态/配置变化时会调它重设底板与导航条的背景，会盖掉我们
     * 的玻璃——它执行完后立刻把已挂实例的背景换回我们的 Drawable。
     * 找不到该方法（版本变化）就静默跳过，靠获焦/布局补挂兜底。
     */
    private fun hookBlurViewBgReset(classLoader: ClassLoader?) {
        if (bgResetHooked) return
        val cl = classLoader ?: return
        val cls = runCatching { Class.forName(BLUR_VIEW_CLASS, false, cl) }.getOrNull() ?: return
        val method = runCatching {
            cls.getDeclaredMethod(
                "a",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull() ?: run {
            log("未找到 BlurView.a(ZZ)，跳过背景重置 hook（靠补挂兜底）")
            return
        }
        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val self = chain.thisObject as? View
                    if (self != null && glassHosts.containsKey(self)) {
                        attachGlass(self, self.context)
                    }
                    // navigationBar 的背景也被 a() 一并改过，同样补回
                    if (self != null) {
                        navigationBarOf(self)?.let { nav ->
                            if (glassHosts.containsKey(nav)) {
                                runCatching { nav.alpha = 1f }
                                attachGlass(nav, nav.context)
                            }
                        }
                    }
                }.onFailure { log("a() 后补挂异常（已吞）", it) }
                result
            }
            bgResetHooked = true
            log("BlurView.a(ZZ) 背景重置 hook 已安装")
        }.onFailure { log("BlurView.a(ZZ) hook 失败（不致命，继续）", it) }
    }

    /** 把 BlurView 内部持有的预模糊 Bitmap 字段清空（按类型找字段，不写死混淆名）。 */
    private fun clearBlurViewBitmap(blurView: View) {
        runCatching {
            var cls: Class<*>? = blurView.javaClass
            while (cls != null && cls != View::class.java) {
                for (field in cls.declaredFields) {
                    if (field.type == Bitmap::class.java) {
                        field.isAccessible = true
                        field.set(blurView, null)
                        log("已清 BlurView 的 Bitmap 字段 ${field.name}")
                    }
                }
                cls = cls.superclass
            }
        }.onFailure { log("清 BlurView Bitmap 失败（不致命）", it) }
    }

    /** 背景是不透明的才清（半透明/透明的不动，避免误伤渐变边框这类装饰）。 */
    private fun clearIfOpaque(v: View, role: String) {
        val bg = v.background ?: return
        val opaque = when (bg) {
            is ColorDrawable -> Color.alpha(bg.color) > 0xE0
            else -> false // GradientDrawable 等形状背景交给玻璃宿主自己处理
        }
        if (opaque) {
            v.background = null
            log("清掉不透明背景（$role）: ${v.javaClass.name}")
        }
    }

    private fun watchLayout(container: ViewGroup, act: Activity) {
        if (!watched.add(container)) return
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 键盘动画期间布局回调很密：先廉价地核对已挂的玻璃还在不在，
            // 全都完好就直接跳过，避免每次都全树遍历定位。
            val broken = synchronized(glassHosts) {
                glassHosts.entries.any { (host, drawable) -> host.background !== drawable }
            }
            if (broken) {
                runCatching { apply(act, "layout") }
                    .onFailure { log("layout 补挂异常（已吞）", it) }
            }
        }
    }

    private fun isNight(ctx: Context): Boolean {
        val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------------- 反射小工具 ----------------

    private fun loadClass(cl: ClassLoader, name: String): Class<*>? {
        return try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            log("loadClass FAIL: $name -> $t")
            null
        }
    }

    private fun step(name: String, block: () -> Any?): Any? {
        return try {
            val r = block()
            log("STEP OK: $name")
            r
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) t.targetException ?: t else t
            log("STEP FAIL: $name -> $cause")
            null
        }
    }

    private fun log(msg: String, t: Throwable? = null) {
        if (t != null) module.log(Log.INFO, TAG, msg, t) else module.log(Log.INFO, TAG, msg)
    }

}
