package io.github.yuxiximyxx.honorsearchglass.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.WeakHashMap

/**
 * SearchGlassHook —— 只把荣耀搜索首页底部的白色搜索药丸框换成毛玻璃。
 *
 * v0.1.1 口径（用户纠正后定死）：v0.1.0 做过的底板（BlurView）玻璃已全部
 * 回退——不再碰 BlurView 的背景/onDraw/Bitmap、不清叠层背景、导航条视图
 * 也不挂玻璃，底板完全恢复 App 原样渲染。本版唯一目标是药丸框本体。
 *
 * 目标结构（反编译自荣耀搜索 10.0.37.301，见 docs/stage0-findings.md）：
 *   HomeActivity 底部 SearchViewContainer（id/rl_search_view）
 *   → home_custom_container → home_custom_bg → home_custom（FrameLayout）
 *   里装着 uikit 的 HwSearchView（com.hihonor.uikit.hwsearchview.widget.
 *   HwSearchView，继承 AppCompat SearchView）。截图里那条白色全圆角药丸
 *   就是 HwSearchView 内部的 search_plate 视图：里面装着输入框
 *   （search_src_text）、扫码/更多图标与蓝色「搜索」文字按钮，白色胶囊
 *   背景挂在 search_plate 上（布局/主题给的 GradientDrawable 系背景）。
 *
 * 做法：
 *   1) 定位药丸：主锚点 = rl_search_view 子树里按类名找 HwSearchView，
 *      再按资源 id 名找它内部的 search_plate；找不到时退回结构性查找
 *      （从 EditText 沿祖先链找带不透明圆角背景的容器）；
 *   2) 给药丸挂 HnBackgroundBlurDrawable 系统实时背景模糊（与 v0.1.0
 *      底板同源路线）：createBlurDrawable → setBlurRadius(140) →
 *      setSaturation(1.4) → setColor（白天 0x4DFFFFFF / 夜间 0x4D000000，
 *      比底板略厚的纱，保证框内深色图标/文字与蓝色「搜索」可读）；
 *   3) 保胶囊轮廓：换背景前先从原背景读圆角半径（GradientDrawable 的
 *      cornerRadius，读不到用药丸高度一半）；优先调 HnBackgroundBlur-
 *      Drawable 自带的圆角 API（反射探测 setCornerRadius 的各种签名），
 *      没有就给药丸设圆角 OutlineProvider + clipToOutline=true 兜底；
 *   4) 补挂：App 若重设药丸背景，获焦/布局变化时校验，不是我们的
 *      Drawable 就复用同一实例重挂。
 *
 * 边框装饰层 GradientBorderLayout（id/gradient_layout）是独立视图，
 * 本模块不碰它，不会误清/盖住。所有步骤独立 runCatching + 写 LSPosed
 * 日志，失败静默降级，绝不崩目标进程。
 */
object SearchGlassHook {

    private const val TAG = "HonorSearchGlass"
    private const val HOME_ACTIVITY =
        "com.hihonor.search.feature.mainpage.presentation.page.HomeActivity"
    private const val HW_SEARCH_VIEW_CLASS =
        "com.hihonor.uikit.hwsearchview.widget.HwSearchView"

    // 磨砂参数：与 v0.1.0 底板同系（半径/饱和相同，tint 略厚保可读性）
    private const val BLUR_RADIUS = 140
    private const val BLUR_SATURATION = 1.4f
    private const val PILL_TINT_LIGHT = 0x4DFFFFFF // 白天：白色纱（约 30%）
    private const val PILL_TINT_DARK = 0x4D000000  // 夜间：黑色纱（约 30%）

    private lateinit var module: XposedModule

