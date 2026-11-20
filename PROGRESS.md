# PROGRESS

## 项目：第二十届全国大学生软件创新大赛 — 参赛方案「知隙 MindGap」

### 已确认的方案基线

- 赛道：赛题 1（端云协同），原生 Android 应用（非 Web），团队无硬件 → 已排除赛题 4，谨慎赛题 2。
- 产品：防呆笔记本。端侧发现用户"隐性卡壳"（自己没意识到的认知盲区），主动出"反事实找茬题"并给出原文证据。
- 技术栈：Kotlin + Jetpack Compose（Clean Architecture + MVI）、Sherpa-ONNX 离线 ASR、ML Kit OCR、Room/SQLite、云端 FastAPI + 大模型、PostgreSQL。
- 算法：时空对齐索引器 / 隐性卡壳状态机 / 用户自适应阈值校准 / 三步反事实 Prompt Chaining。
- 预算：除报名费外可压到 0 元（多模型故障转移 + 免费额度）。

### 实施里程碑（忽略人数与工期，按依赖顺序）

1. **M1 采集通道**：无障碍服务取文本 + MediaProjection 1fps 截帧 + AudioRecord 16k，前台服务与常驻"采集中"提示，一键暂停。先用假数据跑通 UI。
2. **M2 端侧感知**：Sherpa-ONNX（int8）+ VAD 分段；ML Kit 中文 OCR；帧去重（文本 diff / 感知哈希）。模型体积大，走首次启动按需下载。
3. **M3 时空对齐**：音频段 / 屏幕帧 / 操作事件按 500ms 桶聚合为注意力时间轴；验收标准是"能回放看哪、说什么、点什么"。
4. **M4 卡壳判定（核心）**：时间异常（停留超 P90）、行为异常（回看 / 切窗 / 搜索 / 删除重写）、语义异常（术语未解释即跳过）；规则 + 可解释特征起步，不做训练；前 5 分钟静默校准基线。
5. **M5 反事实找茬**：①压缩"用户当前理解状态" ②生成 3 选项陷阱题 ③逐选项回指时间轴证据，证据不成立则重生成；断网降级为本地模板/小模型。
6. **M6 共驾界面**：采集会话 / 时间轴回放 / 找茬挑战 / 成长档案 四页；数据本地优先、显式上传授权、一键删除。
7. **验证与交付**：真人试用 5–8 人（同一篇文档），统计卡壳检出精确率、题目命中率、完成率；配 demo 视频与文档。

### 数据契约（2026-11-17 已落地）

- Schema：`schema/timeline.schema.json`（JSON Schema draft 2020-12）。顶层 `TimelineSession` = schemaVersion + sessionId + app/device/capture/document + frames + stuckEvents + quizItems。
- 帧结构 `TimelineFrame`：`frameId / t(ms, 严格递增) / capturedAt / screen / speech / action / features`。
  - `screen`：docId、paragraphId、text、textHash、isDuplicate、progress、source(accessibility|ocr)
  - `speech`：segmentId、text、isFinal、startMs/endMs、asrConfidence
  - `action.type`：none / scroll / click / switch_app / search / erase / type_text / idle
  - `features`：dwellMs、revisitCount、isStalled、attentionScore（判定引擎只读这些，不回看原始数据）
- `StuckEvent` 必须携带 `evidenceFrameIds`（至少 1 帧）——无证据不得进入找茬环节；`kind` 三值：time/behavior/semantic_anomaly。
- `QuizItem` 三个选项、每题恰好 1 个 `verdict=correct`；`verdict` 与 rationale 仅本地演示，生产环境必须由服务端持有答案键。
- 假数据：`mock/timeline_raft_session.json`（Raft 文档 174 秒会话，37 帧，3 个卡壳事件 e1/e2/e3，3 道题）。
- 已用 jsonschema 校验通过 + 不变量检查通过：t 严格递增、frameId 唯一、证据帧存在且落在事件区间、p 段落引用合法、每题仅 1 个正确项。

### Android 原型（2026-11-17）

- 工程：`android/`（AGP 8.5.2 / Kotlin 2.0.21 / Compose BOM 2024.09.03 / minSdk 26 / targetSdk 34）。
- 包结构 `com.mindgap.app`：
  - `data/model/Timeline.kt`：与 Schema 一一对应的 kotlinx.serialization 模型（74 字段、21 个枚举值）。
  - `data/TimelineRepository.kt`：从 `assets/mock_timeline.json` 读取假会话，返回 Loading/Ready/Failed。
  - `MainActivity.kt`：`produceState` + IO 加载，失败显示原因。
  - `ui/MindGapApp.kt`：Scaffold + 底部四页导航（采集/回放/找茬/档案）+ 共用 Tag/SectionCard/StatRow。
  - `ui/SessionScreen.kt`：会话信息、采集授权开关（撤回即不出设备）、卡壳概览。
  - `ui/ReplayScreen.kt`：按帧回放，可隐藏重复帧，卡壳证据帧打标签。
  - `ui/ChallengeScreen.kt`：逐题作答，选后显示对错、解析、证据帧时间与编号。
  - `ui/ArchiveScreen.kt`：卡壳类型分布条形图、事件明细、阅读进度。
- 静态校验通过：assets 与 mock 字节一致；Schema 字段全部落到 Kotlin 模型；枚举 @SerialName 全覆盖；括号平衡；声明的 Composable 均存在。
- **未做**：本机无 JDK / Android SDK / Gradle，工程未编译、未上真机；需在 Android Studio 打开 `android/` 后补 Gradle wrapper 与首次构建。
- 下一步：M3–M6 与云端找茬接口已完成；下一步真机联调与演示材料。

### M1 采集通道（2026-11-17 已实现，待真机验证）

