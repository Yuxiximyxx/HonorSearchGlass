# HonorSearchGlass

LSPosed 模块（libxposed API 102）：把**荣耀搜索**（`com.hihonor.search`）
底部那条**白色搜索药丸框**改成毛玻璃，与页面自身的模糊背景统一。

> **v0.1.1 口径变更**：v0.1.0 改的是药丸下面的底板（BlurView），用户纠正
> 他要改的一直是白色药丸框本体，且**底板那层玻璃也不要**。v0.1.1 已把
> v0.1.0 的全部底板改动回退（BlurView 恢复 App 原样渲染：背景/onDraw/
> 预模糊位图一律不碰，叠层背景不清，导航条视图不挂玻璃），**只给药丸框
> 挂玻璃**。

药丸框本体是 uikit `HwSearchView`（继承 AppCompat SearchView）内部的
`search_plate` 视图：白色全圆角胶囊，里面装着输入框、扫码/更多图标和
蓝色「搜索」文字。本模块定位到它后，把它的背景换成 MagicOS 系统实时
背景模糊（`HnBackgroundBlurDrawable`，半径 140 / 饱和 1.4 / 白天
0x4DFFFFFF、夜间 0x4D000000 的薄纱），并保住胶囊圆角（优先用 Drawable
自带圆角 API，无则 OutlineProvider + clipToOutline 兜底）。

- 作用域：仅 `com.hihonor.search`（荣耀搜索主进程）
- 目标版本：荣耀搜索 10.0.37.301（versionCode 100037301）；定位以类名 +
  资源 id 名 + 结构特征为主锚点，不写死混淆成员名，跨小版本应可继续
  工作（未逐版验证）
- 生效方式：LSPosed 启用模块 → 强行停止荣耀搜索 → 重新打开
- 分析记录：见 [docs/stage0-findings.md](docs/stage0-findings.md)
- 日志 tag：`HonorSearchGlass`（LSPosed 日志里可查每一步命中/失败）

签名：自有 keystore（`~/workspace/keystores/honorsearchglass.jks`，
证书 SHA256 C4:2B:4E:52:…:1B:B5:68），CI 由仓库 secret
`HONORSEARCHGLASS_KEYSTORE_B64` 解码注入。
