# 接口与测试契约

版本0.1；服务于已确认规格，不是当前已实现的API。下列项目自定义接口均需由对应任务创建，只有Android／libmpv／FFmpeg／Media3原有接口另引上游。除协议片段外，本包不包含可运行的手机产品源文件。

## 1. 基本约定与类型所有权

新增Kotlin包根为 `org.lumaview.mobile`。所有时间保存为有符号64位整数微秒，未知值使用null；不要用−1秒/0秒代替未知。原生ABI采用固定宽度整数，转换有溢出检查。屏幕触控输入以像素进入，dp仅用于手柄／触控尺寸下限。

每个回调必须携带媒体代次；与画面相关的请求还携带Surface与视口代次。所有可关闭对象按所有权释放，不能把输入FD交给两个独立seek使用者而仅dup一个描述符。UI订阅发布不可变快照；厂商错误、取消和不支持能力用显式Result，不直接异常穿过JNI。

| 类型及归属任务 | 字段／精确定义 |
|---|---|
| `DocumentIdentity`，T02 `storage/DocumentModels.kt` | `authority: String?`、`documentId: String?`、`sessionId: String`、`displayName: String`；身份比较只能得SAME／DIFFERENT／UNKNOWN，UNKNOWN不允许覆盖写 |
| `ReadLease`，T02 | `identity: DocumentIdentity`、`fd: Int`、`sizeBytes: Long?`、`seekable: Boolean`、`cached: Boolean`、`close(): Unit`；只读所有权，不对外返回可写源文件路径 |
| `DocumentEntry`，T02 `storage/DocumentModels.kt` | `uri: Uri`、`identity: DocumentIdentity`、`mime: String?`、`isDirectory: Boolean`、`sizeBytes: Long?` |
| `PointD`／`SourceRect`，T03 `model/Contracts.kt` | 点为`x,y: Double`；矩形为`left,top,right,bottom: Double`，像素边界半开区间，坐标相对于编码源图，不含播放器黑边 |
| `VideoGeometry`，T03 `model/Contracts.kt` | `codedWidth,codedHeight: Int`、`intrinsicCrop: SourceRect`、`sarNum,sarDen: Int`、`fileRotation: Int`、`color: ColorInfo`；同文件使用T03已声明的SourceRect，T04只实现坐标计算 |
| `ColorInfo`，T03 | `matrix,transfer,primaries: String?`、`range: FULL/LIMITED/UNKNOWN`、`bitDepth: Int?`、`sourceHdr: Boolean?`；不从已映射SDR反推源SDR |
| `TrackKind`／`TrackInfo`，T03 | VIDEO/AUDIO/SUBTITLE；`mpvId: Long`、`demuxId: Long?`、`ffmpegIndex: Int?`、`codec: String?`、`language: String?`、`selected: Boolean`；三种ID不等同，缺映射时导出前验证 |
| `PlayerState`，T03 | `mediaGeneration: Long`、`surfaceGeneration: Long`、`positionUs,durationUs,presentedPtsUs: Long?`、`paused: Boolean`、`speed: Double`、`seekable: Boolean`、`videoGeometry: VideoGeometry?`、`tracks: List<TrackInfo>`、`decoder: String?`、`lastAppliedRequestId: Long?` |
| `SeekMode`，T03 | PREVIEW／FINAL；PREVIEW可关键帧快速定位，FINAL要求内核精确seek并回报实际呈现PTS，不能只以command reply结束 |
| `ViewportRequest`，T04 | `screenWidthPx,screenHeightPx: Int`、`userRotation: Int`、`roi: SourceRect?`、`zoom: Double`、`panX,panY: Double`、`density: Float`；zoom夹紧1–8 |
| `ViewportSnapshot`，T04 | `mediaGeneration,surfaceGeneration,viewRevision: Long`、`sourceToScreen: DoubleArray(9)`、`screenToSource: DoubleArray(9)`、`visibleSourceRect: SourceRect`、`statsSourceRect: SourceRect`、`contextFraction,contextWeight: Double`；矩阵提交后复制数组，不暴露可改别名 |
| `HitTarget`，T05 | PANEL、SEEK、CLIP_A、CLIP_B、ROI_CORNER_0..3、ROI_BODY、VIDEO、UNLOCK、NONE；先命中已抓取对象再查其他目标 |
| `GestureAction`，T05 | sealed类型：ArmSelection、Begin/Update/EndSelection(PointD)、ResizeCorner(Int,PointD)、MoveRoi(PointD)、Pinch(scale,focus)、Pan(delta)、SeekFraction(fraction,phase)、SetVolume(value)、SetBrightness(value)、Cancel(reason)、Unlock；数值均有限 |
| `CancelReason`／`DragPhase`，T05 | USER／POINTER_CANCEL／FOCUS_LOST／SURFACE_CHANGED／MEDIA_CHANGED；START／MOVE／END／CANCEL |
| `EnhanceMode`／`EnhanceParams`，T07 `model/Contracts.kt`（T10实现面板） | ORIGINAL/AUTO/LOW/ULTRALOW/FAST；`mode`、`manualEv: Float` [−1,1]、`shadows:Int` [0,100]、`contrast:Int` [−25,25]、`saturation:Int` [0,150]、`denoise:Int` [0,100]、`detail:Int` [0,30]、`bypass:Boolean`、`lockRequest:LockRequest`；原画忽略手调，其他总曝光限制0–4EV |
| `LockRequest`，T07（T10实现锁定控制） | NONE／LOCK_LAST_APPLIED／UNLOCK；锁定当前曝光只由GPU执行点确认，UI展示LOCK_PENDING直到收到回执 |
| `FrameStamp`，T07 | `mediaGeneration,surfaceGeneration,viewRevision,requestId: Long`，各从单一会话递增；相同代次小于最后受理requestId者拒绝 |
| `FrameSnapshot`，T07 | `stamp: FrameStamp`、`viewport: ViewportSnapshot`、`params: EnhanceParams`、`historyReset: ResetReason`；T07完整声明参数布局，但先仅启用原画恒等行为；T08/T09/T10依次实现统计、像素处理与控制器 |
| `ResetReason`，T07 | NONE／NEW_MEDIA／SEEK／VIEW_COMMIT／ROTATION／SURFACE／COLOR_PATH／SCENE_CUT；高频拖动同一绘制序列合并，仅最新有效revision进入渲染 |
| `StatsState`，T07声明、T08填充 | `validWeight,log2Mean,brightFraction,exposureEv: Float?`、`historyEpoch: Long`；无样本不发送NaN，用有效位＋null |
| `RenderReceipt`，T07 | `stamp:FrameStamp`、`presentedPtsUs:Long?`、`actualSourceRect:SourceRect`、`effectiveMode:EnhanceMode`、`bypassReason:String?`、`stats:StatsState?`、`format:String`、`resourceBytes:Long`、`gpuTimeNs:Long?`、`lockApplied:Boolean`；数值观测T08/12才增加有效值，不能提前填虚构的0 |
| `SubmitStatus`，T07 | ACCEPTED／STALE／UNSUPPORTED／INVALID／CLOSED；ACCEPTED只表示排队成功，不代表效果已显示 |
| `EnhancementState`，T10 | `requested:EnhanceParams`、`lastReceipt:RenderReceipt?`、`pending:Boolean`、`reason:String?`；无回执不显示“已生效” |
| `PerformanceSample`，T12 | `monotonicNs:Long`、`presentedCount,droppedCount:Long?`、`paused,buffering,focused:Boolean`、`lastStartNs,lastSeekNs:Long?`、`thermalStatus:Int?`、`gpuTimeNs:Long?`；无可靠counter则不可计算有效掉帧率 |
| `QualityDecision`／`MetricsSnapshot`，T12 | 决策包括`level:Int`、`effectiveMode`、`reason:String?`、`nextChangeEarliestNs:Long`；统计包括有效区间、丢帧分子／分母、原始计数来源、排除区间和可用性 |
| `ClipRange`／`ClipHandle`，T13 | `startUs,endUs:Long`，使用[A,B)，合法0≤A<B≤duration；手柄START/END |
| `ExportMode`／`ExportRequest`，T13 | COPY／EXACT；请求包括`mediaGeneration:Long`、`document:DocumentIdentity`、`range:ClipRange`、`videoTrack:TrackInfo`、`audioTracks:List<TrackInfo>`、`subtitleTracks:List<TrackInfo>`、`mode`、`container:String`；EXACT最多一个音轨，省略项披露 |
| `TrackDisposition`／`CopyPlan`，T14 | 每轨selectedId、sourceIndex、COPY/ENCODE/DROP/UNSUPPORTED及reason；CopyPlan包含请求、已验证A′与尾部边界、timelineShiftUs、前滚量、可独立解码判据、输出容器和处理清单 |
| `ExportJobId`／`ExportEvent`，T14 | ID为String UUID；事件为ANALYZING/WAITING_CONFIRMATION/WRITING/FINALIZING/COMMITTING/COMPLETED/CANCELLED/FAILED，含进度可用性、per-track实际方式、源／目标时间映射，不能用0冒充未知进度 |
| `PreparedInput`，T15 | `uri:Uri`、`lease:AutoCloseable?`、`sourceToPreparedOffsetUs:Long`、`trackMapping:List<TrackDisposition>`、`isTemporary:Boolean`；目标播放时间=源播放时间＋偏移；验证器逆变换后对照原始PTS |
| `NewDocumentTicket`，T16 | `jobId`、`uri`、`providerIdentity:DocumentIdentity?`、`createdByThisJob:Boolean`、`creationToken:String`；只能由DocumentAccess创建流程内部构造，不接受外部任意URI冒充 |
| `CommitReceipt`，T16 | `destinationUri:Uri?`、`bytesCopied:Long`、`completed:Boolean`、`residualUris:List<Uri>`、`reason:String?` |
| `CaptureResult`，T17 | `tempFile:File`、`stamp:FrameStamp`、`presentedPtsUs:Long`、`crop:SourceRect`、`enhanced:Boolean`、`subtitlesIncluded:Boolean`、`controlsIncluded:Boolean=false`；保存成功另由提交回执确认 |