- 数据流：无障碍文本 / 屏幕指纹 / 麦克风 PCM → `CaptureSession`（唯一入口）→ `EventStore` 追加 JSONL（`filesDir/capture/{sessionId}.jsonl`）+ PCM 文件。
- 新增文件：
  - `capture/RawEvent.kt`：原始事件模型（screen_text / screen_frame / audio_segment / app_switch / scroll / click / marker）。
  - `capture/EventStore.kt`：JSONL 追加写 + 读回，换 Room 时只需替换此对象。
  - `capture/CaptureSession.kt`：`StateFlow<CaptureSnapshot>` 状态机（IDLE/RUNNING/PAUSED/FINISHED）+ 计数 + 落盘；文本 1.5s 内同哈希去重。
  - `capture/AudioCapture.kt`：16kHz 单声道 PCM，100ms 分块，能量 VAD（-45dB 起、静音 600ms 断句），只输出语音段边界 + 峰值 dB。
  - `capture/CaptureService.kt`：前台服务，`ServiceCompat.startForeground` 带 `mediaProjection|microphone` 类型；1fps 采样 8x8 亮度指纹（不存位图）；通知含暂停/继续/结束。
  - `capture/ScreenTextAccessibilityService.kt`：遍历活动窗口节点取文本，800ms 节流，同哈希跳过。
  - `ui/CaptureScreen.kt`：状态/计时/四类计数、权限检查、开始-暂停-结束、结束后读回原始事件列表。
- 关键 API 顺序（API 34 硬要求）：先 `startForeground`（带 mediaProjection 类型）→ 再 `getMediaProjection` → 先 `registerCallback` → 再 `createVirtualDisplay`。
- 授权处理：麦克风被拒时降级为"仅屏幕与文本"；未开无障碍时降级为"仅指纹与语音"，不阻塞采集。
- 真机验收清单：① 开无障碍 → ② 开始采集（系统录屏弹窗）→ ③ 暂停/继续各一次 → ④ 结束 → ⑤ 统计四项计数非零 → ⑥ 退出重进能加载原始事件 → ⑦ 无障碍关闭时仍能采集。
- 已知限制：M1 本身不跑 OCR/ASR（已由 M2 补齐）；JSONL 无索引，长会话读回需分页。

### M2 端侧感知（2026-11-17 已实现，待真机验证）

- 依赖（均已核对可下载）：sherpa-onnx 2.0.1 官方 AAR（JitPack `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:2.0.1@aar`，49 MB，`classes.jar` 含 `OfflineRecognizer`/`Vad`/`OfflineStream`，`jni/` 含四种 ABI）；`com.google.mlkit:text-recognition-chinese:16.0.1`（内置中文模型，不依赖 Google Play）；`commons-compress:1.26.2`。APK 只保留 arm64-v8a / x86_64。
- 模型按需下载（`perception/ModelManager.kt`）：Paraformer-small 中文 int8（74.3 MB，包内 `model.int8.onnx` + `tokens.txt`）+ Silero VAD（0.64 MB）。下载 → 解压 → 完整性校验；状态流 MISSING/DOWNLOADING/EXTRACTING/READY/FAILED；已有文件不重复下载。网络受限时只需替换两个 URL 常量。
- 采集端扩展（M1 补丁）：
  - 新增 `RawEventType.AUDIO_START` 与 `RawEvent.imagePath`；`AUDIO_START.startMs` = PCM 起点在会话时间轴的位置。
  - `AudioCapture` 改为按「已写入采样数」换算语音段边界，暂停不再造成时间漂移；能量 VAD 仍只作粗提示。
  - `CaptureService` 每次 1fps 采样都算 8x8 亮度指纹；汉明距离 ≥6 且间隔 ≥1.5s 时落盘 JPEG 关键帧（长边 ≤1920、质量 65），压缩在单线程池执行；单会话上限 600 张（约 100 MB）；普通帧只留指纹。
- 离线转写（`perception/OfflineTranscriber.kt`）：PCM 按 512 采样喂 Silero VAD，边切边用 Paraformer-small 解码；`segment.start` 是绝对采样下标，按 `originMs + start/16` 换算为会话毫秒。单线程顺序处理，内存只保留当前语音段（≤20s）。
- OCR（`perception/OcrEngine.kt`）：ML Kit 中文识别；`BitmapFactory` 按最长边 1920 采样解码，避免大图内存峰值。
- 去重（`perception/FrameDedup.kt`）：画面在采集端用感知哈希去重；OCR 文本用 SHA-256 指纹 + 字符二元组 Dice 相似度（≥0.90 判重）。重复帧保留并标记 `isDuplicate`，供 M4 判断「停留」。
- 结果落盘：`filesDir/capture/{sessionId}.perception.json`（`PerceptionResult`）：
  - `speech[]`：segmentId / startMs / endMs / text / isFinal / asrConfidence（暂空：sherpa Android API 不暴露 token 概率，不写伪值）
  - `ocr[]`：frameId / t / text / textHash / isDuplicate / similarity / imagePath
  - `stats`：采样帧、关键帧、OCR 帧、文本重复数、语音字数、画面/文本去重率、耗时
  - `notes`：模型缺失、单帧失败等降级原因
- 编排（`perception/PerceptionPipeline.kt`）：READ → ASR（0–58%）→ OCR（60–97%）→ 落盘；ASR 缺模型或失败不影响 OCR，原因写入 notes。
- 采集页新增「端侧感知（M2）」卡片：模型状态/下载进度、运行按钮（仅采集结束后）、阶段进度、结果摘要与片段预览；「当前状态」增加关键帧计数。
- 静态校验：23 个 Kotlin 文件括号与 package 检查通过；AAR 的 `classes.jar` 已确认含所需 API；模型包内文件结构已核对。
- **未做**：仍无 JDK / SDK / Gradle，未编译、未真机跑通；演示前需在有网环境完成一次模型下载，并预热 JitPack 与 GitHub Release 访问；赛场上模型就绪后无需联网。
- 真机验收清单：① 下载模型到 READY；② 录一段含讲解的会话并结束；③ 运行 M2 → 语音段非零且文本可读；④ OCR 有效帧数 ≤ 关键帧数，解码/识别失败写入 notes；⑤ 关闭无障碍再录 → 仅 OCR 仍有文本；⑥ 暂停 30s 后继续说话 → 转写时间不漂移；⑦ 重进应用后 `perception.json` 仍在。
- 下一步：M3 已完成（见下）；M4 消费 `frames.features` 做卡壳判定。

