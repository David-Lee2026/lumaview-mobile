# LumaView Mobile 逐任务实施计划（Implementation Plan）

> **For agentic workers:** 逐任务执行时使用 `superpowers:executing-plans`；只有执行环境确实提供独立子智能体时，才改用 `superpowers:subagent-driven-development`。以 `- [ ]` 跟踪步骤，不能以计划检查结果代替手机功能测试。

**版本：0.1｜日期：2026-10-03｜规格：已获用户确认｜实施计划：待执行方式确认**

**Goal:** 交付面向 Android ARM64 与 Mate 20 X／HarmonyOS 4.0 的 LumaView Mobile 测试版，实现触控播放、按当前可视区域增强、可调整框选放大和可视化片段导出。

**Architecture:** 在固定 mpv-android 源码上新增 Kotlin／Views 控制层，保留 libmpv 主播放及 Android EGL／MediaCodec 路径；用受控 native 补丁在同一渲染边界提交视口与增强快照。FFmpeg 原生库负责原码流导出，Media3 Transformer 仅负责精确重编码；文件访问和提交通过 SAF 租约与事务隔离。

**Tech Stack:** Kotlin／Android Views、NDK C/C++、GLES 3.0、libmpv、FFmpeg、Media3 Transformer 1.11.1；原生 Linux 构建；Python／Android instrumentation 仅用于测试工具。

**Spec:** `docs/superpowers/specs/2026-10-03-lumaview-mobile-design.md`。该文件随本包保留原字节；确认记录见 `audit/approval-record.json`。

## Global Constraints（直接继承规格）

- 首版：Android ARM64 APK；优先 Mate 20 X／HarmonyOS 4.0，不混称 HarmonyOS 5／6 原生 HAP。
- mpv-android：`fdf74f6830c47dbaa8a22ac79726e8303f1db5af`；libmpv：`0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6`；FFmpeg：`094a2f8a2a5e7fa64736e067de224ce28fdf5979`。
- `minSdk 23`、`compileSdk 36`、`targetSdk 36`；NDK r30；交付 `arm64-v8a`，`x86_64` 仅为单列的模拟器验证架构。
- 主增强路径 `vo=gpu`／Android EGL／GLES 3.0；不能用仅对 `gpu-next` 有效的 `glsl-shader-opts` 冒充参数通路。
- 不依赖 Google 登录、付费 API、账号或云端视频处理；默认系统文件／文件夹授权；不申请所有文件访问、摄像头、麦克风、无障碍或悬浮权限。
- 源输入只读；导出最多一个任务；原码流与精确重编码分开；两者均不写入播放增强、选区或用户附加旋转。
- 不纳入首版完成声明：运动补偿多帧降噪、神经网络超分、HDR完整增强、增强视频另存、任意角度旋转、DRM编辑、投屏和在线媒体聚合。
- 所有模块输出实际生效状态；原画对比保持同视图；UI不解码、转码、扫描全片或等待GPU整帧回读。
- 测试状态仅 `NOT_RUN / PASS / FAIL / BLOCKED`；模拟器／桌面结果不替代目标真机，空数据不能视为通过。

本轮新增的工具链细化值来自固定上游：NDK `30.0.16248370`、SDK Build Tools `36.0.0`、AGP `9.2.1`、Gradle `9.4.1`、Kotlin `2.2.21`。[R1–R2] 其完整下载校验与JDK兼容验证放在T01执行，不能拿“版本号已列出”当环境已配置。`11076708_latest`只是上游SDK归档文件名的一部分；实际归档仍需以固定标识和SHA256锁定，不执行浮动latest选择。

## Review Focus（跨模块最易遗漏的五类输入）

1. **失效Surface与晚到回调**：切媒体或旋转后旧结果不能污染新媒体；T03、T07、T18以代次／释放顺序断言覆盖。
2. **坐标与统计不同步**：非方形像素、固有裁剪、旋转、pinch／pan后，显示域与增强域必须一致；T04、T08、T11覆盖。
3. **文档授权与输入输出别名**：content URI不同也可能是同一文档，FD复制还可能共享seek偏移；T02、T14、T16覆盖。
4. **VFR与压缩依赖**：非关键帧、开放GOP、多轨、非零PTS不能靠固定fps或默认轨道误判；T13–T15覆盖。
5. **能力不足与热降级**：无浮点FBO、HDR、温度不可读、GPU计时缺失、方形高分辨率素材，必须真实旁路或报告未测；T08–T12、T19覆盖。

---

## 1. 当前进度与执行原则

用户已批准工程规格。本次仅交付任务计划、接口约定、验收映射和进度模板；**20项实施任务均为未开始，38项手机验收均为未执行**。本包无APK，无已实现手机源码，也没有触发远程构建。文中的函数、测试代码、qa脚本与native补丁路径是下一阶段需创建的约定，不是当前已存在的产品工具。

新增代码根目录使用独立 `lumaview-mobile` 工作区；不覆盖桌面项目、不将播放器代码塞进报价比较仓库。源文件沿用上游结构，新增模块按功能拆分；修改过的第三方代码必须同时保留patch。无新授权时不推送、部署或修改远程仓库。

命令统一从工程根目录执行；`<device>`代表执行时通过 `adb devices` 确认并记录的测试设备，不会自动选中用户其他设备。凡命令依赖的工具尚未创建，必须先完成负责它的任务，不靠把缺失命令记为跳过来通过验收。

## 2. 文件布局与模块所有权

| 区域 | 责任／约束 |
|---|---|
| `app/src/main/java/is/xyz/mpv/` | 保留上游桥接namespace；仅修改必要生命周期与接口，避免盲目批量改包名导致JNI失联 |
| `app/src/main/java/org/lumaview/mobile/` | 新增player、storage、viewport、gesture、enhance、clip、export、performance、ui等模块 |
| `app/src/main/jni/lumaview/` | 新JNI桥、原码流导出、安全FD访问；与主播放共用锁定FFmpeg构建 |
| `buildscripts/deps/mpv/` | 固定libmpv源码树；每次补丁更新都能在该完整提交上重放 |
| `native-patches/mpv/`、`shaders/lvm/` | 可审查原生补丁及着色器唯一源；build脚本由这里同步／嵌入，不维护两份手改shader |
| `qa/`、`app/src/androidTest/`、`app/src/test/` | 主机计算、原生GPU、实际触控、媒体验证与报告，逐任务列出文件 |
| `artifacts/runs/<run_id>/` | 每次运行的原始证据；不能复用旧APK日志并改日期 |
| `release/`、`licenses/` | 发布清单、公共签名信息、开源来源及许可；不包含私钥／个人素材 |

《接口与测试契约》（`docs/implementation/interface-contracts.md`）定义所有跨模块公开数据类型；下方任务内的 Files 是准确到文件的实施位置，而不是要求当前创建整套空壳。

## 3. 阶段、依赖与检查点

| 阶段 | 任务 | 阶段交付 | 不允许越过的检查点 |
|---|---|---|---|
| M1 基础播放 | T01–T06 | 内部基础播放APK、文件授权、稳定会话、可见播放控制 | 未加载真实native库／触控进度条不成立，不能进入增强完成声明 |
| M2 区域增强 | T07–T11 | 首个值得验证核心功能的测试包：真像素增强＋可调整ROI＋局部统计 | E02区域独立与E03内部响应必须一起成立，不能靠固定曝光假通过 |
| M3 性能与编辑 | T12–T16 | 实际降级状态、A/B可视剪辑、两类导出与安全提交 | 复制码流和精确重编码证据分开；不以容器时长代替帧边界验证 |
| M4 集成与真机 | T17–T19 | 截图隐私诊断、整机回归、目标设备与真实微光记录 | 无Mate20X实测时只列BLOCKED，不能写“真机流畅” |
| M5 交付 | T20 | APK、源码、补丁、构建资料、中文说明与校验 | test与validated两条发布门禁，受阻项必须可见 |

