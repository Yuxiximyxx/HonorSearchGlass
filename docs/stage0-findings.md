# 阶段 0：荣耀搜索 APK 反编译发现

样本：小乐真机提取的荣耀搜索 APK（`_____10.0.37.301_3_576v.apk`，45.9 MB）。
工具：aapt/aapt2/dexdump 静态解析 + jadx 1.5.1 反编译 classes3/4/5.dex。

## 基本信息

- 包名：`com.hihonor.search`
- versionName：`10.0.37.301`，versionCode：`100037301`
- minSdk 29 / targetSdk 36 / compileSdk 35；11 个 dex
- 搜索首页 Activity：`com.hihonor.search.feature.mainpage.presentation.page.HomeActivity`
  （全局搜索主页；另有 LauncherActivity/SplashActivity 等入口壳）

## 代码混淆情况

- **类名基本保留**：`com.hihonor.search.*` 的类名、资源名都可读
  （HomeActivity、SearchViewContainer、BlurView 等）。
- **成员名被 R8 压缩**：字段/方法多为单字母（BlurView 字段 b..l、方法 a(ZZ)），
  框架方法（onDraw 等）与部分 getter/setter 保留原名。
- 第三方 SDK 的 dex（classes3 的一部分）有整类改名（La0b; 风格）。
- 结论：hook 定位**不能写死成员名**，要用类名 + 资源 id 名 + 结构特征。

## 底部搜索框底板的结构（activity_search_home.xml 实证）

根 LinearLayout（纵向） = HomeLayout（上半页，weight=1）+ 底部
`SearchViewContainer`（id/rl_search_view，RelativeLayout 子类，
左右 margin + 底部 margin 12dp）。容器内部叠层（自下而上）：

1. `GradientBorderLayout`（id/gradient_layout）——渐变边框装饰层，
   对齐到 home_custom_container。
2. **`BlurView`（id/search_blur_view）——底板本体**：
   `com.hihonor.search.feature.mainpage.presentation.view.BlurView`，
   背景 = `drawable/search_blur_bg`（shape：solid `color/bg_search_view_blur`
   = `#0ddddddd`，顶部圆角 26dp）。它从容器顶边拉到 home_custom_container
   底边，覆盖推荐 chips 区 + 药丸区。
3. `FrameLayout`（id/bottom_container）——推荐 chips（fl_chips_container）、
   语音区（fl_asr_container）等内容。
4. `RelativeLayout`（id/home_custom_container）→ LinearLayout
   （id/home_custom_bg）→ FrameLayout（id/home_custom，searchViewType=1）
   ——真正的搜索药丸框（HwSearchView）宿主。
5. `LinearLayout`（id/navigation_container）——最底部手势导航条分隔区。

## 底板原背景的实现方式（jadx 反编译 BlurView / SearchViewContainer 实证）

荣耀搜索给底板准备了**两套渲染模式**，由云端配置 + 能力开关决定走哪套：

### 模式一：系统材质玻璃（新路径，默认未必开）

- `SearchViewContainer.setTransparent(...)`（混淆名 a，静态）组装材质参数
  （模糊度由云配置 `transparentDegree` 换算、饱和 1.3/1.1、tint
  #3F262626/#33262626 等、圆角、shapeAlpha），最后经 `zt8.setHnMaterial(...)`
  把 framework 的 HnMaterial 挂到 **SearchViewContainer 本身**。
- 开关 `vi6.S()` = 云配置（`cod` 的 transparentMode 等字段下发值 == "1"）
  **且** 非 YOYO 板式输入风格（`dre` = YoyoModeController，b() = isBoardStyle）。
  云配置没下发到这台设备/账号时 S() 为 false，新路径整体不走。
- S() 为真时 BlurView 完全让位：onDraw 直接 return、setSearchViewBgBlur
  只把背景设成 `color/transparent`。

### 模式二：BlurView 自绘（老路径）

- `BlurView`（extends View）持有 Bitmap 字段（**1/4 尺寸的预模糊背景图**，
  onDraw 里按 getLocationOnScreen × 0.25 算源矩形）、Rect/RectF/Path/Paint，
  沿顶部圆角 Path 裁剪绘制。
