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

## 底板原背景的实现方式

- BlurView 是 App 自带的「贴背景模糊图」视图（extends View）：持有 Bitmap
  字段 + Rect/RectF/Path/Paint，onDraw 里把一张**预先模糊好的背景位图**
  按自身在窗口中的位置裁剪、沿顶部圆角 Path 绘制出来。
- 也就是说 App 本来就有「底板 = 页面模糊背景的延续」的机制；位图的来源与
  开关由上层控制器管理（代码里有 `updateBottomBlurView`、
  `mIsBottomBlurEnabled`、`SearchBoxParams(blurRadius=…)` 等日志/类名，
  在 classes3.dex）。
- 页面上半部分的模糊同样走位图 + 系统材质路线：dex 字符串实证 App 调用了
  framework 的 `com.hihonor.android.graphics.material.HnMaterial` /
  `setHnMaterial`、`RenderEffect`，且 APK 内打包了整套 uikit 材质库
  （`com.hihonor.uikit.hnmagicsystem.graphics.material.*`，classes5.dex，
  含 HnFrostGlassMaterial / HnLiquidGlassMaterial）。

（位图来源、底部模糊开关条件、页面模糊半径参数：jadx 反编译完成后补。）

## 本模块选定的路线

任务书路线优先级：① 底板透明化透出页面自身的模糊 → ② 底板自挂
HnBackgroundBlurDrawable → ③ RenderEffect 兜底。

实测结构下选 **② 为主**，理由：

- 路线①的「透出」不成立：页面模糊不是窗口级 blur-behind，也没有一张
  铺满全屏的模糊图层垫在底板下面——模糊是逐视图贴位图画的，底板下面
  没有现成模糊层可透；把底板单纯透明化只会露出未模糊的桌面原图。
- 路线②有真机实证：MagicGlassProbe v0.2/v0.5 已在微信进程内验证
  `HnBackgroundBlurDrawable`（createBlurDrawable → setBlurRadius /
  setSaturation / setColor → setBackground）在三方进程放行且真渲染；
  它是 SurfaceFlinger 层面对视图区域后方内容的实时模糊，与页面里
  「桌面被模糊」的观感同源，且不依赖 App 那张位图的开关状态。
- 配套动作：hook BlurView.onDraw 跳过其自带位图绘制（否则双重渲染发灰）、
  清空其 Bitmap 字段、清容器叠层里的不透明 ColorDrawable 背景；
  获焦/布局变化时校验背景是否被 App 重置、是则复用同一 Drawable 重挂。
- 兜底：系统 Drawable 创建失败 → 半透明底色（至少去掉不透明白板）。

### 参数

- setBlurRadius = 140（AOSP BackgroundBlurDrawable 的半径口径，
  MagicGlassProbe 阶段 4 用 150 实测可渲染；页面自身参数待 jadx 补齐后对齐）
- setSaturation = 1.4
- setColor：白天 0x2EFFFFFF / 夜间 0x33000000（薄纱 tint，按 uiMode 切换）

以上参数为首版取值，**待小乐真机验收后按观感再调**。
