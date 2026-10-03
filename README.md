# LumaView Mobile 光影增强播放器

Android 本地视频播放器，基于固定版本 mpv-android，支持可见播放控制、暂停／快慢速状态、触控或鼠标 ROI 框选放大、可见区域 GPU 低光增强以及 A/B 片段导出。

开发分支：`implementation/android-20261003`；不会合并到 main。包名 `org.lumaview.mobile`，最低 API 23，目标／编译 API 36，ARM64 与 x86_64 分包。测试包版本 `0.1.0-test`。

## 构建和下载

[LumaView installable APK workflow](https://github.com/David-Lee2026/lumaview-mobile/actions/workflows/apk.yml) 复用已成功的 `native-arm64`、`native-x86_64`（run `37127934301`），为固定 FFmpeg 启用 MP4 muxer，重编译固定 mpv 的 ROI／增强补丁和 JNI，再构建 Android APK。下载成功 run 的 `LumaView-ARM64-installable` artifact，即可获得 APK 与 SHA256。

同一 run 上传构建日志、依赖锁定记录、签名／包信息／对齐检查、单元测试、模拟器 instrumentation 结果和原画／增强截图。`LumaView-emulator-validation-NOT-Huawei` 明确属于 API 35 x86_64 模拟器。

## 导出范围和验证

原码流导出仅支持有可证实 IDR 边界的 H.264，并显示实际延展的 GOP 区间；精确模式重新编码为 H.264/AAC。两种模式均不把播放增强、ROI 裁剪或临时旋转写入成片。不支持的额外轨道和字幕会在界面提示。

完整限制见 [验证说明](docs/implementation/validation-notes.md)。华为 Mate 20 X／HarmonyOS 4.0 真机验证尚未执行；CI、模拟器、静态对齐检查均不等于真机兼容性认证。APK 使用测试调试证书，尚未建立稳定发行签名。

固定上游与设计／实施方案见 `docs/`；保留上游许可证。请勿提交个人视频、账户凭据或签名私钥。