推荐主顺序：T01→T02→T03→T04→T05→T06→T07→T08→T09→T10→T11→T12→T13→T14→T15→T16→T17→T18→T19→T20。任务寄存器给出实际依赖，不存在循环依赖。具备真正独立执行者时可在T07之后并行准备T13–T14，但原生构建文件和状态契约必须单一负责人合并；本会话默认不把并行方案当作已启用。

阶段按证据推进，不给未经构建与设备验证的绝对工期承诺。失败优先定位根因并复现，不能在三个核心流程未成立时先用大量美化工作掩盖问题。

## 4. 逐任务实施

测试示例使用T01创建的 `run_case`：它执行该任务列出的真实Android／主机探针并返回观测字段。各任务首先编写探针与断言，再看到可解释的失败，才写产品实现。缺依赖／无设备是BLOCKED，不是功能红灯；接口可先加返回NOT_IMPLEMENTED的最小声明以让测试真正进入断言。T01先对证据校验器完成自身的红—绿测试，再构建未改应用标识的固定上游，在可用测试设备上观察applicationId断言失败；基础构建或设备不可用只能记BLOCKED，不伪造功能红灯。

### T01　固定源码、最小测试基础与可构建安装包

**阶段：** M1　**依赖：** 无　**验收编号：** C01, C03　**状态：** NOT_STARTED

**Files**

- 修改：`app/build.gradle`。
- 修改：`app/src/main/AndroidManifest.xml`。
- 修改：`buildscripts/include/depinfo.sh`。
- 新建：`buildscripts/lvm-build.sh`。
- 新建：`buildscripts/lvm-lock.json`。
- 新建：`qa/conftest.py`。
- 新建：`qa/runner.py`。
- 新建：`qa/cases.json`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/BaselineScenario.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/ScenarioRunner.kt`。
- 新建：`qa/tests/test_runner_protocol.py`。
- 新建：`qa/generate_fixtures.py`。
- 新建：`qa/fixtures.json`。
- 测试断言：`qa/cases/test_t01.py`；测试结果：`artifacts/runs/<run_id>/T01/`。

**Interfaces**

输入：已批准规格；上游三个固定提交及构建脚本。

输出：`buildscripts/lvm-build.sh --abi arm64-v8a --stage baseline`；`run_case(case_id: str, options: dict | None = None) -> dict`，测试协议见《接口与测试契约》。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t01.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_baseline(run_case):
    r = run_case("T01/baseline")
    assert r["application_id"] == "org.lumaview.mobile"
    assert r["min_sdk"] == 23 and r["target_sdk"] == 36
    assert r["apk_abis"] == ["arm64-v8a"]
    assert r["unpinned_dependencies"] == []
    assert r["native_library_load_error"] is None
    assert r["evidence_validator_rejects_forged_pass"]
    assert r["version_name"] == "0.1.0-test" and r["version_code"] == 1
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t01.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 在独立本地工作区检出固定上游，保留原 Java/Kotlin namespace `is.xyz.mpv` 与旧 JNI 符号，另设独立 applicationId；删除 allstorage 发布变体。包装原生构建脚本，固定 NDK `30.0.16248370`、Build Tools `36.0.0`；保留基线 AGP／Gradle／Kotlin 版本。逐依赖记录来源、完整提交／版本、归档 SHA256 和许可证，不用浮动分支替代。 JDK采用21作为本计划构建起点，并在本任务用所锁AGP/Gradle的实际启动与构建校验；不通过则记录工具链冲突后修订锁文件，不能继续宣称基线可构建。固定应用versionName为0.1.0-test、versionCode为1。
- [ ] **步骤4：接入本任务调用方。** 先建立只测原始播放内核的 baseline 入口与测试探针；随后主入口由 T02／T06 接替。原生库未构建时不给出空壳 APK。构建脚本默认不安装／删除用户软件；首次安装只对指定测试设备执行。 建立ScenarioRunner与run_case的证据校验负例；同时创建generate_fixtures.py/fixtures.json的最小有声、无声H.264集合。后续任务在首次需要时追加自己的素材，不能等T18才生成前置测试素材。测试APK只安装到明确选择的测试设备。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t01.py -v`，随后运行 `python qa/runner.py --task T01 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t01 baseline"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 干净工作区可重复构建；安装／加载／4KB与16KB静态检查分别留证。没有 ARM64 设备时安装子项 BLOCKED，不把编译等同安装。

**重点限制：** 本轮不创建应用工程。T01 执行时，源码下载失败、缺工具链或依赖校验失败就停止该任务，不降级为来源未知的二进制。

### T02　SAF 文件访问、文件页与只读输入租约

**阶段：** M1　**依赖：** T01　**验收编号：** S01, C02　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/storage/DocumentAccess.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/storage/ReadLease.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/library/LibraryActivity.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/library/PlaybackHistory.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/DocumentScenario.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/fixtures/FaultDocumentsProvider.kt`。
- 新建：`app/src/androidTest/AndroidManifest.xml`。
- 新建：`app/src/main/java/org/lumaview/mobile/storage/DocumentModels.kt`。
- 修改：`app/src/main/AndroidManifest.xml`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t02.py`；测试结果：`artifacts/runs/<run_id>/T02/`。

**Interfaces**

输入：Android ContentResolver／系统实际授予的 URI 权限；T01 基础工程。

输出：`DocumentAccess.openRead(uri: Uri, allowCache: Boolean, reply: (Result<ReadLease>) -> Unit): Long`；`listChildren(tree: Uri, reply: (Result<List<DocumentEntry>>) -> Unit): Long`；`PlaybackHistory.save(documentId: String, positionUs: Long): Unit`。 `DocumentAccess.cancel(requestId: Long): Unit`；`DocumentAccess.reopenRead(identity: DocumentIdentity, reply: (Result<ReadLease>) -> Unit): Long`，导出调用方获取独立读取位置，不从DocumentIdentity猜文件路径。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t02.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_document(run_case):
    r = run_case("T02/document")
    assert r["seekable_open_copied_bytes"] == 0
    assert r["read_only"] and r["revoked_grant_error"] == "NEEDS_RESELECT"
    assert r["unknown_size_cancel_releases_fd"]
    assert r["cache_reserve_bytes"] == 256 * 1024 * 1024
    assert r["independent_read_offsets_verified"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t02.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现 openRead 和可关闭 ReadLease：URI 身份、FD／可寻址性、已知或未知长度、取消、缓存同意结果都显式保存。缓存先征求同意，边写边检查至少保留 256 MiB；授权不是永久有效承诺。
- [ ] **步骤4：接入本任务调用方。** 文件与文件夹授权页只消费异步列表；不猜真实路径、不在主线程递归扫描。最近播放只存私有目录；断点由后续 PlayerSession 的真实位置写入。对提供方报告的远程文档显示来源；无法判断时标为来源未知。 用仅测试包可注册的FaultDocumentsProvider模拟撤权、未知长度和读取失败；不篡改用户提供方或真实媒体。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t02.py -v`，随后运行 `python qa/runner.py --task T02 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t02 document"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 中文名、空格、只读文件、撤销权限、移动／删除、不可寻址 FD、未知长度均有可读结果；常规本地文件打开不整段复制。

### T03　播放会话、异步代次与 Surface 安全生命周期

**阶段：** M1　**依赖：** T01, T02　**验收编号：** P01, P04, F03　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/player/PlayerSession.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/player/PlayerStateReducer.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/player/SurfaceCoordinator.kt`。
- 修改：`app/src/main/java/is/xyz/mpv/BaseMPVView.kt`。
- 修改：`app/src/main/jni/event.cpp`。
- 新建：`app/src/main/jni/lumaview/surface_bridge.cpp`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/SessionScenario.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t03.py`；测试结果：`artifacts/runs/<run_id>/T03/`。

**Interfaces**

输入：`ReadLease`；上游 MPVLib，使用受控的单实例生命周期。

输出：`PlayerSession.open(input: ReadLease): Long`；`setPaused(paused: Boolean): Long`；`seekUs(positionUs: Long, mode: SeekMode): Long`；`setSpeed(speed: Double): Long`；`selectTrack(kind: TrackKind, mpvId: Long?): Long`；`observe(listener: (PlayerState) -> Unit): AutoCloseable`；`detachSurface(surfaceGeneration: Long): Long`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t03.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_session(run_case):
    r = run_case("T03/session")
    assert r["switch_iterations"] == 30 and r["stale_events_applied"] == 0
    assert r["surface_cycles"] == 30 and r["submit_after_invalidation"] == 0
    assert r["detach_ack_before_native_release"]
    assert r["decoder_name"] and r["codec_release_count"] == r["codec_create_count"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t03.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** PlayerSession 独占命令线程，MPV 事件回到状态归约器；每个请求携带媒体／Surface 代次和 requestId。停止旧媒体、清空待处理请求并完成旧资源释放后，才开放新代次。seek 命令受理不等于呈现完成。 本任务即在model/Contracts.kt声明PointD、SourceRect、ColorInfo、VideoGeometry、TrackKind/TrackInfo、PlayerState、SeekMode；PlayerStateReducer.kt只保存状态归约行为，后续不搬移模型定义。
- [ ] **步骤4：接入本任务调用方。** 以 SurfaceCoordinator 替换旧 Surface 回调的无屏障释放：回调首先禁止新帧提交；native 保持有所有权的窗口引用；渲染线程处理失效并发回 detach 回执后释放。处理 EGL_BAD_SURFACE／上下文丢失；UI 不等待完整解码或 GPU 回读。超时转可读错误并关闭会话，不能 sleep 固定时间后强释放仍在使用的对象。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t03.py -v`，随后运行 `python qa/runner.py --task T03 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t03 session"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 常见 H.264／HEVC、有声／无声实际播放；30 次切文件／后台／旋转重建无旧状态污染、无新增未回收对象。每次记录实际解码器，不以“请求硬解”推断硬解成功。

**重点限制：** 上游 BaseMPVView 有释放竞态提醒；T03 是原生增强的前置阻断任务。[R3]

### T04　源视频坐标与统一视口数学

**阶段：** M1　**依赖：** T03　**验收编号：** R04, R05, R06　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/viewport/ViewportController.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/viewport/ViewportPolicy.kt`。
- 新建：`app/src/test/java/org/lumaview/mobile/viewport/ViewportTest.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/ViewportScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t04.py`；测试结果：`artifacts/runs/<run_id>/T04/`。

