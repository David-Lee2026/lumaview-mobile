# LumaView Mobile 首次执行记录：T01 检查点 01

日期：2026-10-03。执行方式：独立本地工作区、串行实施。用户已批准规格及实施计划，本轮没有再次修改功能范围。

## 当前结论

**实际编码已经开始，交付的是 T01 的构建前置与测试工具代码。T01 整项仍然 BLOCKED，没有编译 APK，也没有创建完成的 Android 播放器工程。** 原因是完整固定源码抓取失败、当前工作容器没有 Android SDK/NDK，依赖归档校验尚未完成。T02–T20 不冒称已开始或已通过。

## 已执行结果

| 检查 | 数量/结果 | 原始记录 |
|---|---|---|
| 记录校验器、子进程、设备选择 | 51 项主机测试通过 | `artifacts/runner-green.log` |
| 固定提交获取与上游 Gradle 定向适配 | 19 项主机测试通过 | `artifacts/source-green.log` |
| 构建前置检查 | 11 项主机测试通过 | `artifacts/preflight-green.log` |
| 合成媒体生成与主机完整解码 | 12 项主机测试通过 | `artifacts/fixtures-green.log` |
| 主机测试套件 | 93 passed；退出码 0 | `artifacts/host-tests.log`、`host-tests.xml` |
| 完整测试套件 | **93 passed，1 failed；退出码 1**。失败项是 `test_baseline`，原因明确为 BLOCKED / APK_NOT_BUILT | `artifacts/full-suite.log`、`full-suite.xml` |
| 对已核对原文回放 Gradle 补丁 | check/apply 均退出 0，结果与适配函数逐字节一致；没有执行 AGP 编译 | `artifacts/patch-replay.json` |
| 实际远程源码抓取 | fetch 退出 128；`Could not resolve host: github.com` | `artifacts/source-acquisition-actual.log` |
| 实际构建前置命令 | 退出 2，BLOCKED；没有创建 APK | `artifacts/preflight-actual.json` |
| 实际 Android 案例入口 | 退出 2，BLOCKED / APK_NOT_BUILT；observed=null | `artifacts/device-gate-actual.log` |

**以上 93 项只证明对应主机工具行为，不代表手机播放、触控、选区增强、剪辑或兼容性通过。** 38 项手机验收中没有 PASS，2 项前置受阻、36 项未运行。见 `audit/acceptance-current.json`。

## 本轮具体代码

`qa/runner.py` 实现证据路径/哈希/运行身份/基本观测的校验与有超时的真实子进程执行。它不抵抗能够伪造全部材料的恶意生产者，也不替代设备 Scenario；未实现的设备驱动明确拒绝运行，不返回模拟的成功值。

`buildscripts/source_tools.py` 只接受完整提交号和指定 HTTPS 来源；已有目标目录不覆盖；失败不留下假装完整的源码目录。其固定提交检出功能在临时本地 Git 仓库实际测试，远程固定上游抓取则实测受阻，两者没有混写。

`adapt_app_gradle` 仅接受已经核对 Git blob 的上游输入。补丁设置 `org.lumaview.mobile`、版本 `0.1.0-test` / code 1、ARM64，保留 JNI namespace，移除 allstorage 与上游 ABI 版本号重映射。完整应用 Manifest、本地播放器页面、JNI/原生库与 Android 构建尚未实现。

`lvm-build.sh` 当前只连接前置检查，**不是已经完成的 APK 构建器**。`lvm-lock.json` 明确标注不完整，并列出缺失的归档校验、子模块与工具链依赖；不将空 hash 或浮动分支当作固定供应来源。

## 合成输入

本轮另实际生成两段 640×360、30fps、约 3 秒 H.264 MP4：一段含 AAC 音轨，一段无声。两段均完成主机全程解码。记录包含生成命令、工具版本、真实探测结果及 SHA256。

- `BASELINE-AV`：406375 字节，`49edca26995819d54a18cf144be268675d8679e529feb2a848568961c1e80b54`。
- `BASELINE-SILENT`：367095 字节，`257dabab61b445da1fdb363100b15ede90e94357776228f6b927369c50b5e6b2`。

生成器第一次实现出现文件大小属性误调用；修正后又观察到只设置编码器色彩参数没有保留完整帧色彩标记。通过独立变体对照，确认在帧过滤器明确设置色彩信息后输出探测正确，保留了失败日志和对照记录。没有降低测试断言来绕过问题。

## 工作区和接续

本地独立工程目录：`lumaview-mobile`；分支 `implementation/t01`。没有向远程推送，也没有在原来的报价比较仓库创建工作流。构建前置包包含本次真实代码、测试、补丁、日志、合成素材、批准文件和可恢复 Git 历史。

下一个工作步骤仍为 T01：在获授权且可访问依赖的 Linux 构建环境获取完整固定源码、完成锁定校验、打通 native/Gradle 构建及 Android Scenario。当前需要的是构建环境与专用项目仓库权限，不是再次确认功能或实施方案。可使用独立 GitHub 仓库 `lumaview-mobile`，待用户提供仓库地址并授权提交代码、运行 Actions 后接续。