### M3 时空对齐（2026-11-17 已实现，待真机验证）

- 输入：`{sessionId}.jsonl`（M1 原始事件）+ `{sessionId}.perception.json`（M2 语音/OCR）；M2 缺失时降级为「仅屏幕与操作」，原因写入 `notes`。
- 输出：`filesDir/capture/{sessionId}.timeline.json`，对应 `schema/timeline.schema.json`（schemaVersion 1.0.0，bucketMs 500）。
- 新增 `align/`：
  - `TimelineAligner.kt`：500ms 桶聚合。屏幕取最近的无障碍文本、缺失时用最近 OCR；应用切换后旧文本失效；语音取与桶重叠最多的转写段；操作按 切应用 > 点击 > 滚动 取一条；暂停区间输出空帧且不累计停留。
  - `ParagraphIndex.kt`：屏幕文本快照 → 稳定段落 ID（p000…）；相同 hash 复用，最近 6 段做 Dice ≥0.92 近似合并，吸收 OCR 抖动。
  - `TimelineStore.kt`：时间轴读写；`encodeDefaults=true` 保证 `bucketMs` 等 Schema 必填字段落盘；`explicitNulls=false` 保证可选字段「缺失」而非 null（Schema 不接受 null）；启动时载入最近一份。
  - `AlignmentPipeline.kt`：READ → 对齐 → 落盘，StateFlow 进度；`restore()` 在应用重启后恢复最近时间轴。
- 特征：`dwellMs` 按连续段落累计、跨暂停冻结；`revisitCount` 为当前时刻的累计重入次数；`isStalled` 留给 M4；`attentionScore` 暂空（不臆造数值）。
- 文档：无真实文档源，段落由屏幕文本快照派生；`docId=doc-{sessionId 前 8 位}`，标题取首个快照首行；`progress = 当前段落 charEnd / totalChars`。
- 体积控制：重复帧只写 `textHash`、不重复正文；正文截断 800 字；单会话时间轴约数百 KB。
- UI：采集页新增「时空对齐（M3）」卡片（生成/进度/摘要/去回放）；回放页优先显示真实会话并标注「真实会话」；M1 无障碍服务在切窗时立即抓一次文本，避免 M3 等不到内容变化而丢屏。
- 静态校验：27 个 Kotlin 文件括号/package 检查通过；用 jsonschema 校验了含默认字段、空数组、重复帧省略正文、无屏幕/无语音帧的 M3 输出样例，符合 Schema。
- **未做**：仍未编译/真机验证；段落粒度是「屏幕文本快照」而非真实文档段落；OCR 置信度未填。
- 验收清单：① 采集并结束 → ② 运行 M2 → ③ 生成时间轴 → ④ 回放同时看到屏幕文本/语音/操作 → ⑤ 切到别的应用再回来，旧屏幕文本不串台 → ⑥ 暂停 30s，区间为空帧且停留不增长 → ⑦ 重启应用，回放页仍显示上次时间轴。
- 下一步：M4 已完成（见下）；M5 读 `stuckEvents` 生成反事实找茬题。

### M4 卡壳判定（2026-11-17 已实现，待真机验证）

- 输入：M3 的 `TimelineSession`（内存优先）；输出：`stuckEvents` 回写同一份 `{sessionId}.timeline.json`，并更新 `notes`。
- 新增 `judge/`：
  - `StuckDetector.kt`：三类可解释规则 + 基线校准 + 证据帧 + 同段落合并。
  - `TermExtractor.kt`：引号短语 / 会话低频拉丁技术词抽取；用解释标记与括号定义判断是否已解释。
  - `JudgementPipeline.kt`：判定编排、落盘、回写 `AlignmentPipeline`；M3 重跑时 `invalidate` 旧判定。
- 基线：会话 >5 分钟时前 5 分钟静默校准；不足 5 分钟用前 1/3（最多 60s）校准。样本 <3 次时用先验 P90=15s / P50=6s；P90 限幅 8–180s。
- 时间异常：段落连续停留 ≥ max(P90×1.2, 20s)，且事件起点在静默期之后；置信度随超出比例增长（0.55–0.95）。
- 行为异常：90s 内回看同一段落，且前一次停留 ≥3s；空档内有切窗/点击/滚动，或空档 <4 个桶才算回看（避免把暂停误判为回看）。切窗返回时 `signals.switchedApp=true` 并在摘要标注应用包名。
- 语义异常：段落停留 ≤ 校准 P50（6–20s）即离开；段落含低频（≤2 次）技术词，当前段 + 下一段上下文无解释标记/括号定义，且对应时段的语音未提到该词；每段最多一个术语。
- 证据与去重：证据帧全部来自现有 frames 且落在事件区间内；同段落事件合并窗口 ±10s，最终每段最多 1 个事件（取置信度最高），最多 50 个；事件 ID 形如 e001。
- 回写链路：`JudgementPipeline` → `TimelineStore.save` → `AlignmentPipeline.updateSession`，回放/档案/会话页立即刷新；证据帧回写 `features.isStalled=true`，重跑判定会先重置旧标记；`isCurrent` 防止 M3 重跑后 M4 用旧时间轴覆盖新结果。
- UI：采集页新增「卡壳判定（M4）」卡片（运行/进度/基线/三类计数/前 5 条事件/去回放看证据）；回放页对证据帧打标签，档案页统计类型分布。
- 静态校验：30 个 Kotlin 文件括号/package 检查通过；M4 的 `StuckEvent` 样例通过 Schema 与「证据帧存在且在事件区间内」校验。
- **未做**：仍未编译/真机验证；行为通道未覆盖搜索/删除重写（M1 未采集对应事件）；语义术语是低频词/引号词启发式，不是完整 NLP；`attentionScore` 仍未使用。
- 验收清单：① 采集 3 分钟以上并跑完 M2/M3 → ② 运行 M4 有基线数值 → ③ 长时间停留段出现时间异常且证据帧可回放 → ④ 切到搜索应用再回来出现行为异常并带 `switchedApp` → ⑤ 快速跳过含 AppendEntries/commit 的段落出现语义异常 → ⑥ 暂停 30s 不被误判为回看 → ⑦ 重启应用后卡壳事件仍在。
- 下一步：M5 已完成（见下）；M6 接共驾界面与端云协同。