**Interfaces**

输入：`PlayerState.videoGeometry`；系统显示尺寸与 density；容器裁剪、SAR、文件方向。

输出：`ViewportController.compute(geometry: VideoGeometry, viewport: ViewportRequest): ViewportSnapshot`；`screenToSource(point: PointD, snapshot: ViewportSnapshot): PointD?`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t04.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_viewport(run_case):
    r = run_case("T04/viewport")
    assert r["tested_rotations"] == [0, 90, 180, 270]
    assert r["max_roundtrip_source_error_px"] <= 1.0
    assert r["blackbar_point_rejected"] and r["container_crop_applied_once"]
    assert r["stats_rect"] == r["visible_source_rect"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t04.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 建立 double 精度的有效源矩形→SAR→合成直角旋转→适配窗口→缩放／平移的唯一矩阵；逆矩阵映射触点。源矩形用像素边界半开区间，取样时另加半像素约定；不把屏幕图像作为原图。 在Contracts.kt扩展ViewportRequest/ViewportSnapshot；ViewportPolicy.kt只实现边界合法性与取整策略。
- [ ] **步骤4：接入本任务调用方。** 屏幕显示与统计都消费同一 ViewportSnapshot／viewRevision；仅视口模块能产生裁剪。放大限制 1–8 倍，平移夹紧；全画面恢复保留容器正常裁剪。原生最终取整后的区域经回执反映到界面。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t04.py -v`，随后运行 `python qa/runner.py --task T04 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t04 viewport"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 固定目标网格在四个旋转、横竖屏、非方形像素、内置裁剪与黑边下回指同一目标；记录取整前后范围，≤1 源像素数学误差不等于视频变得更清晰。

### T05　手势优先级与触点唯一所有权

**阶段：** M1　**依赖：** T04　**验收编号：** R01, R02, R03, R06　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/gesture/GestureRouter.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/gesture/GestureState.kt`。
- 新建：`app/src/test/java/org/lumaview/mobile/gesture/GestureRouterTest.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/GestureScenario.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t05.py`；测试结果：`artifacts/runs/<run_id>/T05/`。

**Interfaces**

输入：`ViewportSnapshot`、MotionEvent 与控件命中结果。

输出：`GestureRouter.route(event: MotionEvent, hit: HitTarget): List<GestureAction>`；`cancel(reason: CancelReason): List<GestureAction>`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t05.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_gesture(run_case):
    r = run_case("T05/gesture")
    assert r["button_up_creates_selection"] is False
    assert r["roi_changes_playback_volume_backlight"] == [False, False, False]
    assert r["pointer_reorder_preserves_owner"] and r["action_cancel_clears_capture"]
    assert r["owner_changes_within_sequence"] == 0
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t05.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 以对话框／面板→已抓取手柄→框选→放大缩放平移→普通手势的固定顺序派发；跟踪 pointerId，不使用触点数组索引代替身份。控件抬手不得泄漏为新序列。
- [ ] **步骤4：接入本任务调用方。** 过小选框保持待选；多指中断、ACTION_CANCEL、窗口失焦、安全返回统一取消。锁定时只开放解锁热区。拖进度与调音量的同一输入序列不能同时传给框选模块。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t05.py -v`，随后运行 `python qa/runner.py --task T05 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t05 gesture"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 用包含第二指加入／第一指离开／取消的原始触控序列回放；只验证路由状态，不假称完成 T11 的真实选择界面验收。

### T06　可见播放控制、慢速、音量、轨道与横竖屏布局

**阶段：** M1　**依赖：** T02, T03, T05　**验收编号：** P02, P03, P05　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/ui/PlayerActivity.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/ui/TransportController.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/ui/TrackPanel.kt`。
- 新建：`app/src/main/res/layout/activity_lvm_player.xml`。
- 新建：`app/src/main/res/layout-land/activity_lvm_player.xml`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/TransportScenario.kt`。
- 修改：`app/src/main/AndroidManifest.xml`。
- 新建：`app/src/main/res/values/strings_lvm.xml`。
- 新建：`app/src/main/res/values-zh-rCN/strings_lvm.xml`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t06.py`；测试结果：`artifacts/runs/<run_id>/T06/`。

**Interfaces**

输入：`PlayerSession` 的状态回报；GestureRouter 的已归属操作；PlaybackHistory。

输出：`TransportController.onScrub(positionUs: Long, phase: DragPhase): Unit`；`setMediaVolume(normalized: Double): Unit`；`setWindowBrightness(value: Float): Unit`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t06.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_transport(run_case):
    r = run_case("T06/transport")
    assert r["seek_fractions"] == [0.25, 0.75] and r["restores_prior_pause"]
    assert r["preview_min_interval_ms"] >= 150 and r["pending_preview_max"] <= 1
    assert r["speed_choices"] == [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2]
    assert r["min_touch_target_dp"] >= 48 and r["window_brightness_only"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t06.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现真实可拖动时间轴、−10 秒／播放暂停／+30 秒、速度选择与单一媒体音量；mpv 音量维持 unity，音量条及右侧手势驱动同一 STREAM_MUSIC，不串联两层增益。左侧手势只改当前 Window 的亮度。
- [ ] **步骤4：接入本任务调用方。** 时间未知／不可寻址禁用拖动；150ms 合并预览，最终 seek 呈现回执到达才恢复状态。合成实际PTS而非固定FPS假定。3秒自动隐藏只用于普通播放；暂停、剪辑、选框、错误及面板期间常显。轨道列表用真实 ID；继承外挂字幕需独立 SAF 授权。适配系统返回、触控锁定与系统栏 inset，不沿用上游默认网络打开入口。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t06.py -v`，随后运行 `python qa/runner.py --task T06 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t06 transport"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 真实手势拖动、慢速、静音恢复及横竖屏截图通过；扬声器听音单列 T19，模拟器属性变化不算听音。M1 仅形成基础播放测试包，画面增强仍标未实现。

### T07　原生 GPU 扩展、快照提交与实际应用回执

**阶段：** M2　**依赖：** T03, T04　**验收编号：** E01, E04, R06, F03　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/enhance/NativeEnhancementStage.kt`。
- 新建：`app/src/main/jni/lumaview/enhancement_bridge.cpp`。
- 修改：`app/src/main/jni/Android.mk`。
- 新建：`native-patches/mpv/0001-lvm-frame-stage.patch`。
- 修改：`buildscripts/deps/mpv/video/out/gpu/video.c`。
- 修改：`buildscripts/deps/mpv/video/out/vo_gpu.c`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/NativeStageScenario.kt`。
- 补丁新增：`buildscripts/deps/mpv/libmpv/lvm_ext.h`。
- 补丁新增：`buildscripts/deps/mpv/video/out/gpu/lvm_stage.h`。
- 补丁新增：`buildscripts/deps/mpv/video/out/gpu/lvm_stage.c`。
- 补丁修改：`buildscripts/deps/mpv/player/client.c`。
- 补丁修改：`buildscripts/deps/mpv/video/out/vo.h`。
- 补丁修改：`buildscripts/deps/mpv/video/out/gpu/video.h`。
- 补丁修改：`buildscripts/deps/mpv/meson.build`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t07.py`；测试结果：`artifacts/runs/<run_id>/T07/`。

**Interfaces**

输入：PlayerSession 的原生句柄（不暴露裸指针到 UI）；ViewportSnapshot；Surface 代次。 共享EnhanceParams／StatsState布局本任务预先声明，后续填充行为。

输出：`submitFrame(snapshot: FrameSnapshot): SubmitStatus`；`observeReceipt(listener: (RenderReceipt) -> Unit): AutoCloseable`。新增补丁 ABI：`mpv_lvm_submit(mpv_handle*, const lvm_frame_v1*)`、`mpv_lvm_poll_receipt(mpv_handle*, lvm_receipt_v1*)`，详见契约；这些不是既有 mpv API。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t07.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_native_stage(run_case):
    r = run_case("T07/native_stage")
    assert r["vo"] == "gpu" and r["uniform_updates_seen"] > 0
    assert r["receipt_view_revision"] == r["displayed_view_revision"]
    assert r["shader_recompiles_during_drag"] == 0 and r["decoder_reopens_during_drag"] == 0
    assert r["released_surface_receipts_accepted"] == 0
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t07.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 在固定 libmpv 源码查明 RGB转换后、视频放大及字幕合成前的实际插入点，并保存源码锚点与补丁。初始增强 stage 为恒等输出，先证明 JNI→邮箱→渲染线程 uniforms→回执链路。用长度1合并邮箱替换过时快照，所有GL对象由渲染线程拥有。 同时在Contracts.kt声明FrameStamp、FrameSnapshot、RenderReceipt、SubmitStatus、ResetReason、EnhanceMode/EnhanceParams、LockRequest与StatsState的数据布局，先以原画通路及无效数值标识接通，不等待后续算法任务才定义ABI。
- [ ] **步骤4：接入本任务调用方。** 同一渲染边界应用视口与增强快照；以最终可见源矩形取样，不能UI独立设置video-crop再另送统计区域。复用上游纹理管理和状态缓存，不用未知 raw GL 调用破坏状态。所有变体预编译；paused 时请求重绘。主路径失败显示旁路原因，不能改成 mediacodec_embed 仍称增强已生效。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t07.py -v`，随后运行 `python qa/runner.py --task T07 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t07 native_stage"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 恒等阶段在设备上显示真实视频、更新已生效回执；变更选区不会重启解码器。未打通不能进入“弱光功能完成”声明。

**重点限制：** 固定 GPU 源文件的完整代码本轮未成功取得；插入函数与 patch context 需在此任务只读核对后定案。目标文件和所需语义已定，不伪造行号或已有扩展符号。glsl-shader-opts 不用于 vo=gpu。[R4]

### T08　选区亮度统计、数值编码与曝光时间稳定

**阶段：** M2　**依赖：** T07　**验收编号：** E02, E03, E07, E08　**状态：** NOT_STARTED

**Files**

- 新建：`native-patches/mpv/0002-lvm-roi-statistics.patch`。
- 新建：`buildscripts/deps/mpv/video/out/gpu/lvm_statistics.c`。
- 新建：`shaders/lvm/roi_stats.glsl`。
- 新建：`shaders/lvm/reduce.glsl`。
- 新建：`shaders/lvm/exposure.glsl`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/StatisticsScenario.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 新建：`qa/reference/statistics_reference.py`。
- 补丁修改：`buildscripts/deps/mpv/video/out/gpu/lvm_stage.h`。
- 补丁修改：`buildscripts/deps/mpv/meson.build`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t08.py`；测试结果：`artifacts/runs/<run_id>/T08/`。

**Interfaces**

输入：FrameSnapshot 的可见源矩形、颜色条件与实际视频PTS；T07 原生 stage。

输出：GPU 内部 `lvm_stats_update(struct lvm_stage*, const struct lvm_input_frame*, const struct lvm_rect*, double)`；输出 StatsState（有效权重、log2均值、亮区比例、exposureEv、historyEpoch）由 RenderReceipt 低频回报。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t08.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_statistics(run_case):
    r = run_case("T08/statistics")
    assert r["outside_background_delta_ev"] <= 0.05
    assert r["inside_darkening_gain_responds"]
    assert r["steady_ev_change_per_second"] <= 0.05
    assert max(r["float_error_ev"], r["packed_error_ev"]) <= 0.05 and r["nonfinite_count"] == 0
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t08.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现最长边64、另一边16–64的取样与分层归约，默认边界上下文权重0（仍满足规格≤10%）；区外黑白切换不得影响暗ROI。对数亮度范围初始化为[−16,0] EV；RGBA8后备用两通道16位规范编码，归约先解码再运算；不平均已编码字节。
- [ ] **步骤4：接入本任务调用方。** 时间平滑使用实测视频时间差和0.4秒时间常数、同场景1EV/秒限速；镜头突变按分布与连续性判断，清除历史并0.15秒过渡。重复呈现同一PTS不推进历史；seek／文件／Surface／颜色／视口提交变更清历史。所有浮点格式做真实FBO测试，不凭扩展名称判断。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t08.py -v`，随后运行 `python qa/runner.py --task T08 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t08 statistics"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** E02 必须和 E03 成对通过；E08 两种格式各有数值参考和GPU输出，无浮点支持的设备只能报告受测后备，不虚报另一分支。

### T09　暗部降噪、局部提亮、高光与细节处理

**阶段：** M2　**依赖：** T08　**验收编号：** E01, E05, E06　**状态：** NOT_STARTED

**Files**

- 新建：`native-patches/mpv/0003-lvm-enhancement-pass.patch`。
- 新建：`shaders/lvm/illumination.glsl`。
- 新建：`shaders/lvm/denoise_tonemap.glsl`。
- 新建：`shaders/lvm/detail.glsl`。
- 新建：`qa/reference/enhancement_reference.py`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/EnhancementQualityScenario.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 新建：`qa/analyze_pixels.py`。
- 补丁修改：`buildscripts/deps/mpv/video/out/gpu/lvm_stage.c`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t09.py`；测试结果：`artifacts/runs/<run_id>/T09/`。

**Interfaces**

输入：已验证的区域统计、颜色转换条件及预编译变体。

输出：`lvm_enhance_render(struct lvm_stage*, const struct lvm_input_frame*, const struct lvm_stats_state*, const struct lvm_params*)`；输出线性光计算后返回管线声明工作空间的纹理；原画变体严格恒等。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t09.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_enhancement_quality(run_case):
    r = run_case("T09/enhancement_quality")
    assert r["same_pts_same_view"] and r["screen_brightness_unchanged"]
    assert r["normalized_noise_variance_reduction"] >= 0.15
    assert r["edge_contrast_retention"] >= 0.90
    assert r["additional_saturated_fraction"] <= 0.005
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t09.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现≤256最长边照度纹理、3×3保边暗部降噪、线性光曝光和单调高光保护，再做门控细节增强；避免重复有限范围扩展。预设曝光上限原画0／自动2／弱光3／极弱光4／流畅2EV。 lvm_enhance_render的原生实现归入lvm_stage.c；qa/analyze_pixels.py在本任务创建，供后续集成复用。
- [ ] **步骤4：接入本任务调用方。** 流畅档跳过邻域降噪和细节仍保留ROI统计提亮。工作纹理最长边≤1920，同时像素数≤1920×1080；竖屏交换长宽，方形等比限面积，保证预算不被方形素材绕过。RGBA8存储用已声明编码空间，不把暗部线性值直接8位截断；浮点路径预算单独计数。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t09.py -v`，随后运行 `python qa/runner.py --task T09 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t09 enhancement_quality"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 同帧灰阶、肤色、细线和噪声块联合验证，不能只因平均亮度上升而判通过。冻结可复现实验参数，真实微光画质还需T19。

### T10　增强面板、参数锁定、HDR 旁路与同视图对比

**阶段：** M2　**依赖：** T06, T09　**验收编号：** E04, E09　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/enhance/EnhancementController.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/enhance/EnhanceDefaults.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/ui/EnhancementPanel.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/EnhancementUiScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t10.py`；测试结果：`artifacts/runs/<run_id>/T10/`。

**Interfaces**

输入：实际 RenderReceipt、ViewportSnapshot、PlayerState 的输入颜色信息。

输出：`EnhancementController.request(params: EnhanceParams): Unit`；`compareOriginal(enabled: Boolean): Unit`；`lockCurrent(enabled: Boolean): Unit`；`effectiveState(): EnhancementState`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t10.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_enhancement_ui(run_case):
    r = run_case("T10/enhancement_ui")
    assert r["compare_preserves"] == ["pts", "viewport", "zoom", "subtitle_state"]
    assert r["hdr_sdr_stage_active"] is False and r["hdr_reason_visible"]
    assert r["applied_label_requires_receipt"]
    assert r["locked_exposure_changed_by_quality_degrade"] is False
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t10.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现五档及规格全部手调范围，区分请求值、回执生效值和不可用原因。总曝光夹紧0–4EV；曝光锁定来自最后有效GPU回执，按原生请求／确认双阶段冻结，不锁未来未知参数。 EnhanceDefaults.kt仅持有默认值与范围校验，复用T07的EnhanceParams；EnhancementState在Contracts.kt扩展。
- [ ] **步骤4：接入本任务调用方。** 对比只旁路像素增强，不改变视口与播放位置；HDR按输入PQ／HLG或HDR元数据检测，不能因内核已转换SDR而误认源SDR。改变选区／媒体／颜色路径解除曝光锁定并提示；过热可减降噪，不偷改锁定曝光。首次加载失败、历史无有效回执或不支持效果时禁用对应控件。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t10.py -v`，随后运行 `python qa/runner.py --task T10 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t10 enhancement_ui"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 真实触控调整面板后像素或回执随之改变；HDR对比和锁定不篡改当前区域。

### T11　可调整触控选框、放大平移及区域增强联动

**阶段：** M2　**依赖：** T04, T05, T06, T07, T10　**验收编号：** R01, R02, R03, R04, R05, R06, E02, E03　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/viewport/RoiOverlayView.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/viewport/RoiController.kt`。
- 新建：`app/src/main/res/layout/panel_lvm_roi.xml`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/RoiTouchScenario.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t11.py`；测试结果：`artifacts/runs/<run_id>/T11/`。

**Interfaces**

输入：GestureRouter、ViewportController、EnhancementController、PlayerSession。

输出：`RoiController.begin(): Unit`；`apply(): Result<ViewportSnapshot>`；`cancel(): Unit`；`reset(): Unit`；`onGesture(action: GestureAction): Unit`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t11.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_roi_touch(run_case):
    r = run_case("T11/roi_touch")
    assert r["arm_only_on_button_tap"] and r["apply_restores_prior_pause"]
    assert r["minimum_source_pixels"] == [16, 16] and r["minimum_draw_dp"] == [24, 24]
    assert r["corner_resize_move_cancel_pass"] and r["zoom_limits"] == [1, 8]
    assert r["after_pan_visible_rect"] == r["after_pan_statistics_rect"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t11.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现待画／绘制／四角调整／整框移动／应用／取消的可视选择页；初绘边长均≥24dp且源尺寸均≥16像素才可应用。操作从已放大视图进入时保留入口变换，取消还原，应用后恢复原暂停状态。
- [ ] **步骤4：接入本任务调用方。** 手指操作通过统一逆矩阵更新快照；pinch焦点固定源像素，平移不调用快进或亮度音量逻辑。最终ROI取原始解码纹理，不先对全图降采样再裁剪。缩放连续更新统计域而不重编译shader，合并过时快照；旋转清手工选区、保留源方向含义。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t11.py -v`，随后运行 `python qa/runner.py --task T11 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t11 roi_touch"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 完成设备触控视频与同PTS像素记录；M2首个核心测试包必须真实具备“局部放大＋按局部增强”，不是只包含选框UI。

### T12　掉帧、热状态与增强资源自适应

**阶段：** M3　**依赖：** T03, T09, T10, T11　**验收编号：** F02, F03　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/performance/QualityGovernor.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/performance/PlaybackMetrics.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/performance/ThermalProbe.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/PerformancePolicyScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t12.py`；测试结果：`artifacts/runs/<run_id>/T12/`。

**Interfaces**

输入：内核帧计数、RenderReceipt／GPU计时可用性、可用的系统热状态。

输出：`QualityGovernor.update(sample: PerformanceSample): QualityDecision`；`PlaybackMetrics.snapshot(): MetricsSnapshot`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t12.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_performance_policy(run_case):
    r = run_case("T12/performance_policy")
    assert r["window_seconds"] == 5 and r["bad_windows_to_degrade"] == 2
    assert r["drop_threshold"] == 0.01 and r["min_change_interval_seconds"] >= 10
    assert r["stable_seconds_to_restore"] >= 30 and r["restores_at_most_one_level"]
    assert r["gpu_budget_bytes"] <= r["selected_budget_bytes"] and r["missing_thermal"] is None
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t12.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 实现规格降级顺序：关细节→降空间降噪→流畅→旁路；排除开播5秒、seek后2秒、暂停、缓冲及失焦，连续两个有效5秒窗口掉帧>1%才逐级改变。独立注入时间源用于确定性回归。
- [ ] **步骤4：接入本任务调用方。** GPU计时缺失、热接口缺失都用null／不可用。实际呈现无法可靠测量则F01对应指标标未测，不用UI刷新率充当视频帧率。计数器回绕／重置开始新窗口，不出现负掉帧。增强资源上限RGBA8约32MiB／浮点64MiB，统计、FBO和池也计入；不含解码器和系统总内存。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t12.py -v`，随后运行 `python qa/runner.py --task T12 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t12 performance_policy"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 策略模拟与设备真实负载分开留证；降级不改变视口／播放／音量；锁定曝光保持，稳定恢复只一级且不超过用户请求档。

### T13　微秒级 A／B 时间轴、边界预览与循环

**阶段：** M3　**依赖：** T03, T05, T06　**验收编号：** T01, T02, P05　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/clip/ClipController.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/clip/ClipRangePolicy.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/clip/ClipTimelineView.kt`。
- 新建：`app/src/main/res/layout/panel_lvm_clip.xml`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/ClipTimelineScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t13.py`；测试结果：`artifacts/runs/<run_id>/T13/`。

**Interfaces**

输入：真实duration、PTS及seek回执；触点归属；当前轨道选择。

输出：`ClipController.setRange(range: ClipRange): Result<Unit>`；`onHandle(which: ClipHandle, positionUs: Long, phase: DragPhase): Unit`；`setLoop(enabled: Boolean): Unit`；`request(mode: ExportMode): ExportRequest`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t13.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_clip_timeline(run_case):
    r = run_case("T13/clip_timeline")
    assert r["valid_range"] and r["handles_never_cross"]
    assert r["position_storage_unit"] == "int64_us" and r["long_duration_overflow"] is False
    assert r["loop_follows_range"] and r["vfr_uses_actual_pts"]
    assert r["text_input_supported"] and r["timeline_local_zoom_supported"]
    assert r["vfr_actual_pts_verified"] and r["time_input_overflow_rejected"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t13.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 独立播放进度条与剪辑区间轨道；A/B热区上下错位。合法区间严格0≤A<B≤duration；无时长禁用。长视频支持局部时间轴放大及HH:MM:SS.ffffff输入，不先转浮点秒再保存。 ClipRangePolicy.kt只负责区间验证和边界策略；ClipRange/ClipHandle/ExportMode/ExportRequest的值类型统一声明于Contracts.kt。
- [ ] **步骤4：接入本任务调用方。** 手柄拖动暂停预览并沿用150ms合并；松手记录请求时间与实际帧时间。可在当前点设A/B，循环立即跟随区间；关闭循环清内核边界。导出请求冻结媒体代次、区间与轨道，不使用未来UI状态。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t13.py -v`，随后运行 `python qa/runner.py --task T13 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t13 clip_timeline"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 实际触控拖动、键盘输入、短区间、长视频与VFR均检查；仅此任务完成还不能宣称导出可用。

### T14　FFmpeg 原生原码流剪辑与随机访问边界证明

**阶段：** M3　**依赖：** T02, T13　**验收编号：** T03, T04, T06, T07　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/export/StreamCopyExporter.kt`。
- 新建：`app/src/main/jni/lumaview/export_bridge.cpp`。
- 新建：`app/src/main/jni/lumaview/stream_copy.cpp`。
- 新建：`app/src/main/jni/lumaview/random_access.cpp`。
- 修改：`app/src/main/jni/Android.mk`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/StreamCopyScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 新建：`qa/verify_packets.py`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t14.py`；测试结果：`artifacts/runs/<run_id>/T14/`。

**Interfaces**

输入：ReadLease、ExportRequest（明确选中轨道）以及固定FFmpeg原生库。

输出：`StreamCopyExporter.analyze(input: ReadLease, request: ExportRequest, reply: (Result<CopyPlan>) -> Unit): Long`；`write(input: ReadLease, plan: CopyPlan, temp: File, listener: (ExportEvent) -> Unit): ExportJobId`；`cancel(id: ExportJobId): Unit`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t14.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_stream_copy(run_case):
    r = run_case("T14/stream_copy")
    assert r["copied_video_packets"] > 0 and r["same_container_payload_matches"] == r["copied_video_packets"]
    assert r["full_output_decode_errors"] == 0 and r["added_av_offset_ms"] <= 40
    assert r["unsafe_open_gop_claimed_safe"] is False
    assert r["unretained_tracks_disclosed"] and r["source_hash_unchanged"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t14.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 为导出重新打开独立FD与解复用上下文，不dup后共享文件偏移；验证读权限并用AVIO受控读。对H.264/HEVC显式解析随机访问单元与配置，首版安全边界优先IDR；开放GOP/CRA等无法证明可独立启动时拒绝“安全无损”结论。
- [ ] **步骤4：接入本任务调用方。** CopyPlan回报请求A、实际A′、B、前滚、选中流及不支持项；同容器优先并分别声明容器支持。使用共同时间基准平移PTS/DTS；末尾保留解码依赖，记录实际区间。JPEG封面/附件不混作视频轨。完整解码及负载哈希在测试验证器中运行，不能用重新编码保持码率假冒复制。 写入API显式接收本任务独立ReadLease，CopyPlan不隐藏FD或依赖分析器继续存活；write拥有该lease直到完成或取消，分析阶段仅借用、不会关闭它。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t14.py -v`，随后运行 `python qa/runner.py --task T14 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t14 stream_copy"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** H.264和HEVC同容器逐包核对；跨容器转换另报码流表示转换，不能套用原始字节一致结论。生产导出不要求逐包hash导致UI或导出额外全片阻塞。

**重点限制：** 文件fd seek共享、错误把mpv轨道ID当FFmpeg索引、开放GOP假关键帧，均作为本任务负例；T07中的C/C++链接与本桥接应共用同一FFmpeg构建，避免双份符号。

### T15　Transformer 精确剪辑与所选轨道映射

**阶段：** M3　**依赖：** T02, T03, T13, T14　**验收编号：** T05, T06, T07　**状态：** NOT_STARTED

**Files**

- 修改：`app/build.gradle`。
- 新建：`app/src/main/java/org/lumaview/mobile/export/ExactExporter.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/export/ForceEncodingFactory.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/export/SelectedInputResolver.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/ExactExportScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/fixtures.json`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t15.py`；测试结果：`artifacts/runs/<run_id>/T15/`。

**Interfaces**

输入：ExportRequest、ReadLease、真实轨道映射；T14提供仅在必要时使用的所选流暂存重封装。

输出：`SelectedInputResolver.resolve(input: ReadLease, request: ExportRequest, reply: (Result<PreparedInput>) -> Unit): Long`；`ExactExporter.write(input: PreparedInput, request: ExportRequest, temp: File, listener: (ExportEvent) -> Unit): ExportJobId`；`cancel(id: ExportJobId): Unit`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t15.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_exact_export(run_case):
    r = run_case("T15/exact_export")
    assert r["first_frame_error_frames"] <= 1 and r["last_frame_error_frames"] <= 1
    assert abs(r["added_av_offset_ms"]) <= 40 and r["removed_preroll_visible_frames"] == 0
    assert r["actual_video_codec"] == "video/avc" and r["video_process"] == "ENCODE"
    assert r["mp4_edit_list_trim_enabled"] is False and r["requested_tracks_match_output"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t15.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 所有Media3模块固定1.11.1；用该版本实际存在的setStartPositionUs/setEndPositionUs传递微秒边界。包装EncoderFactory，使videoNeedsEncoding/audioNeedsEncoding返回所需策略并记录实际编码器；关闭编辑列表trim和拼接优化，精确模式首版统一H.264/AAC重编码，无音频则纯视频。[R5–R7]
- [ ] **步骤4：接入本任务调用方。** 单一合适视频／音轨直接输入；多轨且不能验证Transformer选中映射时，先经FFmpeg只暂存所选流并记录统一时间偏移，不让它默认选错轨。该暂存成本在确认页显示，并征求空间／耗时同意；这不是每次播放默认复制全片。使用同一Looper管理Transformer，后台工作不让UI转码；开始前释放／暂停预览的硬解资源，完成才重新获取。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t15.py -v`，随后运行 `python qa/runner.py --task T15 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t15 exact_export"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 用帧号与PTS标签验证首末实际画面，不仅检查容器时长。SDR、无声、两音轨、非零起始PTS、VFR分别通过；HDR／编码能力不足明确拒绝或用户确认后降分辨率，不静默降画质。

### T16　安全输出提交、取消与可恢复失败

**阶段：** M3　**依赖：** T02, T14, T15　**验收编号：** S02, S03, F03　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/export/ExportCoordinator.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/storage/OutputTransaction.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/ui/ExportConfirmationPanel.kt`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/OutputTransactionScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/storage/DocumentAccess.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t16.py`；测试结果：`artifacts/runs/<run_id>/T16/`。

**Interfaces**

输入：CopyPlan／PreparedInput／ExportEvent；用户通过系统选择器创建的新文件；只读源身份。

输出：`OutputTransaction.commitNew(temp: File, ticket: NewDocumentTicket, source: DocumentIdentity, reply: (Result<CommitReceipt>) -> Unit): Long`；`ExportCoordinator.cancel(jobId: ExportJobId): Unit`。 `DocumentAccess.createNewDocument(jobId: ExportJobId, suggestedName: String, mime: String, reply: (Result<NewDocumentTicket>) -> Unit): Long`；`ExportCoordinator.start(request: ExportRequest): ExportJobId`；`observe(jobId: ExportJobId, listener: (ExportEvent) -> Unit): AutoCloseable`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t16.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_output_transaction(run_case):
    r = run_case("T16/output_transaction")
    assert r["active_export_jobs_max"] == 1
    assert r["same_document_via_alias_write_attempts"] == 0 and r["source_hash_unchanged"]
    assert r["diskfull_retry_succeeds"] and r["cancel_releases_resources"]
    assert r["residual_files"] == r["disclosed_residual_files"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t16.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 输出状态固定分析→确认→私有暂存→收尾校验→外部提交→完成；外部提交完成前不显示100%已完成。只允许本任务创建的新文档凭据，比较authority/documentId与可取身份；身份不清拒绝覆盖式写入，URI字符串不同不是安全证明。
- [ ] **步骤4：接入本任务调用方。** 取消既覆盖转码也覆盖外部复制；只删除本任务创建的暂存及半成品，失败给出残留URI。断电／强停不能执行取消回调，重启扫描私有任务日志并展示中断状态。首测试版退后台安全取消，选择器／本应用确认页不视为用户离开；不承诺后台不中断。所有出口恢复可再导出的状态。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t16.py -v`，随后运行 `python qa/runner.py --task T16 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t16 output_transaction"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 磁盘不足、撤权、外置存储断开、提供方新URI别名、重复输出、写失败后重试都不破坏源文件；触控选择的区间通过两条导出路径真正保存。

### T17　无控件截图、诊断信息与本地隐私检查

**阶段：** M4　**依赖：** T02, T03, T06, T07, T10, T11, T16　**验收编号：** S04, C02　**状态：** NOT_STARTED

**Files**

- 新建：`app/src/main/java/org/lumaview/mobile/capture/ScreenshotController.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/diagnostics/DiagnosticExporter.kt`。
- 新建：`app/src/main/java/org/lumaview/mobile/security/IntentPolicy.kt`。
- 修改：`app/src/main/AndroidManifest.xml`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/PrivacyCaptureScenario.kt`。
- 修改：`app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t17.py`；测试结果：`artifacts/runs/<run_id>/T17/`。

**Interfaces**

输入：当前帧 RenderReceipt、选区、字幕状态；任务诊断和DocumentAccess。

输出：`ScreenshotController.capture(includeSubtitles: Boolean, reply: (Result<CaptureResult>) -> Unit): Long`；`DiagnosticExporter.writeRedacted(destination: NewDocumentTicket): Long`；`IntentPolicy.accept(intent: Intent): Result<Uri>`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t17.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_privacy_capture(run_case):
    r = run_case("T17/privacy_capture")
    assert r["screenshot_contains_controls"] is False and r["screenshot_pts_matches_receipt"]
    assert r["automatic_network_requests"] == 0 and r["external_script_executions"] == 0
    assert r["unrequested_sensitive_permissions"] == [] and r["external_command_injection_rejected"]
    assert r["diagnostic_contains_private_uri_or_unique_device_id"] is False
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t17.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 显式截图从视频渲染层获取当前增强／ROI后的像素，默认不含控件；字幕开关和保存内容在提示中说明。仅显式截图允许整帧回读，工作线程编码PNG并经新文件事务写出。
- [ ] **步骤4：接入本任务调用方。** 诊断默认脱敏文件名、URI、路径与序列号，保留API、ABI、GPU、输入色彩和生效状态。去除INTERNET及摄像头、麦克风、所有文件访问、无障碍、悬浮权限；控制Intent协议与长度，只接受授权媒体URI，不接受额外mpv命令。禁止目录auto-load脚本和远程协议；系统提供方自有行为与应用自动请求分开记录。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t17.py -v`，随后运行 `python qa/runner.py --task T17 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t17 privacy_capture"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 无网络／无Google登录核心流程可执行；权限静态检查与动态网络观察分别有记录，不把截图背光变化作为像素增强证据。

### T18　整机流程回归与可复现证据归档

**阶段：** M4　**依赖：** T01, T02, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T13, T14, T15, T16, T17　**验收编号：** C01, P01, P02, P03, P04, P05, R01, R02, R03, R04, R05, R06, E01, E02, E03, E04, E05, E06, E07, E08, E09, T01, T02, T03, T04, T05, T06, T07, S01, S02, S03, S04, F02, F03　**状态：** NOT_STARTED

**Files**

- 修改：`qa/generate_fixtures.py`。
- 修改：`qa/analyze_pixels.py`。
- 修改：`qa/verify_packets.py`。
- 新建：`qa/run_android_suite.py`。
- 新建：`qa/report.py`。
- 新建：`app/src/androidTest/java/org/lumaview/mobile/scenarios/EndToEndScenario.kt`。
- 修改：`qa/fixtures.json`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t18.py`；测试结果：`artifacts/runs/<run_id>/T18/`。

**Interfaces**

输入：T01 测试驱动协议及T02–T17真实功能探针；本计划38项清单。

输出：`python qa/run_android_suite.py --serial <device> --abi arm64-v8a --out <run_dir>`；`python qa/report.py --run <run_dir>`，输出 per-case 状态，不从日志出现PASS字符串推断整套通过。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t18.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_integration(run_case):
    r = run_case("T18/integration")
    assert r["required_criteria_registered"] == 38
    assert r["pass_without_evidence"] == [] and r["stale_build_evidence"] == []
    assert r["touch_sequences_replayed"] > 0 and r["golden_frame_only_ui_mock"] is False
    assert r["device_environment_recorded"] and r["retained_failure_records"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t18.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 复用前置任务已生成的固定种子的灰阶／彩色块／噪声／帧号／声画脉冲／固定ROI与变化背景素材，记录命令及hash；增加VFR、非零PTS、开放GOP、异常容器和特殊SAR组。真实触控必须从系统注入序列进入View，不直接调用按钮回调当成触控测试。
- [ ] **步骤4：接入本任务调用方。** 像素分析按同PTS同视图、同色彩空间对齐；掉帧和时域统计读真实回执。每次记录APK／补丁／素材hash、设备环境、raw数据和计算脚本；错误和不利结果保留。无设备用BLOCKED，不通过跳过设备测试获得全绿报告。 原始素材生成、像素分析与包验证脚本在T01/T08/T09/T14已分别落地，本任务补齐端到端调度和缺失覆盖，不引入前向测试依赖。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t18.py -v`，随后运行 `python qa/runner.py --task T18 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t18 integration"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 形成“已验证功能／失败／未跑／受阻”四分表。模拟器ARM64、x86_64和物理ARM64结果分开；本任务不替代Mate 20 X长测及真实微光评估。

### T19　Mate 20 X 实物素材与持续性能验收

**阶段：** M4　**依赖：** T18　**验收编号：** C02, P03, E10, F01, F02, F03　**状态：** NOT_STARTED

**Files**

- 新建：`qa/device_profiles/mate20x-harmony4.json`。
- 新建：`qa/run_soak.py`。
- 新建：`qa/real_lowlight_manifest.json`。
- 新建：`docs/implementation/target-device-runbook.md`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t19.py`；测试结果：`artifacts/runs/<run_id>/T19/`。

**Interfaces**

输入：目标手机实际诊断；可复现APK；获授权的四类真实微光视频；T18基准结果。

输出：`python qa/run_soak.py --serial <device> --profile qa/device_profiles/mate20x-harmony4.json --mode original|auto|roi --minutes 20 --out <run_dir>`。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t19.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_target_device(run_case):
    r = run_case("T19/target_device")
    assert r["is_physical_arm64"] and r["google_login_required"] is False
    assert r["modes"] == ["original", "auto", "roi"] and r["minutes_per_mode"] >= 20
    assert r["effective_drop_ratio"] <= 0.01 and r["max_nonbuffer_stall_s"] <= 0.5 and r["anr_count"] == 0
    assert r["lowlight_categories_reviewed"] == 4 and r["tail_interval_reported"]
    assert r["requested_mode_downgrade_disclosed"] and r["audio_listening_recorded"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t19.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 按设备实际API／GPU建档，不以HarmonyOS版本猜API。1080p30 SDR在原画、自动、选区增强各连续20分钟；固定背光与播放音量，记录环境温度、充电状态、可取热状态、有效窗口与最后5分钟。
- [ ] **步骤4：接入本任务调用方。** 四类真实素材为室内暗光、路灯高反差、人物运动、细文字；固定相同PTS对照并人工观察噪声、细节、光晕、颜色。没有配对真值不得算真实画面PSNR。GPU计时可靠时单报增强P50/P95，P95≤8ms为调校目标，否则null。1080p60和4K另列能力实验，不改变首验口径。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t19.py -v`，随后运行 `python qa/runner.py --task T19 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t19 target_device"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 目标机不在可用环境时本任务BLOCKED并提供用户本地执行说明，绝不声称远程访问了用户手机。听音、真实素材和长测分别签认；未达标明确不通过，不靠隐藏自动降级掩盖请求档未达标。

### T20　发布门禁、签名、源码一致性与完整测试包

**阶段：** M5　**依赖：** T18, T19　**验收编号：** C01, C03, S04　**状态：** NOT_STARTED

**Files**

- 新建：`buildscripts/lvm-package.sh`。
- 新建：`qa/release_gate.py`。
- 新建：`docs/implementation/mobile-user-guide.md`。
- 新建：`licenses/THIRD_PARTY_NOTICES.md`。
- 新建：`release/release-manifest.json`。
- 修改：`qa/cases.json`。
- 测试断言：`qa/cases/test_t20.py`；测试结果：`artifacts/runs/<run_id>/T20/`。

**Interfaces**

输入：全部实现提交、依赖源码和patch、逐项真实验收；签名证书公共信息。

输出：`buildscripts/lvm-package.sh --channel test|validated --run <run_dir>`；输出APK＋对应源码＋native补丁＋说明＋逐项结果＋SHA256。

- [ ] **步骤1：编写失败用例。** 在 `qa/cases/test_t20.py` 写入以下核心断言，并在本任务的 Scenario／unit test 文件中采集对应实际观测值。

```python
def test_release_gate(run_case):
    r = run_case("T20/release_gate")
    assert r["validated_with_blocked_or_failed_required_case"] is False
    assert r["native_sources_and_patches_rebuildable"] and r["package_checksums_match"]
    assert r["private_keys_in_bundle"] == 0 and r["user_media_in_bundle"] == 0
    assert r["elf_alignment_checked"] and r["apk_zip_alignment_checked"]
```

- [ ] **步骤2：确认红灯。** 执行 `python -m pytest qa/cases/test_t20.py -v`，保存失败日志；失败必须来自尚未满足的上述行为，不是错拼路径或没有设备。
- [ ] **步骤3：实现本任务核心单元。** 再次干净构建并做ELF、ZIP对齐、ABI、安装包签名校验；检测原生资源页大小假设。默认test渠道允许明确列出BLOCKED的受限测试包；validated渠道要求38项在所声明设备／能力范围内全部满足，缺测不得放行。
- [ ] **步骤4：接入本任务调用方。** 首测试版versionName `0.1.0-test`，独立递增versionCode；测试签名证书摘要公开、私钥受保护且不进源码。保留上游许可、全部新增／修改源文件、原生patch和依赖锁，不交只有壳的“完整源码”。只把真实产物的字节hash写清单，更新说明不得从本计划复制通过结论。
- [ ] **步骤5：验证绿灯和相邻回归。** 再运行 `python -m pytest qa/cases/test_t20.py -v`，随后运行 `python qa/runner.py --task T20 --include-dependencies --record`；记录实际命令、退出码、环境及观测数据。
- [ ] **步骤6：提交独立变更。** 先核对 `git diff --stat` 只含本任务与必要关联文件，用明确文件路径暂存，再执行 `git commit -m "feat(mobile): t20 release_gate"`；提交号写入任务记录。不要全目录提交测试私钥／个人视频。

**本任务通过条件：** 交付APK、源码、构建方式、中文说明和逐项验证。T19受阻时仅可创建“未完成目标真机验收”的测试包，不能叫最终兼容认证版；这是一条显式受限发布路径，不把T19标完成。

## 5. 任务与38项验收的对应关系

完整映射见 `docs/implementation/acceptance-matrix.md` 和 `audit/acceptance-matrix.json`。任务编号也采用T前缀；为避免与原规格的T01–T07剪辑验收编号混淆，JSON以 `tasks`／`criteria` 集合以及 `primary_task`／`supporting_tasks` 字段区分上下文，表格始终分别显示“实施任务”和“规格验收”。

验收不能仅按模块自测完成来勾选：实现负责人完成任务测试后，T18做端到端复核，T19做目标机／真实素材复核，T20检查证据能否支撑所声明的分发渠道。C03必须分别列ELF静态、APK对齐、4KB启动、16KB启动；P03属性变化与听音分开；E08浮点与后备编码分开。

## 6. 首次执行边界与后续交接

批准实施计划和执行方式后，先只执行T01–T03：建立独立工程、固定工具链、得到基础APK并验证授权打开和Surface生命周期；完成后报告版本／APK真实路径／hash及受阻项，再按依赖继续。不能只下载上游APK重签就称本项目已开发。

本会话推荐**串行原生实施**：每项先测试再实现并留证，遇到阻断先处理，不假称当前有独立子智能体。若用户明确选择在具备子智能体的Codex环境执行，可按同一任务表分配实施和独立审查；先确认工具能力再启动，不能把自行复查标注为独立审查。

执行者每次接续至少阅读规格、本计划、接口契约、上次运行记录四项。任务已通过也不能复用旧的验收截图证明新二进制；记录新旧commit和重新覆盖范围。不在没有工具执行的情况下声称持续后台构建。

## 7. 计划自检与当前状态

本轮检查对象是**文档**：规格hash一致、20个任务唯一、依赖顺序合法、38个验收编号全部有主要责任任务、状态初始化为未运行、契约路径存在、示例代码格式及链接完整。检查结果见 `audit/plan-validation.json`。

这些结果不代表任何手机测试通过。本轮没有安装Android SDK／NDK，没有创建应用源码工程，没有编译／签名APK，没有接入目标手机，也没有向远程仓库写入内容。源码级插入点与完整依赖归档校验属于T01／T07的实作前检查，遇到矛盾应记录而非静默改规格。

## 8. 参考依据

[R1] 固定mpv-android发布及版本清单：`https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17`。

[R2] 固定上游 `app/build.gradle`、`build.gradle`、`buildscripts/include/depinfo.sh`、`buildscripts/buildall.sh`、`buildscripts/README.md`；对应完整提交均为 `fdf74f6830c47dbaa8a22ac79726e8303f1db5af`。公开核对记录见 `audit/source-review.json`。原生构建与Gradle应用构建是不同步骤；不声称桌面Windows能按此原生流程直接编译全部库。

[R3] `https://raw.githubusercontent.com/mpv-android/mpv-android/2026-09-17/app/src/main/java/is/xyz/mpv/BaseMPVView.kt`；只说明上游Surface回调与释放竞态提醒，不证明本项目已修复。

[R4] mpv手册：`https://mpv.io/manual/stable/`；GPU后端、`video-crop`、可调shader参数的适用范围。

[R5] Media3 1.11.1 `Codec.EncoderFactory`：`https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/transformer/src/main/java/androidx/media3/transformer/Codec.java`；编码需求接口与实际编码器名称。

[R6] Media3 1.11.1 `MediaItem.ClippingConfiguration.Builder`：`https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/common/src/main/java/androidx/media3/common/MediaItem.java`；实际存在微秒级边界接口。

[R7] Android Transformer：`https://developer.android.com/media/media3/transformer/getting-started`；`https://developer.android.com/media/media3/transformer/transformations`。默认可能重封装；编辑列表快速剪辑可能保留未显示的前段，不能当成已移除。

[R8] Android共享文档与持久授权：`https://developer.android.com/training/data-storage/shared/documents-files`。

[R9] Android 16KB页检查：`https://developer.android.com/guide/practices/page-sizes`。ELF／APK对齐与运行验证分开。

[R10] Android PowerManager：`https://developer.android.com/reference/android/os/PowerManager`。热状态读取需版本与能力检查；读取不到用不可用，不用0假正常。

外部资料核对日期2026-10-03，引用只用于接口与边界。全部阈值以用户确认的规格为准；本计划新增的是分解、实现路径和证据形式，不提升未实测的能力承诺。