共享值类型使用固定位置，不随任务搬家：存储域的DocumentIdentity、DocumentEntry在T02的storage/DocumentModels.kt，ReadLease在storage/ReadLease.kt；手势域值类型在T05的gesture/GestureState.kt。其余跨域几何、播放、渲染、剪辑、导出与截图快照统一置于 `app/src/main/java/org/lumaview/mobile/model/Contracts.kt`。T03创建播放及SourceRect等首批定义；T04扩展视口；T07完整声明增强快照/统计布局；T10、T12、T13–T17在首次消费时向后兼容扩展各自的状态与值类型。字段的功能责任按上表所列任务，文件修改项已同步到每个任务。

PlayerStateReducer.kt、ViewportPolicy.kt、EnhanceDefaults.kt、ClipRangePolicy.kt分别保存状态归约、坐标策略、默认参数校验、区间策略，不再次定义同名值类型。T07不得先引用尚不存在的EnhanceParams或StatsState，再等T10补类型。

## 2. 原生扩展ABI与渲染边界

以下为**项目自定义**拟新增API，需在native补丁中实现并编译进同一libmpv，不是官方已有API；严禁仅写JNI声明就返回成功。

```c
/* libmpv/lvm_ext.h -- v1 fixed-layout schema, implemented by T07 */
int mpv_lvm_submit(mpv_handle *ctx, const struct lvm_frame_v1 *snapshot);
int mpv_lvm_poll_receipt(mpv_handle *ctx, struct lvm_receipt_v1 *out);

/* private GPU functions: T08 and T09, renderer thread only */
int lvm_stats_update(struct lvm_stage *stage, const struct lvm_input_frame *frame,
                     const struct lvm_rect *visible_rect, double dt_seconds);
int lvm_enhance_render(struct lvm_stage *stage, const struct lvm_input_frame *frame,
                       const struct lvm_stats_state *stats,
                       const struct lvm_params *params);
```

