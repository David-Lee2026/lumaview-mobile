# LumaView Mobile 光影增强播放器

Android 本地视频播放器，恢复自固定版本 mpv-android，提供可见播放控制、暂停与倍速状态、触控／鼠标 ROI 框选放大、可见区域 GPU 低光增强，以及 A/B 片段导出。

开发分支：`implementation/android-20261003`。包名 `org.lumaview.mobile`，最低 API 23，目标／编译 API 36，ARM64 与 x86_64 分包，测试版本 `0.1.1-test`。不合并到 main。

## 构建和下载

黑屏／进度修复构建：[run 37150812083](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083)，源码提交 `af313c43423057eb381b5ec39e26bddb436621ba`。下载 [ARM64 APK 与校验／构建日志 ZIP](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083/artifacts/11284067796)、[API 29 验证证据](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083/artifacts/11284490899)、[API 35 验证证据](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083/artifacts/11284396232)。GitHub artifact 下载需登录，保留 30 天。

ARM64 APK SHA256：`2b4885f9e818221626d0fa8c188ac104f4f70708185330e0d13fb9fa66d28d9f`。三个构建各 13 项单测通过；API 29 / 35 x86_64 模拟器各 6 项真实屏幕／播放／ROI／导出集成测试和主机导出校验通过。华为真机与完整 38 项严格验收场景仍未执行。

默认使用软件解码，避免零复制硬件纹理路径；可从“解码设置”选择硬件复制解码。视频恢复官方 SurfaceView 显示路径，播放器控件采用软件 Canvas 绘制；固定请求 GLES 3，shader 故障逐级回退到兼容增强或原画，并明确显示状态。暂停曝光调整立即更新画面；缺失位置样本保留有效进度，连续播放以独立 UI 时钟平滑更新。具体调查和失败记录见 [黑屏调查](docs/implementation/black-screen-investigation.md)。

[APK 与集成验证 workflow](https://github.com/David-Lee2026/lumaview-mobile/actions/workflows/mobile-release.yml) 复用成功 run `37127934301` 的 `native-arm64`、`native-x86_64`。仅为固定 FFmpeg 增加 MP4 muxer，并重编译带 LumaView 扩展的固定 mpv 与 JNI；其他原生依赖复用现有产物。

成功 run 的 `LumaView-Android-arm64` 包含可直接安装的 ARM64 APK、`SHA256SUMS.txt`、依赖锁定、构建日志、签名／包信息／ZIP 与 ELF 对齐检查、单元测试结果。`LumaView-Android-x86_64-API29` 与 `LumaView-Android-x86_64-API35` 分别包含对应模拟器的真实触控／系统窗口截图、故障恢复、进度、ROI、导出媒体、编码器摘要和完整解码／压缩包负载检查。x86_64 APK 单独保存在 `LumaView-APK-x86_64-API29` / `API35`。`LumaView-readable-source` 固定同一源码提交；构建不会跟随移动中的分支 head。

## 功能和当前限制

文件库支持系统文档、目录浏览和最近记录；播放器支持可见进度、快退／快进、0.25–2× 倍速、音量、画面方向、轨道／字幕、逐帧、截图与触控锁定。ROI 支持触控／鼠标选框、应用／取消和放大后平移。

SDR 增强包括 ROI 统计、带历史平滑的自动曝光、高光保护、局部提亮、空间降噪和有噪声门控的细节处理。HDR 及源纹理超过 2,073,600 像素时明确保留原画；4K 增强预算优化尚未完成。原画对比保留当前 ROI。渲染回执与媒体、Surface、选区修订和请求编号对应。

原码流模式支持可证实 IDR 边界的 H.264／HEVC，确认窗显示请求区间和向外延展的实际安全区间；精确模式强制 H.264／AAC 重编码，仅用于 SDR 单视频／单音轨源。两种模式均不写入播放增强、ROI 裁剪或临时旋转。详细限制见 [验证说明](docs/implementation/validation-notes.md)。

APK 使用调试测试证书，尚未建立稳定发行签名。华为 Mate 20 X／HarmonyOS 4.0 真机验证为 `NOT_RUN`；模拟器、CI 与静态对齐检查不能代替华为真机验收。完整严格验收矩阵仍保留未执行项，不以局部自动化通过代替全套验收。

固定上游、已批准设计与实施方案见 `docs/`，保留上游许可证。不要提交个人视频、凭据或签名私钥。
