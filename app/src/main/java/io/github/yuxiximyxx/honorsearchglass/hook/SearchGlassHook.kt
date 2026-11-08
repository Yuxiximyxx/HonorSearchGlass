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
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.WeakHashMap

/**
 * SearchGlassHook —— 只把荣耀搜索首页底部的白色搜索药丸框换成毛玻璃。
 *
 * 口径（v0.1.1 定死、v0.1.2 沿用）：v0.1.0 做过的底板（BlurView）玻璃已
 * 全部回退，本模块完全不碰 BlurView 的背景/onDraw/Bitmap、不清叠层
 * 背景、导航条视图也不挂玻璃；唯一目标是药丸框本体。
 *
 * v0.1.2 修的是定位：v0.1.1 真机日志显示每一次尝试都「HwSearchView 未
 * 命中」+ 结构性兜底也落空，药丸从未被挂上。静态核对（见
 * docs/stage0-findings.md「v0.1.2 增补」）：
 *   - search_plate = com.hihonor.uikit.hwsearchview.R.id.search_plate
 *     （uikit 库资源，合并进 App 资源表后常量 0x7f0a0992，entry name 就
 *     是 "search_plate"），本体是 LightStrokeLinearLayout；HwSearchView
 *     字段 e（View 型）= findViewById(R.id.search_plate) 是其唯一绑定点。
 *   - rl_search_view/search_blur_view 在同一张表里 v0.1.0 用 id 名命中
 *     过，证明「资源 entry name 全树查找」在本 App 可靠。
 *
 * 本版定位策略（按优先级）：
 *   1) 主锚点：decor 全树按资源 id 名找 search_plate；
 *   2) 辅锚点：按 id 名找 search_src_text（EditText）/ 搜索按钮，沿祖先
 *      链逐层比对 id 名/类名找 search_plate；
 *   3) 次级：类名找 HwSearchView（类名相等，或类名包含 HwSearchView，
 *      或父类链含该类），再在其子树按 id 名找 search_plate；
 *   4) 结构性兜底（v0.1.1 原有逻辑保留）。
 *
 * 重试设计：宿主没挂上之前不放弃——onResume 延时（500ms + 新增
 * 800ms/2000ms 两档）、获焦都会再试；HomeActivity 出现后给
 * rl_search_view 容器（找不到则 decor）挂 OnGlobalLayoutListener，
 * 未定位期间每次全局布局都节流重试（≤30 次），定位成功即摘除；
 * 已挂上后布局监听转为廉价校验（背景被重置才补挂）。
 * 重试用尽仍未命中时，一次性把 rl_search_view 子树（找不到容器就
 * decor 顶层两级）按「类名+id 名+背景类型」逐行 dump 进日志（上限
 * 120 个节点、只 dump 一次），供下一轮直接定死药丸真实形态。
 *
 * 挂玻璃沿用 v0.1.1：HnBackgroundBlurDrawable（radius 140、
 * saturation 1.4、tint 白天 0x4DFFFFFF / 夜间 0x4D000000），圆角保形
 * 双路（Drawable 自带圆角 API 优先、无则 OutlineProvider +
 * clipToOutline）；同级装饰层 GradientBorderLayout 不碰。所有步骤
 * 独立 runCatching + 写 LSPosed 日志，失败静默降级，绝不崩目标进程。
 */
object SearchGlassHook {

    private const val TAG = "HonorSearchGlass"
    private const val HOME_ACTIVITY =
        "com.hihonor.search.feature.mainpage.presentation.page.HomeActivity"
    private const val HW_SEARCH_VIEW_CLASS =
        "com.hihonor.uikit.hwsearchview.widget.HwSearchView"

    // 资源 id 名锚点（entry name，运行时经 getResourceEntryName 比对）
    private const val ID_CONTAINER = "rl_search_view"
    private const val ID_PLATE = "search_plate"
    private const val ID_SRC_TEXT = "search_src_text"
    private const val ID_SEARCH_BUTTON = "hwsearchview_search_text_button"

