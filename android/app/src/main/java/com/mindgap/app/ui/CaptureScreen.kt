package com.mindgap.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.align.AlignmentSnapshot
import com.mindgap.app.align.AlignmentStage
import com.mindgap.app.capture.CapturePhase
import com.mindgap.app.capture.CaptureService
import com.mindgap.app.capture.CaptureSession
import com.mindgap.app.capture.RawEvent
import com.mindgap.app.capture.RawEventType
import com.mindgap.app.capture.ScreenTextAccessibilityService
import com.mindgap.app.capture.loadRawEvents
import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.QuizMode
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.judge.JudgementPipeline
import com.mindgap.app.judge.JudgementSnapshot
import com.mindgap.app.judge.JudgementStage
import com.mindgap.app.perception.ModelManager
import com.mindgap.app.perception.ModelPhase
import com.mindgap.app.perception.ModelSnapshot
import com.mindgap.app.perception.PerceptionPipeline
import com.mindgap.app.perception.PerceptionStage
import com.mindgap.app.privacy.SessionDataManager
import com.mindgap.app.quiz.QuizPipeline
import com.mindgap.app.quiz.QuizSnapshot
import com.mindgap.app.quiz.QuizStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun CaptureScreen(
    onOpenMockSession: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenChallenge: () -> Unit
) {
    val context = LocalContext.current
    val snapshot by CaptureSession.state.collectAsState()
    val modelState by ModelManager.state.collectAsState()
    val perception by PerceptionPipeline.state.collectAsState()
    val alignment by AlignmentPipeline.state.collectAsState()
    val judgement by JudgementPipeline.state.collectAsState()
    val quiz by QuizPipeline.state.collectAsState()
    val alignmentDone = alignment.session?.takeIf { it.sessionId == snapshot.sessionId }
    val scope = rememberCoroutineScope()
    var footprint by remember { mutableStateOf<SessionDataManager.Footprint?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var privacyMessage by remember { mutableStateOf("") }
    var permissionRefresh by remember { mutableStateOf(0) }
    val pipelinesRunning = perception.stage == PerceptionStage.RUNNING ||
        alignment.stage == AlignmentStage.RUNNING ||
        judgement.stage == JudgementStage.RUNNING ||
        quiz.stage == QuizStage.RUNNING

    // 足迹统计随采集阶段与各流水线进度刷新：M2 落盘 perception.json、
    // M4/M5 原地更新时间轴都会改变占用，不能只等 sessionId/授权变化
    LaunchedEffect(
        snapshot.sessionId,
        snapshot.phase,
        alignmentDone?.capture?.consent,
        perception.stage,
        alignment.stage,
        judgement.stage,
        quiz.stage
    ) {
        val sessionId = snapshot.sessionId
        footprint = if (sessionId == null) {
            null
        } else {
            runCatching { SessionDataManager.footprint(context, sessionId) }.getOrNull()
        }
    }

    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var message by remember { mutableStateOf("") }
    var replayEvents by remember { mutableStateOf<List<RawEvent>>(emptyList()) }

    LaunchedEffect(Unit) {
        ModelManager.refresh(context)
    }

    LaunchedEffect(snapshot.phase) {
        accessibilityEnabled = isAccessibilityEnabled(context)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
            message = ""
        } else {
            message = "未获得屏幕录制授权，采集未开始"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 只申请了部分权限时，结果表里没有 RECORD_AUDIO，必须回查真实授权状态
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        message = if (audioGranted) "" else "未授权麦克风，本次只采集屏幕与文本"
        permissionRefresh++
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    val startCapture: () -> Unit = {
        accessibilityEnabled = isAccessibilityEnabled(context)
        if (!accessibilityEnabled) {
            message = "未开启无障碍服务：仍可采集屏幕指纹与语音，但拿不到文字"
        }
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Column {
                Text("采集会话", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "采集 → 识别 → 对齐 → 判定 → 找茬，全部在本机完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            SectionCard("当前状态") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(phaseLabel(snapshot.phase), accent = snapshot.phase == CapturePhase.RUNNING)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        snapshot.elapsedMs.asClock(),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatRow("屏幕文本", "${snapshot.screenTextCount} 条")
                StatRow("画面指纹", "${snapshot.screenFrameCount} 帧")
                StatRow("关键帧", "${snapshot.keyframeCount} 张")
                StatRow("语音段", "${snapshot.audioSegmentCount} 段")
                StatRow("交互动作", "${snapshot.actionCount} 次")
                if (snapshot.lastEvent.isNotEmpty()) {
                    Text(
                        snapshot.lastEvent,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (snapshot.message.isNotEmpty()) {
                    Text(
                        snapshot.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            SectionCard("端侧闭环") {
                val perceptionDone = perception.stage == PerceptionStage.DONE &&
                    perception.sessionId == snapshot.sessionId
                val perceptionFailed = perception.stage == PerceptionStage.FAILED &&
                    perception.sessionId == snapshot.sessionId
                val judgementDone =
                    (judgement.stage == JudgementStage.DONE && judgement.sessionId == snapshot.sessionId) ||
                        alignmentDone?.stuckEvents?.isNotEmpty() == true
                val quizDone =
                    (quiz.stage == QuizStage.DONE && quiz.sessionId == snapshot.sessionId) ||
                        alignmentDone?.quizItems?.isNotEmpty() == true

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Tag("采集", accent = snapshot.phase == CapturePhase.FINISHED)
                    Tag("识别", accent = perceptionDone, warn = perceptionFailed)
                    Tag(
                        "对齐",
                        accent = alignmentDone != null,
                        warn = alignment.stage == AlignmentStage.FAILED &&
                            alignment.sessionId == snapshot.sessionId
                    )
                    Tag(
                        "判定",
                        accent = judgementDone,
                        warn = judgement.stage == JudgementStage.FAILED &&
                            judgement.sessionId == snapshot.sessionId
                    )
                    Tag(
                        "找茬",
                        accent = quizDone,
                        warn = quiz.stage == QuizStage.FAILED &&
                            quiz.sessionId == snapshot.sessionId
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        snapshot.phase != CapturePhase.FINISHED ->
                            "先完成一次采集，再依次运行识别、对齐、判定与找茬。"
                        alignmentDone == null ->
                            "下一步：运行 M2 离线转写与 OCR，然后生成时间轴（M3）。"
                        alignmentDone.stuckEvents.isEmpty() ->
                            "下一步：运行 M4 卡壳判定。"
                        alignmentDone.quizItems.isEmpty() ->
                            "下一步：运行 M5 生成三选项找茬题。"
                        else ->
                            "闭环完成：去回放看证据，或去挑战页作答。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (snapshot.phase) {
                    CapturePhase.IDLE, CapturePhase.FINISHED -> {
                        Button(onClick = startCapture, modifier = Modifier.weight(1f)) {
                            Text("开始采集")
                        }
                    }
                    CapturePhase.RUNNING -> {
                        Button(
                            onClick = { sendAction(context, CaptureService.ACTION_PAUSE) },
                            modifier = Modifier.weight(1f)
                        ) { Text("暂停") }
                        OutlinedButton(
                            onClick = { sendAction(context, CaptureService.ACTION_STOP) },
                            modifier = Modifier.weight(1f)
                        ) { Text("结束") }
                    }
                    CapturePhase.PAUSED -> {
                        Button(
                            onClick = { sendAction(context, CaptureService.ACTION_RESUME) },
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                        OutlinedButton(
                            onClick = { sendAction(context, CaptureService.ACTION_STOP) },
                            modifier = Modifier.weight(1f)
                        ) { Text("结束") }
                    }
                }
            }
        }

        if (message.isNotEmpty()) {
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            SectionCard("权限") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (accessibilityEnabled) "无障碍服务已开启" else "无障碍服务未开启",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "首选文本通道；关闭时只能拿到画面指纹",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            accessibilityEnabled = isAccessibilityEnabled(context)
                            permissionRefresh++
                            if (!accessibilityEnabled) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    ) { Text(if (accessibilityEnabled) "刷新" else "去开启") }
                }

                val micGranted = remember(permissionRefresh) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                }
                val notificationGranted = remember(permissionRefresh) {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                }
                Spacer(Modifier.height(8.dp))
                StatRow("麦克风", if (micGranted) "已授权" else "未授权 · 仅屏幕与文本")
                StatRow("通知", if (notificationGranted) "已授权" else "未授权 · 前台通知不显示")
                StatRow("屏幕录制", "每次开始由系统弹窗授权")
            }
        }

        item {
            SectionCard("端侧感知（M2）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        modelLabel(modelState),
                        accent = modelState.phase == ModelPhase.READY,
                        warn = modelState.phase == ModelPhase.FAILED
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Paraformer int8 + Silero VAD + ML Kit 中文 OCR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))

                when (modelState.phase) {
                    ModelPhase.MISSING, ModelPhase.FAILED -> {
                        Text(
                            modelState.message.ifEmpty { "首次使用需下载约 75 MB 模型" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { ModelManager.downloadAsync(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("下载离线模型（约 75 MB）") }
                    }
                    ModelPhase.DOWNLOADING, ModelPhase.EXTRACTING -> {
                        LinearProgressIndicator(
                            progress = { modelState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            modelState.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ModelPhase.READY -> {
                        Text(
                            "模型已就绪：转写与 OCR 全部在设备本地完成，原始音频与截图不出设备。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val id = snapshot.sessionId
                        if (id != null) PerceptionPipeline.start(context, id)
                    },
                    enabled = snapshot.phase == CapturePhase.FINISHED &&
                        snapshot.sessionId != null &&
                        perception.stage != PerceptionStage.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("运行离线转写与 OCR") }
                if (snapshot.phase != CapturePhase.FINISHED) {
                    Text(
                        "结束一次采集后可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (perception.stage == PerceptionStage.RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { perception.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${perception.step} · ${(perception.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (perception.stage == PerceptionStage.FAILED) {
                    Text(
                        perception.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                val result = perception.result?.takeIf { it.sessionId == snapshot.sessionId }
                if (result != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("感知结果", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    StatRow("语音段", "${result.speech.size} 段 / ${result.stats.speechChars} 字")
                    StatRow("OCR 帧", "${result.stats.ocrFrames} 帧（重复 ${result.stats.ocrDuplicates}）")
                    StatRow(
                        "关键帧",
                        "${result.stats.keyframes}/${result.stats.sampledFrames}" +
                            "（去重 ${(result.stats.frameDedupRatio * 100).toInt()}%）"
                    )
                    StatRow("耗时", "${result.stats.elapsedMs / 1000} s")

                    result.speech.take(3).forEach { transcript ->
                        Text(
                            "${transcript.startMs.asClock()} ${transcript.text}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    result.ocr.filterNot { it.isDuplicate }.take(2).forEach { frame ->
                        Text(
                            "${frame.t.asClock()} ${frame.text.take(60).replace('\n', ' ')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    result.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            SectionCard("时空对齐（M3）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        alignmentLabel(alignment, snapshot.sessionId, alignmentDone != null),
                        accent = alignmentDone != null,
                        warn = alignment.stage == AlignmentStage.FAILED &&
                            alignment.sessionId == snapshot.sessionId
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "屏幕 + 语音 + 操作 → 500ms 时间桶",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "把三路数据聚合成可回放时间轴；停留与回看在此计算，卡壳判定留给 M4。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (perception.stage != PerceptionStage.DONE) {
                    Text(
                        "未运行 M2：本次只对齐屏幕与操作，语音通道为空。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val id = snapshot.sessionId
                        if (id != null) {
                            AlignmentPipeline.start(context, id, snapshot.startedAtWallClock)
                        }
                    },
                    enabled = snapshot.phase == CapturePhase.FINISHED &&
                        snapshot.sessionId != null &&
                        alignment.stage != AlignmentStage.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("生成注意力时间轴") }
                if (snapshot.phase != CapturePhase.FINISHED) {
                    Text(
                        "结束一次采集后可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (alignment.stage == AlignmentStage.RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { alignment.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${alignment.step} · ${(alignment.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (alignment.stage == AlignmentStage.FAILED) {
                    Text(
                        alignment.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                val timeline = alignmentDone
                if (timeline != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("时间轴摘要", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    StatRow("覆盖时长", (timeline.frames.lastOrNull()?.t ?: 0L).asClock())
                    StatRow("帧数", "${timeline.frames.size}")
                    StatRow("段落", "${timeline.document.paragraphs.size}")
                    StatRow("有屏幕", "${timeline.frames.count { it.screen != null }} 帧")
                    StatRow("有语音", "${timeline.frames.count { it.speech != null }} 帧")
                    StatRow(
                        "有操作",
                        "${timeline.frames.count { it.action.type != ActionType.NONE }} 帧"
                    )
                    Text(
                        "capture/${timeline.sessionId.take(8)}….timeline.json",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    timeline.notes?.let { notes ->
                        Text(
                            notes,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenTimeline, modifier = Modifier.fillMaxWidth()) {
                        Text("去回放（看哪 / 说什么 / 点什么）")
                    }
                }
            }
        }

        item {
            SectionCard("卡壳判定（M4）") {
                val judged = judgement.takeIf {
                    it.sessionId != null && it.sessionId == snapshot.sessionId
                }
                val hasEvents = alignmentDone?.stuckEvents?.isNotEmpty() == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        judgementLabel(judged, hasEvents),
                        accent = judged?.stage == JudgementStage.DONE || hasEvents,
                        warn = judged?.stage == JudgementStage.FAILED
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "时间 / 行为 / 语义 三类可解释规则",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "前 5 分钟静默校准（短会话按前段校准）；事件必须带证据帧，判定只读时间轴特征。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { alignmentDone?.let { JudgementPipeline.start(context, it) } },
                    enabled = alignmentDone != null && judgement.stage != JudgementStage.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("运行卡壳判定") }
                if (alignmentDone == null) {
                    Text(
                        "先生成时间轴（M3）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (judged != null && judged.stage == JudgementStage.RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { judged.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${judged.step} · ${(judged.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (judged != null && judged.stage == JudgementStage.FAILED) {
                    Text(
                        judged.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                val report = judged?.report
                if (report != null) {
                    Spacer(Modifier.height(10.dp))
                    StatRow("基线 P90", secondsLabel(report.baselineP90Ms))
                    StatRow("基线 P50", secondsLabel(report.baselineP50Ms))
                    StatRow("校准窗口", "0–${secondsLabel(report.warmupEndMs)}")
                    StatRow("时间阈值", secondsLabel(report.timeThresholdMs))
                    StatRow("校准样本", "${report.calibrationSamples} 次停留")
                }

                val events = alignmentDone?.stuckEvents.orEmpty()
                if (alignmentDone != null && (events.isNotEmpty() || judged?.stage == JudgementStage.DONE)) {
                    Spacer(Modifier.height(10.dp))
                    val timeCount = events.count { it.kind == StuckKind.TIME_ANOMALY }
                    val behaviorCount = events.count { it.kind == StuckKind.BEHAVIOR_ANOMALY }
                    val semanticCount = events.count { it.kind == StuckKind.SEMANTIC_ANOMALY }
                    StatRow(
                        "事件",
                        "共 ${events.size}（时间 $timeCount / 行为 $behaviorCount / 语义 $semanticCount）"
                    )
                    if (events.isEmpty()) {
                        Text(
                            "本次会话未检出卡壳事件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        events.take(5).forEach { event ->
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Tag(
                                        event.kind.asLabel(),
                                        accent = event.kind == StuckKind.TIME_ANOMALY,
                                        warn = event.kind == StuckKind.SEMANTIC_ANOMALY
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${event.fromMs.asClock()} · ${event.paragraphId ?: "-"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${(event.confidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                event.summary?.let { summary ->
                                    Text(
                                        summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenTimeline, modifier = Modifier.fillMaxWidth()) {
                            Text("去回放看证据")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("反事实找茬（M5）") {
                val quizForSession = quiz.takeIf {
                    it.sessionId != null && it.sessionId == snapshot.sessionId
                }
                val quizzes = alignmentDone?.quizItems.orEmpty()
                val hasStuckEvents = alignmentDone?.stuckEvents?.isNotEmpty() == true
                val consentGranted = alignmentDone?.capture?.consent == Consent.GRANTED
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        quizLabel(quizForSession, quizzes.isNotEmpty()),
                        accent = quizForSession?.stage == QuizStage.DONE || quizzes.isNotEmpty(),
                        warn = quizForSession?.stage == QuizStage.FAILED
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "三选项 · 逐项回指证据 · 在线/离线",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "把卡壳段原文改出两处反事实陷阱：一项逐字保留原文，另外两项各改一个词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { alignmentDone?.let { QuizPipeline.start(context, it) } },
                    enabled = hasStuckEvents &&
                        quiz.stage != QuizStage.RUNNING &&
                        judgement.stage != JudgementStage.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("离线生成反事实找茬题") }
                if (!hasStuckEvents) {
                    Text(
                        "先运行 M4 产生卡壳事件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { alignmentDone?.let { QuizPipeline.startOnline(context, it) } },
                    enabled = hasStuckEvents &&
                        consentGranted &&
                        quiz.stage != QuizStage.RUNNING &&
                        judgement.stage != JudgementStage.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("云端生成（需授权）") }
                if (alignmentDone != null && !consentGranted) {
                    Text(
                        "先在「数据与隐私」中打开上传授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (quizForSession != null && quizForSession.stage == QuizStage.RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { quizForSession.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${quizForSession.step} · ${(quizForSession.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (quizForSession != null && quizForSession.stage == QuizStage.FAILED) {
                    Text(
                        quizForSession.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (quizzes.isNotEmpty() || quizForSession?.stage == QuizStage.DONE) {
                    Spacer(Modifier.height(10.dp))
                    StatRow("题目", "${quizzes.size} 题")
                    val onlineCount = quizzes.count { it.mode == QuizMode.ONLINE }
                    StatRow(
                        "来源",
                        if (onlineCount > 0) {
                            "在线 $onlineCount / 离线 ${quizzes.size - onlineCount}"
                        } else {
                            "离线模板"
                        }
                    )
                    if (quizForSession != null) {
                        StatRow("跳过事件", "${quizForSession.skipped}")
                    }
                    quizzes.take(3).forEach { item ->
                        Text(
                            "${item.quizId} · ${item.stem}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (quizzes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenChallenge, modifier = Modifier.fillMaxWidth()) {
                            Text("去答题")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("数据与隐私（M6）") {
                val uploadGranted = alignmentDone?.capture?.consent == Consent.GRANTED
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("本地优先", accent = true)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (uploadGranted) {
                            "云端生成已授权：仅上传压缩文本片段与摘要"
                        } else {
                            "未授权上传：当前所有数据不出设备"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "原始事件、音频、关键帧与时间轴保存在应用私有目录；卸载或删除后不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showPrivacy = true }) {
                    Text("查看数据边界说明")
                }
                Spacer(Modifier.height(4.dp))

                val timeline = alignmentDone
                if (timeline != null) {
                    val granted = timeline.capture.consent == Consent.GRANTED
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "允许云端生成（上传压缩片段）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (granted) {
                                    "已授权：未来云端生成可上传压缩后的片段"
                                } else {
                                    "未授权：所有数据只留在本机"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = granted,
                            enabled = !pipelinesRunning,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    runCatching {
                                        val updated = SessionDataManager.updateConsent(
                                            context,
                                            timeline,
                                            checked
                                        )
                                        AlignmentPipeline.updateSession(updated)
                                    }.onSuccess {
                                        privacyMessage = ""
                                    }.onFailure {
                                        privacyMessage =
                                            "授权保存失败：${it.message ?: it.javaClass.simpleName}"
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Text(
                        "生成时间轴后可设置上传授权。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (snapshot.sessionId != null) {
                    Spacer(Modifier.height(10.dp))
                    StatRow(
                        "本次数据",
                        "${footprint?.files ?: 0} 个文件 · ${formatBytes(footprint?.bytes ?: 0L)}"
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        enabled = snapshot.phase != CapturePhase.RUNNING &&
                            snapshot.phase != CapturePhase.PAUSED &&
                            !pipelinesRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除本次会话数据") }
                    if (snapshot.phase == CapturePhase.RUNNING || snapshot.phase == CapturePhase.PAUSED) {
                        Text(
                            "请先结束采集",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else if (pipelinesRunning) {
                        Text(
                            "流程运行中，稍后再删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    Text(
                        "本次会话已删除或尚未开始采集",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (privacyMessage.isNotEmpty()) {
                    Text(
                        privacyMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        snapshot.eventFile?.let { path ->
            item {
                SectionCard("落盘位置") {
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    snapshot.audioFile?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (snapshot.phase == CapturePhase.FINISHED) {
            item {
                Button(
                    onClick = {
                        val id = snapshot.sessionId
                        if (id != null) {
                            scope.launch {
                                replayEvents = withContext(Dispatchers.IO) {
                                    loadRawEvents(context, id)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("加载本次原始事件（${snapshot.screenTextCount + snapshot.screenFrameCount + snapshot.audioSegmentCount + snapshot.actionCount} 条）") }
            }
        }

        if (replayEvents.isNotEmpty()) {
            item {
                Text(
                    "原始事件回放（前 60 条）",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(replayEvents.take(60)) { event ->
                RawEventRow(event)
            }
        }

        item {
            TextButton(onClick = onOpenMockSession) {
                Text("查看假数据示例（时间轴 / 找茬 / 档案）")
            }
        }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除本次会话数据？") },
                text = {
                    Text("将删除原始事件、PCM、关键帧、感知结果与时间轴，且不可恢复。已下载的离线模型会保留。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            val sessionId = snapshot.sessionId
                            if (sessionId != null) {
                                scope.launch {
                                    runCatching {
                                        SessionDataManager.deleteSession(context, sessionId)
                                    }.onSuccess {
                                        privacyMessage = ""
                                    }.onFailure {
                                        privacyMessage =
                                            "删除失败：${it.message ?: it.javaClass.simpleName}"
                                    }
                                }
                            }
                        },
                        enabled = !pipelinesRunning
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        if (showPrivacy) {
            OnboardingDialog(onDismiss = { showPrivacy = false })
        }
    }
}

@Composable
private fun RawEventRow(event: RawEvent) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.t.asClock(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Text(
                rawTypeLabel(event.type),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val detail = event.text
            ?: event.note
            ?: event.frameHash?.let { "指纹 $it" }
            ?: event.appPackage
            ?: event.startMs?.let { "语音段 ${it / 1000.0}s" }
            ?: ""
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun rawTypeLabel(type: RawEventType): String = when (type) {
    RawEventType.SCREEN_TEXT -> "屏幕文本"
    RawEventType.SCREEN_FRAME -> "画面"
    RawEventType.AUDIO_START -> "音频起点"
    RawEventType.AUDIO_SEGMENT -> "语音"
    RawEventType.APP_SWITCH -> "切应用"
    RawEventType.SCROLL -> "滚动"
    RawEventType.CLICK -> "点击"
    RawEventType.SEARCH -> "搜索"
    RawEventType.ERASE -> "删除改写"
    RawEventType.MARKER -> "标记"
}

private fun modelLabel(snapshot: ModelSnapshot): String = when (snapshot.phase) {
    ModelPhase.MISSING -> "模型未下载"
    ModelPhase.DOWNLOADING -> "下载中 ${(snapshot.progress * 100).toInt()}%"
    ModelPhase.EXTRACTING -> "解压中"
    ModelPhase.READY -> "模型就绪"
    ModelPhase.FAILED -> "下载失败"
}

private fun alignmentLabel(
    snapshot: AlignmentSnapshot,
    currentSessionId: String?,
    hasResult: Boolean
): String = when {
    hasResult -> "已生成"
    snapshot.stage == AlignmentStage.RUNNING && snapshot.sessionId == currentSessionId ->
        "对齐中 ${(snapshot.progress * 100).toInt()}%"
    snapshot.stage == AlignmentStage.FAILED && snapshot.sessionId == currentSessionId -> "对齐失败"
    else -> "未生成"
}

private fun judgementLabel(snapshot: JudgementSnapshot?, hasEvents: Boolean): String = when {
    snapshot != null && snapshot.stage == JudgementStage.RUNNING ->
        "判定中 ${(snapshot.progress * 100).toInt()}%"
    snapshot != null && snapshot.stage == JudgementStage.FAILED -> "判定失败"
    snapshot != null && snapshot.stage == JudgementStage.DONE -> "已判定"
    hasEvents -> "已判定"
    else -> "未判定"
}

private fun quizLabel(snapshot: QuizSnapshot?, hasQuizzes: Boolean): String = when {
    snapshot != null && snapshot.stage == QuizStage.RUNNING ->
        "生成中 ${(snapshot.progress * 100).toInt()}%"
    snapshot != null && snapshot.stage == QuizStage.FAILED -> "生成失败"
    snapshot != null && snapshot.stage == QuizStage.DONE -> "已生成"
    hasQuizzes -> "已生成"
    else -> "未生成"
}

private fun secondsLabel(ms: Long): String = "${ms / 1000}s"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun phaseLabel(phase: CapturePhase): String = when (phase) {
    CapturePhase.IDLE -> "空闲"
    CapturePhase.RUNNING -> "采集中"
    CapturePhase.PAUSED -> "已暂停"
    CapturePhase.FINISHED -> "已结束"
}

private fun sendAction(context: Context, action: String) {
    runCatching {
        context.startService(Intent(context, CaptureService::class.java).setAction(action))
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val globalEnabled = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0
    )
    if (globalEnabled != 1) return false
    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val target = ScreenTextAccessibilityService::class.java.name
    // 系统可能存全限定类名，也可能存 pkg/.Class 简写
    val shorthand = "." + target.removePrefix(context.packageName + ".")
    return services.split(':').any { entry ->
        entry == target || entry.endsWith(target) || entry.endsWith(shorthand)
    }
}