### M5 反事实找茬（2026-11-17 已实现，待真机验证）

> 2026-11-18 已重做核心机制：由「原文选择题」改为「1 句原文 + 2 句可验证反事实改写」，并加入先报信心、隐性盲区统计。见下方「M5 反事实重做」。

- 输入：M4 的 `stuckEvents`；输出：`quizItems` 回写同一份 `{sessionId}.timeline.json`。
- 新增 `quiz/`：
  - `QuizGenerator.kt`：① 压缩理解状态（段落正文、术语、停留/回看/切窗、当时语音）；② 生成三选项；③ 校验失败换一批候选重试（最多 3 次），仍失败则跳过该事件。
  - `QuizValidator.kt`：恰好 3 项 A/B/C、唯一 correct；证据帧必须存在；正确项证据必须属于卡壳段且屏幕文本逐字包含选项，干扰项证据必须来自其他段且逐字包含选项。
  - `QuizPipeline.kt`：编排、落盘、回写 `AlignmentPipeline`；M3/M4 重跑会使旧题失效，`isCurrent` 防旧题覆盖新时间轴。
- 选项来源：正确项取卡壳段原文中的一句（语义题必须包含被跳过的术语）；干扰项取其他段落原文句，长度接近、优先含技术词的句子；不做机械改写，保证每项都能回指原文。
- 随机化：按 `eventId + attempt` 固定种子打乱选项顺序，重跑结果稳定；语义题的干扰项排除目标术语，避免出现两个正确答案。
- 断网模式：`mode=offline_template`、`model=mindgap-template-v1`；云端生成已接入 `server/` + `CloudQuizClient`，网络或证据校验失败会自动回退本地模板。
- UI：采集页新增「反事实找茬（M5）」卡片（生成/进度/题目数/跳过数/题干预览/去答题）；找茬页展示三选项、逐项解析与证据帧。
- 静态校验：33 个 Kotlin 文件括号/package 检查通过；M5 的 `QuizItem` 样例通过 Schema 与「唯一正确 + 逐项证据包含」校验。
- **未做**：仍未编译/真机验证；云端 HTTP 层未在本机跑（FastAPI 未安装）；LLM 需配置 `LLM_API_KEY` 才有真实生成，否则走服务端模板；题目是模板风格，表达丰富度有限。
- 验收清单：① M4 有事件 → ② 生成找茬题 → ③ 每题 3 项且仅 1 个正确 → ④ 选择后显示解析与证据帧 → ⑤ M4 重跑后旧题清空 → ⑥ 重启应用题目仍在 → ⑦ 文档段落不足时安全跳过并写入 notes。
- 下一步：M6 已完成（见下）；下一步真机联调与云端生成接口。

### M6 共驾界面（2026-11-17 已实现，待真机验证）

- 四页闭环：采集会话（含 M2–M5 流水线）、时间轴回放、找茬挑战、成长档案；底部导航不变。
- 采集页新增「端侧闭环」状态条：采集 / 识别 / 对齐 / 判定 / 找茬五段状态 + 下一步提示（先采集 → M2 → M3 → M4 → M5）。
- 数据与隐私（新增 `privacy/SessionDataManager.kt`）：
  - 本地优先：M3 生成的 `capture.consent` 默认 `revoked`；原始事件、PCM、关键帧、感知结果、时间轴全部在应用私有目录。
  - 显式上传授权：采集页开关修改 `consent` 并写回 `{sessionId}.timeline.json`；当前版本没有任何上传代码，授权仅对未来云端生成生效；档案页也展示当前授权状态。
  - 一键删除：删除 JSONL / PCM / 关键帧目录 / perception.json / timeline.json，并清理 `CaptureSession` 与 M2–M5 内存状态；采集中或任一流水线运行时禁用，删除模型不受影响。
- 找茬页：答案提升到页面级，显示题目数 / 已答 / 正确率；每题仍保留对错、逐项解析与证据帧。
- 成长档案：新增 `archive/ArchiveRepository.kt`，扫描全部 `*.timeline.json`，展示跨会话累计（会话数 / 累计时长 / 累计卡壳 / 累计题目）与历史会话列表；每条历史可删除（复用 `SessionDataManager`）。
- 静态校验：35 个 Kotlin 文件括号/package 检查通过；M3 / M4 / M5 契约样例继续通过。
- **未做**：仍未编译/真机验证；无真实云端上传，授权只是本地状态；历史会话列表暂不支持点开回放（回放页展示当前时间轴）。
- 验收清单：① 完成一次采集并跑 M2–M5 → ② 采集页五段状态全亮 → ③ 授权开关关闭/打开并重启应用后状态保持 → ④ 删除本次数据后原始文件与 M2–M5 状态清空、模型保留 → ⑤ 档案页累计数与历史列表正确 → ⑥ 删除历史会话后列表刷新 → ⑦ 找茬页显示已答数与正确率。
- 下一步：真机联调、演示脚本；云端找茬接口见下。

### 真机联调：权限与删除（2026-11-17 静态审计完成，待真机执行）

