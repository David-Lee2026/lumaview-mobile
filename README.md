# LumaView Mobile 光影增强播放器

Android 本地视频播放器，恢复自固定版本 mpv-android，提供可见播放控制、暂停与倍速状态、触控／鼠标 ROI 框选放大、可见区域 GPU／兼容软件低光增强，以及 A/B 片段导出。

开发分支：`implementation/android-20261003`。包名 `org.lumaview.mobile`，最低 API 23，目标／编译 API 36，ARM64 与 x86_64 分包，测试版本 `0.1.2-test`。不合并到 main。

## 构建和下载

新版 `0.1.2-test`：[直接下载 ARM64 APK](https://github.com/David-Lee2026/lumaview-mobile/releases/download/v0.1.2-test/LumaView-Mobile-0.1.2-test-arm64-v8a.apk)、[SHA256SUMS](https://github.com/David-Lee2026/lumaview-mobile/releases/download/v0.1.2-test/SHA256SUMS.txt)、[完整构建日志与验证证据 ZIP](https://github.com/David-Lee2026/lumaview-mobile/releases/download/v0.1.2-test/LumaView-Mobile-0.1.2-test-build-and-validation.zip)、[构建报告](https://github.com/David-Lee2026/lumaview-mobile/releases/download/v0.1.2-test/BUILD_REPORT.txt)。公开链接已实际下载验证，不需要登录 GitHub。APK 44,102,610 字节，证据 ZIP 84,593,980 字节。

APK SHA256：`014802e4aeae1685c1f3c968e1c67826d5996a5b2df2d670ecbf14a3205a7ca7`。构建源码 `8b85c3c120113b1a99f2b83895b53dd2989b7e06`；[完整成功 run 37174350693](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37174350693)。三个构建各16项核心单测通过；API29/35 x86_64模拟器各9项集成测试与导出完整解码／压缩负载检查通过。

针对0.1.1真机仍黑屏的新反馈，华为/荣耀默认选择独立软件RGBA输出（不经过OpenGL/EGL）；更多→画面输出可选择兼容或OpenGL。兼容预览最长边960，导出保持源视频尺寸。正常回执在成功post后产生；输出切换先停止内核，再新建holder-owned SurfaceView，避免CPU与EGL的BufferQueue连接冲突。

已验证实际系统窗口中的原画、增强模式、+0.5EV、对比度和饱和度；增强面板重开保留原画对比／曝光锁，切换模式只提交一次并同步勾选框。360dp竖屏暂停按钮均分宽度且可点击；进度缺失样本不回零，30次采样不倒退且控制条高度保持，系统截图滑块真实前进。还覆盖旋转/ROI、1080p H.264与10-bit HEVC、两级GPU故障恢复、Surface重建和片段导出。具体失败与修复记录见[黑屏调查](docs/implementation/black-screen-investigation.md)。

这些结果均为CI、静态检查或模拟器结果。华为/Mali-G76真机、真实素材和持续性能验证仍为NOT_RUN；完整38项严格验收仍保留未执行项。0.1.1的成功模拟器结果未能代表用户真机，其历史构建为[run37150812083](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083)。

APK使用测试调试证书，尚未建立稳定发行签名；若安装提示签名冲突，须卸载旧测试版再安装，卸载会清除应用内历史与暂存。未合并main。

[APK workflow](.github/workflows/mobile-release.yml)复用成功run37127934301的native-arm64/native-x86_64，固定FFmpeg增加MP4 muxer，并重编译带LumaView扩展的固定mpv/JNI。证据包含SDK/NDK、原生与Gradle日志、签名/ABI/ZIP/ELF对齐、单元/集成结果、系统窗口PNG、诊断与导出媒体。

## 功能和当前限制

文件库支持系统文档、目录浏览和最近记录；播放器支持可见进度、快退／快进、0.25–2× 倍速、音量、画面方向、轨道／字幕、逐帧、截图与触控锁定。ROI 支持触控／鼠标选框、应用／取消和放大后平移。

SDR 增强包括 ROI 统计、带历史平滑的自动曝光、高光保护、局部提亮、空间降噪和有噪声门控的细节处理。HDR明确旁路；GPU源纹理超过2,073,600像素时保留原画，兼容路径缩放后处理；4K 增强预算优化尚未完成。原画对比保留当前 ROI。渲染回执与媒体、Surface、选区修订和请求编号对应。

原码流模式支持可证实 IDR 边界的 H.264／HEVC，确认窗显示请求区间和向外延展的实际安全区间；精确模式强制 H.264／AAC 重编码，仅用于 SDR 单视频／单音轨源。两种模式均不写入播放增强、ROI 裁剪或临时旋转。详细限制见 [验证说明](docs/implementation/validation-notes.md)。

APK 使用调试测试证书，尚未建立稳定发行签名。华为 Mate 20 X／HarmonyOS 4.0 真机验证为 `NOT_RUN`；模拟器、CI 与静态对齐检查不能代替华为真机验收。完整严格验收矩阵仍保留未执行项，不以局部自动化通过代替全套验收。

固定上游、已批准设计与实施方案见 `docs/`，保留上游许可证。不要提交个人视频、凭据或签名私钥。