`lvm_frame_v1` 字段顺序锁定为：`uint32_t size, version`；四个`uint64_t`代次／requestId；`double source_to_screen[9], visible_rect[4]`；`float context_fraction, context_weight`；`uint32_t mode, flags, reset_reason`；六个`float`手调参数；`uint32_t reserved[8]`。手动参数依次manualEv、shadows、contrast、saturation、denoise、detail，其整数UI值在桥接端转换为float，范围与表一致。

`lvm_receipt_v1`包含size/version、相同stamp、`int64_t pts_us`＋valid位、实际source_rect[4]、effective_mode、reason_code、stats_valid_mask、log2_mean/exposure_ev/bright_fraction/valid_weight、resource_bytes、gpu_time_ns＋valid位、lock_applied。`lvm_rect`为四个double；`lvm_params`为模式、六个参数与锁状态；`lvm_stats_state`为四个float、有效位与historyEpoch；`lvm_stage`、`lvm_input_frame`是原生私有类型，由T07的stage.h声明，绝不暴露GL纹理整数ID给Kotlin。

JNI逐字段构造C结构，**不把Kotlin ByteBuffer按C结构强转**；使用sizeof／offsetof静态测试保留实际ABI布局。版本不符或size不足返回INVALID，reserved要求为0。Snapshot复制进入长度1邮箱，JNI函数返回前不等待GPU执行。每次render读取最新快照，将显示矩形与统计矩形在同一边界更新。

坐标路径选择：保留上游显示矩形和旋转／缩放语义，在受控GPU补丁中取得最终可见源矩形并统一给增强；区域取样来自尚未被全图低分辨率缩小的源纹理。若实现中生成显式ROI工作纹理，必须在同一补丁内更新几何映射并取消重复裁剪，不能让UI另一次video-crop裁剪它。任何交叉后端实现都需同样通过R05／E02，不可静默切换后端绕过问题。