    /** 已挂上玻璃的药丸 → 我们给它的 Drawable（复用同一实例重挂）。 */
    private val glassHosts = Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    /** 已安排过延时应用的 Activity，防 onResume 反复排队。 */
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    /** 已挂过布局监听的视图，防重复添加。 */
    private val watched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

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
                    // 获焦时 App 可能刚把药丸背景重置过，补查一次（已挂好的是廉价空转）
                    apply(act, "focus")
                }
            }.onFailure { log("onWindowFocusChanged 后处理异常（已吞）", it) }
            result
        }

        log("hook 已安装（v0.1.1 口径：只改药丸，底板不动）：等 HomeActivity 出现")
    }

    private fun scheduleApply(act: Activity, delayMs: Long) {
        if (!scheduled.add(act)) return
        val decor = act.window?.decorView ?: return
        decor.postDelayed({
            scheduled.remove(act)
            apply(act, "resume")
        }, delayMs)
    }

    /** 一次完整的「定位药丸 + 挂玻璃」流程，可重复调用（内部有幂等检查）。 */
    private fun apply(act: Activity, from: String) {
        val decor = act.window?.decorView ?: return
        val pill = findPill(decor)
        if (pill == null) {
            log("[$from] 未定位到药丸框，跳过（视图树可能还没长好）")
            return
        }
        // 在药丸所在的底部容器上挂布局监听：键盘动画/背景被重置时兜底补挂
        val watchRoot = (findByIdName(decor, "rl_search_view") as? ViewGroup)
            ?: (pill.parent as? ViewGroup)
            ?: pill
        watchLayout(watchRoot, act)
        attachPillGlass(pill, act)
    }

    // ---------------- 定位药丸 ----------------

    private fun findPill(decor: View): View? {
        val searchRoot = findByIdName(decor, "rl_search_view") ?: decor
        // 主锚点：HwSearchView（类名定位）内部的 search_plate（资源 id 名）
        val hwSearch = findByClassName(searchRoot, HW_SEARCH_VIEW_CLASS)
            ?: findByClassName(decor, HW_SEARCH_VIEW_CLASS)
        if (hwSearch != null) {
            val plate = findByIdName(hwSearch, "search_plate")
            if (plate != null) {
                log("药丸定位命中（HwSearchView/search_plate）: ${plate.javaClass.name}")
                return plate
            }
            log("HwSearchView 已找到但 search_plate 未命中，退回结构性查找")
        } else {
            log("HwSearchView 未命中，退回结构性查找")
        }
        return findPillStructural(searchRoot) ?: findPillStructural(decor)
    }

    /**
     * 结构性兜底：从搜索 EditText 沿祖先链向上，找第一个带「不透明圆角
     * 背景」的容器——那就是药丸的承载体（白色 GradientDrawable 系背景）。
     * 只在 rl_search_view 子树范围内找，避免误伤页面其它输入框。
     */
    private fun findPillStructural(root: View): View? {
        val edit = findSearchEditText(root) ?: return null
        var v: View? = edit
        var guard = 0
        while (v != null && guard < 8) {
            val parent = v.parent as? View
            if (parent != null && isPillCarrier(parent)) {
                log("药丸定位命中（结构性）: ${parent.javaClass.name}")
                return parent
            }
            v = parent
            guard++
        }
        return null
    }

    /** 背景解开后是带圆角的不透明 GradientDrawable，就算药丸承载体。 */
    private fun isPillCarrier(v: View): Boolean {
        val gd = unwrapGradient(v.background) ?: return false
        val radius = runCatching { gd.cornerRadius }.getOrDefault(0f)
        if (radius < 4f) return false
        val color = runCatching { gd.color?.defaultColor ?: 0 }.getOrDefault(0)
        return android.graphics.Color.alpha(color) >= 0xE0
    }

    private fun unwrapGradient(d: Drawable?): GradientDrawable? {
        return when (d) {
            is GradientDrawable -> d
            is StateListDrawable -> {
                var found: GradientDrawable? = null
                runCatching {
                    for (i in 0 until d.stateCount) {
                        found = unwrapGradient(d.getStateDrawable(i))
                        if (found != null) break
                    }
                }
                found
            }
            is LayerDrawable -> {
                var found: GradientDrawable? = null
                runCatching {
                    for (i in 0 until d.numberOfLayers) {
                        found = unwrapGradient(d.getDrawable(i))
                        if (found != null) break
                    }
                }
                found
            }
            else -> null
        }
    }

    private fun findByClassName(root: View, className: String): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 3000) {
            val v = stack.removeLast()
            guard++
            if (v.javaClass.name == className) return v
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
        return null
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

    // ---------------- 给药丸挂玻璃 ----------------

    private fun attachPillGlass(pill: View, ctx: Context) {
        val existing = glassHosts[pill]
        if (existing != null) {
            if (pill.background !== existing) {
                pill.background = existing
                log("药丸背景被 App 重置过，已重挂玻璃: ${pill.javaClass.name}")
            }
            return
        }
        // 换背景前先读原圆角半径，读不到用高度一半（全圆角胶囊）
        var radius = readCornerRadius(pill.background)
        if (radius <= 0f && pill.height > 0) radius = pill.height / 2f

        val drawable = createBlurDrawable(pill, ctx, radius) ?: run {
            log("HnBackgroundBlurDrawable 创建失败，药丸退回半透明胶囊底色")
            val gd = GradientDrawable()
            gd.setColor(if (isNight(ctx)) 0xB3242426.toInt() else 0xD9FFFFFF.toInt())
            if (radius > 0f) gd.cornerRadius = radius
            pill.background = gd
            glassHosts[pill] = gd
            return
        }
        pill.background = drawable
        glassHosts[pill] = drawable
        log(
            "药丸玻璃已挂载: ${pill.javaClass.name} corner=${radius}px " +
                "size=${pill.width}x${pill.height}"
        )
    }

    /** 从原背景里读圆角半径（解开 StateList/Layer 包装找 GradientDrawable）。 */
    private fun readCornerRadius(d: Drawable?): Float {
        val gd = unwrapGradient(d) ?: return 0f
        return runCatching { gd.cornerRadius }.getOrDefault(0f)
    }

    private fun createBlurDrawable(host: View, ctx: Context, cornerRadius: Float): Drawable? {
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
        val tint = if (isNight(ctx)) PILL_TINT_DARK else PILL_TINT_LIGHT
        step("setColor(0x${Integer.toHexString(tint)})") {
            cls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(d, tint)
        }
        // setBackgroundBlur 是 AOSP BackgroundBlurDrawable 的收尾步：
        // 有些 ROM 实现里 createBlurDrawable 只登记区域，这一步才真正启用。
        step("setBackgroundBlur(host)（可选）") {
            cls.getMethod("setBackgroundBlur", View::class.java).invoke(d, host)
        }
        applyCornerRadius(d, cls, host, cornerRadius)
        @Suppress("UNCHECKED_CAST")
        return d as? Drawable
    }

    /**
     * 保胶囊轮廓：先反射探测 HnBackgroundBlurDrawable（及其 AOSP 基类
     * BackgroundBlurDrawable）自带的圆角 API——setCornerRadius 有
     * (float) / (float,float,float,float) / (float[]) 多种可能签名，
     * 外加 setCornerRadii(float[])；命中哪个用哪个。都没有时退回给
     * 药丸 View 设圆角 OutlineProvider + clipToOutline=true 兜底裁剪。
     */
    private fun applyCornerRadius(d: Any, cls: Class<*>, host: View, radius: Float) {
        if (radius <= 0f) {
            log("圆角半径未知（0），跳过保形处理")
            return
        }
        val f = Float::class.javaPrimitiveType!!
        val candidates: List<Pair<String, () -> Boolean>> = listOf(
            "setCornerRadius(float)" to {
                tryInvoke(cls, d, "setCornerRadius", arrayOf(f), arrayOf(radius))
            },
            "setCornerRadius(float x4)" to {
                tryInvoke(
                    cls, d, "setCornerRadius",
                    arrayOf(f, f, f, f), arrayOf(radius, radius, radius, radius)
                )
            },
            "setCornerRadius(float[4])" to {
                tryInvoke(
                    cls, d, "setCornerRadius",
                    arrayOf(FloatArray::class.java),
                    arrayOf(floatArrayOf(radius, radius, radius, radius))
                )
            },
            "setCornerRadii(float[8])" to {
                tryInvoke(
                    cls, d, "setCornerRadii",
                    arrayOf(FloatArray::class.java),
                    arrayOf(FloatArray(8) { radius })
                )
            }
        )
        for ((name, attempt) in candidates) {
            if (attempt()) {
                log("圆角保形：已调用 Drawable 自带 $name（r=$radius）")
                return
            }
        }
        // 兜底：用 View 的 outline 把背景裁成圆角胶囊
        runCatching {
            host.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            host.clipToOutline = true
            log("圆角保形：Drawable 无圆角 API，已用 OutlineProvider+clipToOutline 兜底（r=$radius）")
        }.onFailure { log("圆角保形兜底失败（玻璃可能露直角）", it) }
    }

    private fun tryInvoke(
        cls: Class<*>, target: Any, name: String,
        paramTypes: Array<Class<*>>, args: Array<Any>
    ): Boolean {
        return try {
            val m = cls.getMethod(name, *paramTypes)
            m.invoke(target, *args)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun watchLayout(watchRoot: View, act: Activity) {
        if (!watched.add(watchRoot)) return
        watchRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 键盘动画期间布局回调很密：先廉价地核对已挂的玻璃还在不在，
            // 完好就直接跳过，避免每次都全树遍历定位。
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