    // 磨砂参数：与 v0.1.0 底板同系（半径/饱和相同，tint 略厚保可读性）
    private const val BLUR_RADIUS = 140
    private const val BLUR_SATURATION = 1.4f
    private const val PILL_TINT_LIGHT = 0x4DFFFFFF // 白天：白色纱（约 30%）
    private const val PILL_TINT_DARK = 0x4D000000  // 夜间：黑色纱（约 30%）

    // 未定位期间布局重试的参数
    private const val MAX_LAYOUT_RETRIES = 30
    private const val RETRY_THROTTLE_MS = 150L
    private const val DUMP_NODE_LIMIT = 120

    private lateinit var module: XposedModule

    /** 已挂上玻璃的药丸 → 我们给它的 Drawable（复用同一实例重挂）。 */
    private val glassHosts = Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    /** 已安排过延时应用的 Activity，防 onResume 反复排队。 */
    private val scheduled = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    /** 已挂过全局布局监听的根视图（每 Activity 一个），防重复添加。 */
    private val layoutWatched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    /** 某 Activity 的布局重试状态：未命中次数。命中后不再计数。 */
    private val retryStates =
        Collections.synchronizedMap(WeakHashMap<Activity, RetryState>())
    /** 子树 dump 只做一次。 */
    @Volatile
    private var dumped = false

    private class RetryState {
        var attempts = 0
        var lastTryMs = 0L
        var found = false
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.module = module

        val activityClass = Class.forName("android.app.Activity", false, classLoader)