- 环境：开发机无 adb / JDK / Android SDK / Gradle，也无已连接设备，无法在本机编译、安装或抓 logcat；以下命令需在装有 Android Studio + ADB 的机器执行。
- 本轮静态审计修复：
  - 权限回调误报：只申请通知权限时，结果表没有 RECORD_AUDIO 会误判“麦克风未授权” → 改为回查真实授权状态。
  - 屏幕录制失败或系统停止共享时，状态卡只显示“采集结束” → `CaptureSession.finish(message)` 透传“采集失败：屏幕录制授权失败 / 系统已停止屏幕共享”。
  - 采集页权限卡新增麦克风、通知、屏幕录制三行状态与刷新入口；无障碍检测同时校验全局开关与 `pkg/.Class` 两种存储格式。
  - 无障碍配置增加 `flagIncludeNotImportantViews`，提高 WebView/阅读器文本覆盖率；未采集时服务直接返回，不遍历节点。
  - 删除增加日志（session 前缀、文件数、字节数、失败路径、残留路径），删除确认按钮在流水线运行时禁用。
- 权限验收（adb）：
  - `adb shell dumpsys package com.mindgap.app | findstr "RECORD_AUDIO POST_NOTIFICATIONS granted"`
  - `adb shell settings get secure enabled_accessibility_services`
  - 首次开始采集：应依次出现麦克风/通知请求 → 系统录屏弹窗；拒绝麦克风后仍能开始，通知文案降级为仅屏幕。
  - 关闭无障碍再采集：仍能采到画面指纹与语音；权限卡应显示“无障碍服务未开启”。
  - 通知栏点“停止共享”：采集页状态卡应显示“系统已停止屏幕共享”。
  - 首次采集前 `adb logcat -s MindGapPrivacy` 不应出现任何 consent/delete 日志。
- 删除验收：
  - 删除前：`adb shell run-as com.mindgap.app ls -l files/capture` 应看到 `{id}.jsonl`、`{id}.pcm`、`{id}-frames/`、`{id}.perception.json`、`{id}.timeline.json`。
  - 确认删除后：`adb logcat -s MindGapPrivacy` 出现 `deleted session=... files=... bytes=...`，且无 `delete failed` / `delete incomplete`。
  - 同一目录不再有该 session 的任何文件；采集页回到空闲、M2–M5 卡片清空；`files/models` 下的模型仍在。
  - 采集中/暂停时删除按钮禁用；任一流水线运行时删除按钮与确认按钮均禁用。
  - 删除历史会话后，档案页累计数字与列表即时刷新；删除当前会话后回放页回落到假数据。
- 授权验收：
  - 打开授权 → `adb shell run-as com.mindgap.app cat files/capture/{id}.timeline.json | findstr consent` 应为 `"consent": "granted"`；强制停止后重进仍保持。
  - M3 重新生成时间轴后授权应重置为 `revoked`（隐私默认）。
- **未验证**：以上全部未在真机执行；系统弹窗、国产 ROM 的无障碍/录屏限制、`run-as` 与通知行为仍需实机确认。

### 云端找茬接口（2026-11-17 已实现，服务端逻辑与 HTTP 已测）

- 服务端 `server/`：
  - `quiz_logic.py`：请求校验、LLM 调用（OpenAI 兼容）、严格证据校验、服务端模板回退、答案键内存存储。
  - `app.py`：FastAPI，`GET /health`、`POST /v1/quiz/generate`、`POST /v1/quiz/answer`。
  - `test_quiz_logic.py`：不依赖 FastAPI 的逻辑测试，已在本机通过（时间题/语义题/答案键/篡改证据/材料不足）。
  - FastAPI TestClient 已验证：`/health` 200、`/v1/quiz/generate` 200（3 项且唯一正确）、`/v1/quiz/answer` 判定正确、context 不足返回 422。
- 请求只含压缩状态：`event`（类型/术语/停留/回看/切窗）+ `focus` + `context` + `speech` + 逐帧 `evidence`；不含音频、截图或原始事件。
- 生成策略：有 `LLM_API_KEY` 时要求 LLM 只逐字摘录证据句；输出必须 3 项 A/B/C、唯一正确、每项证据帧来自请求且屏幕原文包含选项，否则回退服务端模板；模板仍不合法则返回 422。
- 答案键：`/v1/quiz/answer` + 内存 key store 已实现；当前 `generate` 为本地演示仍带 `verdict`，生产应剔除并改由客户端提交答案。
- Android 客户端：
  - `cloud/CloudQuizClient.kt`：校验 `consent=granted`、构建压缩请求、HTTP 超时、解析后复用 `QuizValidator` 逐项复核证据；失败返回 null。
  - `QuizPipeline.startOnline`：先跑离线模板作为兜底，再逐事件请求云端；首次失败快速回退，最终混合在线/离线题目并落盘回写。
  - 采集页 M5 卡片增加「云端生成（需授权）」；未授权时提示先打开上传授权；题目来源显示在线/离线数量。
  - Debug 构建 `src/debug/AndroidManifest.xml` 允许访问本地 HTTP 开发服务；Release 需换 HTTPS。
- 启动方式：
  - `cd server && pip install -r requirements.txt`
  - 可选 `set LLM_API_KEY=...`（不配则用服务端模板）
  - `uvicorn app:app --host 0.0.0.0 --port 8000`
  - 模拟器默认 `http://10.0.2.2:8000`；真机改 `CloudConfig.BASE_URL` 为开发机局域网 IP。
- **未验证**：Android 端与云端尚未实际联调（本机无 SDK/设备）；真实 LLM 输出质量未验证（本机无 Key，走服务端模板）。
- 下一步：真机/局域网联调云端接口，配置 LLM Key，跑通 3–5 个事件的在线题目并对比离线题质量。

### 构建准备与编译排雷（2026-11-17，待首次构建验证）