- 是否画位图由 `oe7.b()`（ImmersiveBlurUtil.isImmersiveBlurEnable，多项能力
  条件的合取）决定；不满足时 onDraw 不画，靠 setSearchViewBgBlur
  （混淆名 a(ZZ)）设置的 shape 背景（`bg_search_view_not_blur` 等纯色）。
- **键盘弹起（fold 态）时即便位图模式开着也走纯色分支**：onDraw 内部的
  几何条件（底板底边与预期位置对齐等）不满足时，用 CLEAR xfermode +
  `canvas.drawColor(bg_search_input_fold_blur)` 把整块底板刷成纯色，
  并联动 navigationBar 视图（BlurView 的 View 字段 e）的背景色。
- **小乐截图里那块不透明悬浮白板就是这个 fold 纯色分支**（当时键盘正弹起）：
  不是图层没对齐，是代码在键盘态主动放弃了模糊位图、改刷纯色
  （推断链：截图状态 = 键盘弹起 + 白板纯色，与该分支的触发条件吻合；
  是否还叠加了云配置未下发导致 S()=false，待真机看日志再定）。

页面上半部分的模糊同源：App 用同一套「桌面快照 → 预模糊位图」喂给上半页
的背景视图，并对部分容器走 HnMaterial（dex 字符串与 uikit 材质库
`com.hihonor.uikit.hnmagicsystem.graphics.material.*` 打包在 classes5.dex，
含 HnFrostGlassMaterial / HnLiquidGlassMaterial）。

## 本模块选定的路线

任务书路线优先级：① 底板透明化透出页面自身的模糊 → ② 底板自挂
HnBackgroundBlurDrawable → ③ RenderEffect 兜底。

实测结构下选 **② 为主**，理由：

- 路线①的「透出」不成立：页面模糊不是窗口级 blur-behind，也没有一张
  铺满全屏的模糊图层垫在底板下面——模糊是逐视图贴位图画的，底板下面
  没有现成模糊层可透；把底板单纯透明化只会露出未模糊的桌面原图。
- 另一条理论路线是「强制打开 App 自带模式一」（hook vi6.S() 或直接调
  setTransparent），参数是官方调校的、最「原厂」。但它受云配置对象
  （cod 的下发值）驱动，静态无法确认在小乐设备上强开后参数完整、
  材质真渲染（MagicGlassProbe 有过 HnMaterial 挂上但不渲染的前科），
  风险不可控——**留作 v0.2 备选，先用路线②拿到确定的渲染再说**。
- 路线②有真机实证：MagicGlassProbe v0.2/v0.5 已在微信进程内验证
  `HnBackgroundBlurDrawable`（createBlurDrawable → setBlurRadius /
  setSaturation / setColor → setBackground）在三方进程放行且真渲染；
  它是 SurfaceFlinger 层面对视图区域后方内容的实时模糊，与页面里
  「桌面被模糊」的观感同源，且不依赖 App 的云配置与位图开关状态。
- 配套动作：hook BlurView.onDraw 跳过其自带绘制（位图/纯色都不再画）、
  清空其 Bitmap 字段、清容器叠层里的不透明 ColorDrawable 背景；
  hook BlurView.setSearchViewBgBlur（a(ZZ)）在其重设背景后立刻补挂；
  BlurView 联动的 navigationBar 视图（窗口最底部导航条）也挂同一玻璃，
  让底板直达窗口底边；获焦/布局变化时再校验一遍背景是否被重置。
- 兜底：系统 Drawable 创建失败 → 半透明底色（至少去掉不透明白板）。

### 参数

- setBlurRadius = 140（AOSP BackgroundBlurDrawable 的半径口径，
  MagicGlassProbe 阶段 4 用 150 实测可渲染；页面自身参数待 jadx 补齐后对齐）
- setSaturation = 1.4
- setColor：白天 0x2EFFFFFF / 夜间 0x33000000（薄纱 tint，按 uiMode 切换）

以上参数为首版取值，**待小乐真机验收后按观感再调**。