        val onResume = activityClass.getDeclaredMethod("onResume")
        module.hook(onResume).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.thisObject as? Activity
                if (act != null && act.javaClass.name == HOME_ACTIVITY) {
                    // 500ms 是 v0.1.1 原有的一档；800/2000ms 是 v0.1.2 新增的
                    // 延迟重试档——HwSearchView 由 App 异步装入 home_custom，
                    // 首查落空时靠后两档与布局监听兜底。
                    scheduleApply(act, 500, "resume")
                    scheduleApply(act, 800, "resume+800ms")
                    scheduleApply(act, 2000, "resume+2000ms")
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
                    apply(act, "focus")
                }
            }.onFailure { log("onWindowFocusChanged 后处理异常（已吞）", it) }
            result
        }

        log("hook 已安装（v0.1.2 口径：只改药丸，底板不动；id 名锚点+持续重试）：等 HomeActivity 出现")
    }

    private fun scheduleApply(act: Activity, delayMs: Long, from: String) {
        // 同一档延时只排一次（key 含档位，防 onResume 反复触发排队风暴）
        val key = "$from@${System.identityHashCode(act)}"
        if (!scheduledKeys.add(key)) return
        val decor = act.window?.decorView ?: run {
            scheduledKeys.remove(key)
            return
        }
        decor.postDelayed({
            scheduledKeys.remove(key)
            apply(act, from)
        }, delayMs)
    }

    private val scheduledKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private fun retryState(act: Activity): RetryState {
        synchronized(retryStates) {
            return retryStates.getOrPut(act) { RetryState() }
        }
    }

    /** 一次完整的「定位药丸 + 挂玻璃」流程，可重复调用（内部有幂等检查）。 */
    private fun apply(act: Activity, from: String) {
        val decor = act.window?.decorView ?: return
        ensureLayoutWatch(act, decor)
        val state = retryState(act)
        val pill = findPill(decor)
        if (pill == null) {
            if (!state.found) {
                log("[$from] 未定位到药丸，跳过本轮（布局监听会继续重试，剩余预算内）")
                maybeDump(act, decor)
            }
            return
        }
        state.found = true
        attachPillGlass(pill, act)
    }

    // ---------------- 定位药丸 ----------------

    private fun findPill(decor: View): View? {
        // 1) 主锚点：全树按资源 id 名找 search_plate
        findByIdName(decor, ID_PLATE)?.let {
            log(
                "药丸定位命中（锚点=id名 $ID_PLATE）: 类=${it.javaClass.name} " +
                    "id名=${ID_PLATE}"
            )
            return it
        }
        // 2) 辅锚点：找 EditText / 搜索按钮，沿祖先链找 search_plate
        findPlateViaSibling(decor)?.let { return it }
        // 3) 次级：类名找 HwSearchView（相等/包含/父类链），再找其内部 plate
        val hwSearch = findHwSearchView(decor)
        if (hwSearch != null) {
            val plate = findByIdName(hwSearch, ID_PLATE)
            if (plate != null) {
                log(
                    "药丸定位命中（锚点=类名 HwSearchView→id名 $ID_PLATE）: " +
                        "类=${plate.javaClass.name} id名=${ID_PLATE}"
                )
                return plate
            }
            log("HwSearchView 已找到但其内 search_plate 未命中，退回结构性查找")
        }
        // 4) 结构性兜底（限定在 rl_search_view 子树，找不到容器则全树）
        val searchRoot = findByIdName(decor, ID_CONTAINER) ?: decor
        return findPillStructural(searchRoot) ?: findPillStructural(decor)
    }

    /**
     * 辅锚点：先按 id 名找到 search_src_text（EditText）或搜索文字按钮，
     * 再沿其祖先链逐层找 id 名为 search_plate 的祖先（plate 是输入框的
     * 同级偏上容器：布局上 search_src_text 就在 plate 子树内）。
     */
    private fun findPlateViaSibling(decor: View): View? {
        val anchor = findByIdName(decor, ID_SRC_TEXT)
            ?: findByIdName(decor, ID_SEARCH_BUTTON)
            ?: return null
        var v: View? = anchor
        var guard = 0
        while (v != null && guard < 10) {
            if (idNameOf(v) == ID_PLATE) {
                log(
                    "药丸定位命中（锚点=辅锚点 ${idNameOf(anchor) ?: " sibling "}→祖先链 id名）: " +
                        "类=${v.javaClass.name} id名=${ID_PLATE}"
                )
                return v
            }
            v = v.parent as? View
            guard++
        }
        return null
    }

    /** 类名定位 HwSearchView：精确相等、类名包含、或父类链含该类都算。 */
    private fun findHwSearchView(root: View): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            val v = stack.removeLast()
            guard++
            if (isHwSearchView(v)) return v
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    private fun isHwSearchView(v: View): Boolean {
        var cls: Class<*>? = v.javaClass
        var depth = 0
        while (cls != null && depth < 12) {
            if (cls.name == HW_SEARCH_VIEW_CLASS) return true
            cls = cls.superclass
            depth++
        }
        return v.javaClass.name.contains("HwSearchView")
    }

    /**
     * 结构性兜底：从搜索 EditText 沿祖先链向上，找第一个带「不透明圆角
     * 背景」的容器——那就是药丸的承载体（白色 GradientDrawable 系背景）。
     */
    private fun findPillStructural(root: View): View? {
        val edit = findSearchEditText(root) ?: return null
        var v: View? = edit
        var guard = 0
        while (v != null && guard < 8) {
            val parent = v.parent as? View
            if (parent != null && isPillCarrier(parent)) {
                log(
                    "药丸定位命中（锚点=结构性）: 类=${parent.javaClass.name} " +
                        "id名=${idNameOf(parent) ?: "无"}"
                )
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
        while (stack.isNotEmpty() && guard < 6000) {
            val v = stack.removeLast()
            guard++
            if (idNameOf(v) == entryName) return v
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    private fun idNameOf(v: View): String? {
        if (v.id == View.NO_ID) return null
        return runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
    }

    // ---------------- 布局监听：未命中持续重试 / 已命中廉价校验 ----------------

    private fun ensureLayoutWatch(act: Activity, decor: View) {
        val watchRoot = findByIdName(decor, ID_CONTAINER) ?: decor
        if (!layoutWatched.add(watchRoot)) return
        val observer = watchRoot.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val state = retryState(act)
                if (!state.found) {
                    // 还未定位：节流后重试定位，预算用尽后摘除自己并 dump 定性
                    val now = System.currentTimeMillis()
                    if (now - state.lastTryMs < RETRY_THROTTLE_MS) return
                    state.lastTryMs = now
                    state.attempts++
                    runCatching { apply(act, "layout#${state.attempts}") }
                        .onFailure { log("layout 重试异常（已吞）", it) }
                    if (!state.found && state.attempts >= MAX_LAYOUT_RETRIES) {
                        log("布局重试已达上限 $MAX_LAYOUT_RETRIES 次仍未定位到药丸")
                        removeSelf(watchRoot)
                        maybeDump(act, decor)
                    }
                } else {
                    // 已定位：只做廉价校验——药丸背景被 App 重置了才重挂，
                    // 完好就直接跳过，避免每次布局都全树遍历。
                    val broken = synchronized(glassHosts) {
                        glassHosts.entries.any { (host, drawable) -> host.background !== drawable }
                    }
                    if (broken) {
                        runCatching { apply(act, "layout-reattach") }
                            .onFailure { log("layout 补挂异常（已吞）", it) }
                    }
                }
            }

            private fun removeSelf(root: View) {
                runCatching {
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        }
        runCatching {
            observer.addOnGlobalLayoutListener(listener)
            log("全局布局监听已挂（未定位时持续重试，上限 $MAX_LAYOUT_RETRIES 次）")
        }.onFailure { log("挂全局布局监听失败（不致命）", it) }
    }

    /** 重试预算耗尽仍未命中时，把底部子树结构一次性 dump 进日志定性。 */
    private fun maybeDump(act: Activity, decor: View) {
        val state = retryState(act)
        // 布局监听尚未耗尽预算时不急着 dump，等 resume/focus 各档都试过、
        // 且布局重试次数也积累到一定程度再定性（仍未命中才打）。
        if (state.found) return
        if (!dumped && state.attempts < MAX_LAYOUT_RETRIES) return
        if (dumped) return
        dumped = true
        runCatching {
            val container = findByIdName(decor, ID_CONTAINER)
            val sb = StringBuilder()
            if (container != null) {
                sb.append("子树dump（根=rl_search_view）:\n")
                dumpNodeRec(container, 0, sb, intArrayOf(0))
            } else {
                sb.append("子树dump（未找到 rl_search_view，dump decor 顶层两级）:\n")
                dumpShallow(decor, sb)
            }
            log(sb.toString().trimEnd())
            log("子树dump 结束（节点上限 $DUMP_NODE_LIMIT）")
        }.onFailure { log("子树dump 失败（已吞）", it) }
    }

    private fun dumpNodeRec(v: View, depth: Int, sb: StringBuilder, counter: IntArray) {
        if (counter[0] >= DUMP_NODE_LIMIT) return
        counter[0]++
        sb.append("  ".repeat(depth.coerceAtMost(12)))
            .append(describeNode(v)).append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (counter[0] >= DUMP_NODE_LIMIT) return
                dumpNodeRec(v.getChildAt(i), depth + 1, sb, counter)
            }
        }
    }

    private fun dumpShallow(decor: View, sb: StringBuilder): Int {
        var n = 0
        fun line(v: View, depth: Int) {
            if (n >= DUMP_NODE_LIMIT) return
            n++
            sb.append("  ".repeat(depth)).append(describeNode(v)).append('\n')
        }
        line(decor, 0)
        if (decor is ViewGroup) {
            for (i in 0 until decor.childCount) {
                val c = decor.getChildAt(i)
                line(c, 1)
                if (c is ViewGroup) {
                    for (j in 0 until c.childCount) line(c.getChildAt(j), 2)
                }
            }
        }
        return n
    }

    private fun describeNode(v: View): String {
        val bg = v.background
        val bgDesc = when (bg) {
            null -> "bg=null"
            is GradientDrawable -> "bg=GradientDrawable"
            is StateListDrawable -> "bg=StateListDrawable"
            is LayerDrawable -> "bg=LayerDrawable"
            else -> "bg=${bg.javaClass.simpleName}"
        }
        return "${v.javaClass.name} id=${idNameOf(v) ?: "无"} $bgDesc " +
            "size=${v.width}x${v.height}"
    }

    // ---------------- 给药丸挂玻璃（v0.1.1 原逻辑，参数不变） ----------------

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