- 补齐 `android/` 缺失的构建入口：`gradle/wrapper/gradle-wrapper.jar`（43,504 B）、`gradle-wrapper.properties`（Gradle 8.9）、`gradlew`、`gradlew.bat`。此前没有 wrapper，Android Studio 无法同步。
- 本轮修复的两处确定问题：
  - `ui/MindGapApp.kt` 使用 `Alignment.CenterVertically` 但漏 import → 补 `androidx.compose.ui.Alignment`，原工程会直接编译失败。
  - `ui/ArchiveScreen.kt` 卡壳类型分布条在 count=0 时调用 `fillMaxWidth(0f)` → 运行期崩溃；改为仅 count>0 才画条。
- 依赖核对（均返回 200，可解析）：
  - JitPack：`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:2.0.1@aar` 49.4 MB。
  - Google Maven：`text-recognition-chinese:16.0.1`、`compose-bom:2024.09.03`、AGP 8.5.2。
  - Maven Central：`kotlinx-serialization-json:1.7.3`、`commons-compress:1.26.2`。
  - Compose BOM → material3 1.3.0；其 `releaseApiElements` 含 foundation 与 material-icons-core，`Icons.Filled.*` 无需额外依赖。
  - 37 个 Kotlin 文件对 `Alignment` / Compose / 协程 / 序列化等常用符号做了 import 与声明扫描，未发现其他缺失 import。
- 源码与 sherpa-onnx v2.0.1 Kotlin API 逐项对齐：`OfflineRecognizer`、`OfflineRecognizerConfig`、`OfflineModelConfig`、`OfflineParaformerModelConfig`、`FeatureConfig`、`Vad`、`VadModelConfig`、`SileroVadModelConfig`、`SpeechSegment`、`OfflineStream.acceptWaveform(samples, sampleRate)` 的构造参数、命名与返回字段一致。
- 仍未编译：本机无 JDK / Android SDK / ADB；wrapper 与依赖只能保证「可下载、可解析」，真实编译需在 Android Studio 首次 Sync 验证。
- 构建步骤（装有 Android Studio 的机器）：
  1. 打开 `android/` 目录（不是 `app/`），选择 Trust Project。
  2. Gradle JDK 选 17+（Studio 自带 JBR 即可）。
  3. 首次 Sync 下载 Gradle 8.9 / AGP 8.5.2 / Compose / sherpa AAR；如缺 SDK，在 SDK Manager 安装 Android 14 (API 34)。
  4. 手机开 USB 调试后 Run 'app'；Build 首条错误直接回贴。

### M5 反事实重做（2026-11-18 已实现，待真机与 Studio 验证）

- 动机：原 M5 的正确项是卡壳段原句、干扰项取自其他段落，本质是「哪句眼熟」，不构成反事实，也证明不了盲区是隐性的。
- 新题面：1 句原文 + 2 句反事实改写。题干明确「只有一句与原文完全一致，另外两句各被改了一个词」。
- 数据契约（向后兼容，schemaVersion 仍为 1.0.0）：
  - `QuizItem.questionType = counterfactual_quote`（可选）。
  - `QuizOption.mutation`（仅干扰项）：`type / originalText / editedText / changedToken / sourceFrameId`。
  - Schema 与 Kotlin `QuizMutation`、`MutationType` 已同步；旧 mock 仍能通过校验。
- 改写引擎 `quiz/CounterfactualMutator.kt`（纯 Kotlin、无 Android 依赖）：
  - 否定：必须→不必、可以→不能、相同→不同、复制→不复制等；动词否定优先改最后一次出现，避开「日志复制」这种名词开头。
  - 情态：需要→无需、只能→也能、通过→绕过。
  - 量化：多数→少数、至少→至多、每个→部分、任意→固定。
  - 数值：两→三、过半→不足一半。
  - 关系反转：A 导致 B → B 导致 A、依赖/决定的主客互换。
  - 难度分级 easy/medium/hard；`verify()` 会重新执行规则，拒绝客户端或云端伪造的改写记录。
- 生成器 `QuizGenerator` 重写：
  - 正确句优先含卡壳术语、优先有 HARD 改写、句子较长；
  - 干扰项优先改写「正确句本身」（最难），再补其他句；每条原句最多贡献一项，材料不足时允许同句另一条规则；
  - 改写句若在整篇会话文本里真实存在则丢弃，避免出现两个正确项；
  - 逐项证据必须能在卡壳段原文中逐字找到，证据不足则跳过该事件。
- 校验器 `QuizValidator` 重写：唯一正确项必须在卡壳段逐字命中；干扰项必须带 mutation、文本一致、原文在卡壳段、改写句不是文档真话、来源帧在证据帧内、且通过 `verify()`。
- 隐性盲区闭环：
  - 挑战页先让用户选「我很确定 / 不太确定」，选完才展开选项；答后区分「掌握 / 侥幸答对 / 隐性盲区 / 显性卡点」。
  - 新增 `QuizAttemptStore`（`{sessionId}.attempts.jsonl`）记录信心、选项与对错；同一题只保留首次作答，切页回来会恢复已答与信心，避免重复刷高盲区数；会话删除时一并清除。
  - 挑战页显示已答、正确率、隐性盲区次数；档案页显示累计答题、隐性盲区、高信心错误率，以及每个历史会话的答题/盲区数。
- 云端同构：
  - 请求新增 `questionType=counterfactual_quote` 与 `mutationCount=2`。
  - 服务端模板改为对 focus 原文做同一套词表改写；LLM 输出必须带 mutation，且服务端会重新执行规则校验，不合法直接回退模板。
  - Prompt 明确禁止使用 context 句子作选项，并列出允许的改写词对。
