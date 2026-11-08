# HonorSearchGlass

LSPosed 模块（libxposed API 102）：把**荣耀搜索**（`com.hihonor.search`）
底部搜索框的底板改成毛玻璃，与页面自身的模糊背景统一。

荣耀搜索页面上半部分是 App 自己做的桌面模糊背景，底部承载白色搜索药丸框的
底板却是不透明的悬浮白板，两头不搭。本模块在搜索首页（HomeActivity）里
定位到底板视图（`search_blur_view`），把它的渲染换成 MagicOS 系统的实时
背景模糊（`HnBackgroundBlurDrawable`），并停掉它自带的预模糊位图绘制，
使底板与页面背景成为同一层模糊。

- 作用域：仅 `com.hihonor.search`（荣耀搜索主进程）
- 目标版本：荣耀搜索 10.0.37.301（versionCode 100037301）；定位以资源 id 名
  与结构特征为主锚点，不写死混淆成员名，跨小版本应可继续工作（未逐版验证）
- 生效方式：LSPosed 启用模块 → 强行停止荣耀搜索 → 重新打开
- 分析记录：见 [docs/stage0-findings.md](docs/stage0-findings.md)
- 日志 tag：`HonorSearchGlass`（LSPosed 日志里可查每一步命中/失败）

签名：自有 keystore（`~/workspace/keystores/honorsearchglass.jks`，
证书 SHA256 C4:2B:4E:52:…:1B:B5:68），CI 由仓库 secret
`HONORSEARCHGLASS_KEYSTORE_B64` 解码注入。