T07必须在固定源码上落盘 `audit/native-hook-anchors.json`：文件、函数、插入前后关键状态与补丁hash。计划核对未取得完整GPU实现，不能虚构准确行号；落地时若接口语义不符，停止并做具体差异审查，不修改用户目标。

## 3. 线程与释放约定

UI线程只处理View、订阅与轻量输入；Player命令线程独占会话；mpv渲染线程拥有GL资源；Document I/O线程负责文件；Export线程负责原码流；Transformer只由一个专属Looper访问，其内部转码线程由库管理。主线程不得等待它们的阻塞完成。

Surface失效首先阻止新提交并使代次失效；已获取的native window引用到渲染退出／失败处理回执后才释放。资源释放的合法顺序要在测试中记录，不用“设置vo=null后sleep”替代屏障。若驱动阻塞，不能继续释放可能被访问的指针；隔离会话、显示错误、收集本地诊断并走受控关闭。

播放与导出各自打开只读输入。`dup(fd)`不构成独立seek位置保证；可寻址普通文件可用独立重新打开或pread型自定义AVIO保持独立position，提供方返回共享流时经同意使用暂存。此差异在S01／F03内测。

T19的物理设备连接不是已授权或已存在的远程控制能力；执行时须由可用adb连接或用户本地运行产生结果。提供脚本不等于脚本已在该机运行。

## 4. 测试入口契约

T01新增 `qa/runner.py`／`qa/conftest.py`／`app/src/androidTest/java/org/lumaview/mobile/ScenarioRunner.kt`，统一测试函数：

```python
run_case(case_id: str, options: dict | None = None) -> dict
```

`case_id`必须在 `qa/cases.json` 显式登记；每项绑定任务ID、Android Scenario类／主机测试入口、超时、素材ID和原始结果文件名。Android Scenario以项目自定义的`org.lumaview.mobile.ScenarioRunner` instrumentation调度，用实际触控事件、JNI观测及读回帧输出JSON；主机测试调用同一算法的参考／已编译计算单元。不能以Python重写一遍矩阵而未对照手机实现就通过R05。

统一CLI：`python qa/runner.py --task Txx --include-dependencies --record [--serial SERIAL]`。serial未给时只允许恰好一台已授权测试设备，否则报BLOCKED。pytest fixture遇到缺设备／工具／文件不应静默skip后变成全绿；写BLOCKED记录并使发布门禁非通过。

标准原始结果包括：schemaVersion、taskId、criterionIds、caseId、runId、apkSha256、sourceCommit、fixtureSha256、deviceApi、abi、physicalOrEmulator、graphicsBackend、observed、evidencePaths、startedAt、finishedAt、status、reason。`observed`字段名由主计划断言确定；结果中的boolean必须来自真实事件或可复算原始数据，而非Scenario写常量。

证据路径只能指向本次run目录。PASS必须有观测、阈值、计算方式、环境和可读取证据；NOT_RUN与BLOCKED没有测量值时应为null，不制造0或空统计。文件不存在、JSON损坏、超时、进程崩溃、数值非有限都不能返回PASS。

T01创建 `qa/tests/test_runner_protocol.py`，其runner自身要用“缺证据却写PASS”“不同APK混用”“错误目录”“命令超时”“设备不唯一”“缺指标”负例验证；它属于测试基础，不是产品通过项。每个任务的Scenario先采集未实现情况下的错误／真实失败，再实现对应行为，不把测试probe当成产品实现。

## 5. 门禁状态与受限测试包

任务状态可为NOT_STARTED／IN_PROGRESS／CODE_READY／VERIFIED／BLOCKED。CODE_READY表示代码可构建但某设备验证未完成，不等于验收PASS。上游计算接口已验证后可以继续不依赖缺失设备的工作，但不能将BLOCKED任务勾为完成。全量产品报告始终保留规格的38个编号。

T20执行前T19须已尝试并留下结果；其依赖可为VERIFIED或有明确原因的BLOCKED，未开始不视作已满足依赖。`test`渠道可以在T19受阻时分发有完整警示的测试APK，写明哪些测试完成、哪些没跑；`validated`渠道在声明支持的设备／能力范围内要求38项已满足。C03可在不同的4KB/16KB环境分别验证，不要求Mate20X自身切换页大小；P03听音、E10、F01则不能借其他模拟环境替代目标验证。

本轮无执行代码生成、无测试签名创建、无APK构建，接口文档不得另称“源码实现”。
