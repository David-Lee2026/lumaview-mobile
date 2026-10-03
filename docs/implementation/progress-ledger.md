# Android 实施进度与验证记录

0.1.1-test 源码构建提交：`af313c43423057eb381b5ec39e26bddb436621ba`。APK / 集成 run：[37150812083](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812083)。单测 run：[37150812072](https://github.com/David-Lee2026/lumaview-mobile/actions/runs/37150812072)。

ARM64 APK 已构建和静态验证。API 29 与 35 的 x86_64 模拟器各执行 6 项真实集成测试；每个构建的 13 项单测通过。系统窗口截图验证原画、增强、暂停曝光设置、软件解码、API35实际复制硬件解码／API29已证明软件回退、shader 故障恢复与 1080p H.264／10-bit HEVC；进度回归验证缺失样本不归零、连续采样不倒退且控制栏不垂直晃动且实际屏幕滑块前移。ROI 和两种片段导出仍保留实际数据验证。

完整 38 项严格验收与华为真机项目未执行，不以局部通过替代完整场景。

| 任务 | 内容 | 当前状态 | 验证边界 |
|---|---|---|---|
| T01 | 固定源码、最小测试基础与可构建安装包 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T02 | SAF 文件访问、文件页与只读输入租约 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T03 | 播放会话、异步代次与 Surface 安全生命周期 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T04 | 源视频坐标与统一视口数学 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T05 | 手势优先级与触点唯一所有权 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T06 | 可见播放控制、慢速、音量、轨道与横竖屏布局 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T07 | 原生 GPU 扩展、快照提交与实际应用回执 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T08 | 选区亮度统计、数值编码与曝光时间稳定 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T09 | 暗部降噪、局部提亮、高光与细节处理 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T10 | 增强面板、参数锁定、HDR 旁路与同视图对比 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T11 | 可调整触控选框、放大平移及区域增强联动 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T12 | 掉帧、热状态与增强资源自适应 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T13 | 微秒级 A／B 时间轴、边界预览与循环 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T14 | FFmpeg 原生原码流剪辑与随机访问边界证明 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T15 | Transformer 精确剪辑与所选轨道映射 | LIMITED_IMPLEMENTATION | SDR single-video/single-audio forced reencode; explicit multi-track refusal |
| T16 | 安全输出提交、取消与可恢复失败 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T17 | 无控件截图、诊断信息与本地隐私检查 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T18 | 整机流程回归与可复现证据归档 | IMPLEMENTED_PARTIAL_VALIDATION | Implementation present; bounded CI checks available, full scenario matrix not executed |
| T19 | Mate 20 X 实物素材与持续性能验收 | NOT_RUN | No Huawei Mate 20 X / HarmonyOS 4.0 physical device connected |
| T20 | 发布门禁、签名、源码一致性与完整测试包 | TEST_BUILD | Debug signed APK; no stable production signing or main merge; target physical validation pending |

详细结果见 `audit/execution-status.json`；未执行项和限制见 `audit/acceptance-current.json` 与 [验证说明](validation-notes.md)。

未合并 main。测试包使用调试证书，尚未配置稳定发行签名。华为 Mate 20 X／HarmonyOS 4.0 真机状态为 `NOT_RUN`。