- 验证结果（本机可执行的部分）：
  - `python server/test_quiz_logic.py` 通过：模板题、语义题、篡改证据、篡改改写、错误题型、无 context、材料不足。
  - 用真实 mock 会话跑服务端同构引擎：3/3 个卡壳事件都能出题，例如
    - e1：任意→固定 / 连续→不连续；
    - e2：通过→绕过 / 复制→不复制；
    - e3：两→三 / 相同→不同。
  - 更新后的 Schema 校验通过：mock 时间轴仍合法，含 mutation 的新题目也合法。
  - mock 的 3 道题已替换为反事实题并同步到 `android/app/src/main/assets/mock_timeline.json`，两份文件字节一致（sha256 `baebabe8…659f41f`），Schema 校验 3/3。
  - 新增 JVM 单测 `CounterfactualMutatorTest`、`QuizGeneratorTest`（JUnit 4.13.2），覆盖改写、伪造记录、缺 mutation、文本不一致、证据跨段；**2026-11-18 已实际执行，全部通过**。
- 仍未做：
  - 规则改写仍是词表级，细心的用户可能靠语感识别；下一步接 LLM 生成更自然的反事实陈述，但继续用同一套 `verify` 思路做结构约束。
  - 尚未实现「高信心答错后自动追加更难的变式题」。
  - 尚未做真人校准实验（高信心错误率、题目可答率、误报率）；离线评测已执行，见下节。

### 离线评测集与指标（2026-11-18 已执行）

- 目的：答辩时不只说“规则是这样”，而是给出判定精确率/召回率、出题校验通过率、改写覆盖率、同句陷阱比例。
- 语料生成 `eval/generate_fixtures.py` → `android/app/src/test/resources/eval/`：
  - 15 个受控会话，共 10 个 ground-truth 事件：
    - 正常 2（短会话 / 带基线校准）；
    - 时间异常 2（短 / 校准后）；
    - 行为异常 2（切窗后回看，短 / 校准后）；
    - 语义异常 2（快速跳过未解释术语，短 / 校准后）；
    - 诱饵 5（暂停 30s、校准期内长停留、>90s 空档回看、语音已解释、括号已定义）；
    - 组合 2（时间+语义、行为+语义）。
  - 每个会话都包含精确的段落停留、回看、切窗、暂停与语音；标签在 `labels.json`。
  - 15/15 通过 Schema 校验；另用独立规则复核了标签与时间/行为/语义条件一致（无额外误报）。
- 评测入口（直接跑真实代码，不是另一套评测算法）：
  - `android/app/src/test/java/com/mindgap/app/eval/EvaluationRunner.kt`：调用 `StuckDetector` 与 `QuizGenerator`，匹配标签，统计 precision/recall/F1、分类型指标、题目生成率、校验失败、干扰项改写覆盖率、同句陷阱、难度分布。
  - `MindGapEvaluationTest.kt`：JUnit 门禁；报告写到 `app/build/reports/mindgap-eval/report.md` 与 `report.json`。
- 运行命令（已验证）：
  - `cd android && gradlew.bat :app:testDebugUnitTest`
- **已执行结果（2026-11-18，真实 Kotlin 代码，不是 Python 镜像）**：
  - 会话 15 / 期望事件 10 / 检出 10：TP 10 / FP 0 / FN 0；
  - 精确率 100%、召回率 100%、F1 100%；
  - 分类型：time 3/3、behavior 3/3、semantic 4/4；
  - 5 个诱饵全部零误报；
  - 出题 10/10，跳过 0，校验失败 0；
  - 干扰项 20/20 带可验证改写；同句陷阱 10/10；难度 medium 4 / hard 6。
- 报告文件：`eval/report.md`、`eval/report.json`。
- 测试门禁仍保留 precision ≥ 0.5、recall ≥ 0.5、校验失败 = 0，作为回归底线；真实场景指标另算。
- **重要限制**：100% 是合成语料上的实现/回归结果，语料按规则构造，不能当作真实场景精度；答辩必须同时给出真人试用数据。
- 下一步：真机权限验收 + 5–8 人真人试用（记录高信心错误率、题目可答率、误报率），再根据真实数据调阈值。

### 产品闭环补强（2026-11-18 已实现，待真机验证）

- 历史会话可打开：
  - 档案页历史卡片与「打开回放」按钮会读取 `{sessionId}.timeline.json`；
  - 读取成功后 `AlignmentPipeline.open` 替换当前时间轴，并跳到回放页；
  - 回放、找茬、档案立即使用该历史会话；读取失败给出提示，不影响当前数据。
- 首次启动数据边界说明（`ui/Onboarding.kt`）：
  - 三页：能做什么 / 采集什么 / 用户能控制什么；
  - 完成后写入 `SharedPreferences("mindgap", onboarding_done=true)`；
  - 采集页「数据与隐私」卡片新增「查看数据边界说明」，可随时重新打开。
- 未编译、未真机验证；本轮只改了 Compose 状态与导航，未触碰权限与采集逻辑。

### 行为通道补强：搜索与删除（2026-11-18 已实现，待真机验证）

- 采集端：
  - `RawEventType` 新增 `search` / `erase`；无障碍配置增加 `typeViewTextChanged`。
  - 搜索：输入框的 `viewId` 或 `hintText` 含 search/搜索时，记录一次 `SEARCH`（1.5s 节流）。
  - 删除：一次文本变化减少 ≥2 个字符时记录 `ERASE`（1s 节流）。
  - 隐私：只记录动作类型与包名，**不保存输入内容、不保存前后文本**；节点或事件标记为密码框时直接跳过。
- M3/M4：
  - 500ms 桶内动作优先级：切应用 > 搜索 > 删除/点击 > 滚动；输出 `ActionType.SEARCH` / `ERASE`。
  - M4 的「空档内有真实操作」现在也把搜索/删除算进去，回看判定更贴近真实查证行为。
- UI/日志：原始事件回放、动作计数、状态描述都补了搜索/删除文案，仍不展示输入内容。
- 新增 JVM 单测 `TimelineAlignerTest`：验证搜索/删除/切应用在 500ms 桶里的动作类型与优先级，并确认搜索不落查询内容；本机无 JDK 未执行。
- 限制：搜索框识别是启发式；不保存查询词，因此只能判断「搜过」，不能分析搜了什么；删除只按长度减少判断，不区分整段重写。
- 未编译、未真机验证；需要重点验证国产 ROM 的文本变化事件是否可用、是否出现高频事件。

### 首次真实构建与验证（2026-11-18）

- 工具链统一放在 `E:\mindgap-toolchain`：
  - `jdk17`（Corretto 17.0.21.1）；
  - `android-sdk`（platform-tools / platforms;android-34 / build-tools;34.0.0）；
  - `gradle-home`（Gradle 8.9 与全部依赖缓存）；
  - `downloads`（原始 JDK / SDK 压缩包）。
- 工程路径含中文，AGP 会拒绝；已在 `gradle.properties` 加 `android.overridePathCheck=true`。
- 中文路径下测试 `ClassNotFoundException` 已定位并修复（2026-11-18）：
  - 现象：测试类初始化即失败，`java.lang.ClassNotFoundException: com.mindgap.app.quiz.QuizGeneratorTest`。
  - 根因：Gradle 把测试 worker 的类路径写入参数文件（`gradle-home/.tmp/gradle-worker-classpath*.txt`）时用的是守护进程默认字符集（原为 UTF-8）；JDK 17 启动器按系统 ANSI 编码读取该文件（中文 Windows 为 GBK），中文路径 `E:\软创\笔记本\...` 被解码成乱码，类路径整体失效。JUnit/Gradle 自身位于 ASCII 路径，所以 worker 能启动、只是加载不到测试类。
  - 修复：`gradle.properties` 的 `org.gradle.jvmargs` 改为 `-Dfile.encoding=GB18030`，与系统编码对齐（GB18030 对常用汉字与 GBK 字节一致）。
  - 验证：`gradle clean :app:testDebugUnitTest :app:assembleDebug` 直接在 `E:\软创\笔记本\android` 执行成功（43 个任务全部执行），不再需要 `E:\mindgap-build` 副本。
  - 校验：测试报告 XML 为 UTF-8、中文正常；APK 内 `classes*.dex` 中文文案正常；评测报告中文正常。
- `android/local.properties` 指向 `E:\mindgap-toolchain\android-sdk`（本机专用，不应提交）。
- 验证结果：
  - `:app:testDebugUnitTest`：4 个测试类、9 个测试方法全部通过。
  - `:app:assembleDebug`：成功；APK 已复制到 `dist/MindGap-debug.apk`（105,426,247 B，sha256 `92DB934A…FBA168`）。
  - APK：包名 `com.mindgap.app` / versionName 0.2.0 / minSdk 26 / targetSdk 34；含 sherpa-onnx、onnxruntime、ML Kit OCR 原生库。
- 真正跑起来才发现并修掉的问题：
  - `QuizGeneratorTest` 三处 JUnit `assertTrue` 参数顺序写反；
  - 评测入口只跑了判定器，没有把 `stuckEvents` 回写进会话，导致出题数为 0；
  - `Icons.Filled.List` 迁移到 `Icons.AutoMirrored.Filled.List`。
- 仍未验证：真机安装、无障碍/录屏/麦克风权限、国产 ROM 文本变化事件、云端真实 LLM Key、真人试用。

### 风险与待确认

- 国产 ROM 对无障碍 / 悬浮窗 / 后台录屏限制严格 → 需引导页 + 备用 OCR 路线，演示用真机固定机型。
- 录屏与隐私是赛题红线 → 端侧处理、明示采集状态、可一键删除。
- "端云协同"必须可讲清：端侧负责隐私与低延迟，云端负责生成与深度推理，断网仍可用。
- 待确认：演示场景是否收窄为"自学技术文档 / 网课"单一场景（建议收窄）。

### 双重否定修复（2026-11-20 已实现并测试）

- 问题：否定类规则（允许→不允许、复制→不复制、保证→无法保证 等）是纯子串替换，原文已含否定词时产生「不不允许」「无法无法保证」式双重否定（如 `系统不允许日志复制` → `系统不不允许日志不不复制`）。这类干扰项一眼假，用户无需理解内容即可排除，污染「隐性盲区」测量；且旧 `verify` 会接受这类伪造记录。
- 修复（端云同构，`quiz/CounterfactualMutator.kt` + `server/quiz_logic.py`）：
  - 新增 `NEGATION_STARTS`（不/无/未/非）与 `NEGATION_PREFIXES`（无法/不能/没有/不必/无需/未能/不会/不可 + 单字 不/无/未/非/没）。
  - 改写结果以否定词开头时，跳过前文以否定前缀结尾的命中；按 preferLast 偏好顺序扫描全部命中、取第一个安全位置，同句另有未否定命中时不损失覆盖率。
  - `verify` / `verify_mutation` 同步加守卫：双重否定伪造记录现在会被拒绝。
  - 服务端 `SYSTEM_PROMPT` 增加第 7 条：禁止 LLM 对已否定位置做否定类改写。
- 验证（均已实际执行）：
  - JVM：`CounterfactualMutatorTest` 新增 3 例（已否定词跳过、改用其他命中、拒绝伪造且接受合法记录），3→6；`:app:testDebugUnitTest` 12/12 通过，BUILD SUCCESSFUL。
  - 服务端：`test_quiz_logic.py` 新增双重否定断言，全部通过。
  - mock 现有 6 条 mutation 仍全部通过 verify；离线评测报告与修复前逐字节一致（10/10 出题、20/20 改写、0 校验失败），无回归。
- 影响：旧会话若已生成过双重否定干扰项，重跑 M5 即被新题替换。
- 重构建（2026-11-20）：`:app:assembleDebug` 成功（17s，增量），APK 已更新到 `dist/MindGap-debug.apk`（105,503,514 B，sha256 `a149ce2f…05d9`）；已确认 classes6.dex 含新增否定前缀词表（未能/不会/不可 等），修复在包内。
