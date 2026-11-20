package com.mindgap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.align.AlignmentStage
import com.mindgap.app.align.TimelineStore
import com.mindgap.app.archive.ArchiveRepository
import com.mindgap.app.archive.ArchiveSummary
import com.mindgap.app.archive.ArchivedSession
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.judge.JudgementPipeline
import com.mindgap.app.judge.JudgementStage
import com.mindgap.app.perception.PerceptionPipeline
import com.mindgap.app.perception.PerceptionStage
import com.mindgap.app.privacy.SessionDataManager
import com.mindgap.app.quiz.QuizPipeline
import com.mindgap.app.quiz.QuizStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArchiveScreen(
    session: TimelineSession,
    generated: Boolean = false,
    onOpenSession: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ArchivedSession?>(null) }
    var message by remember { mutableStateOf("") }

    val summary by produceState<ArchiveSummary?>(null, refreshKey, session.sessionId) {
        value = ArchiveRepository.load(context)
    }

    val perception by PerceptionPipeline.state.collectAsState()
    val alignment by AlignmentPipeline.state.collectAsState()
    val judgement by JudgementPipeline.state.collectAsState()
    val quiz by QuizPipeline.state.collectAsState()
    val pipelinesRunning = perception.stage == PerceptionStage.RUNNING ||
        alignment.stage == AlignmentStage.RUNNING ||
        judgement.stage == JudgementStage.RUNNING ||
        quiz.stage == QuizStage.RUNNING

    val duration = session.frames.lastOrNull()?.t ?: 0L
    val lastProgress = session.frames.lastOrNull { it.screen?.progress != null }?.screen?.progress

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text("成长档案", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        session.document.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!generated) {
                        Text(
                            "「本次学习」展示的是假数据示例；跨会话累计仍来自本机真实记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("会话", "1", Modifier.weight(1f))
                    MetricCard("卡壳", "${session.stuckEvents.size}", Modifier.weight(1f))
                    MetricCard("题目", "${session.quizItems.size}", Modifier.weight(1f))
                }
            }

            item {
                SectionCard("本次学习") {
                    StatRow("总时长", duration.asClock())
                    StatRow("帧数", "${session.frames.size}")
                    StatRow("阅读进度", lastProgress?.let { "${(it * 100).toInt()}%" } ?: "未记录")
                    StatRow("上传授权", if (session.capture.consent == Consent.GRANTED) {
                        "已授权（未来云端生成）"
                    } else {
                        "未授权（数据不出设备）"
                    })
                    val currentArchive = summary?.sessions?.firstOrNull {
                        it.sessionId == session.sessionId
                    }
                    StatRow(
                        "已答 / 隐性盲区",
                        if (currentArchive == null) "—" else {
                            "${currentArchive.answered} / ${currentArchive.hiddenBlind}"
                        }
                    )
                }
            }

            item {
                SectionCard("跨会话累计") {
                    val current = summary
                    if (current == null) {
                        Text(
                            "正在读取历史…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        StatRow("会话数", "${current.sessions.size}")
                        StatRow("累计时长", current.totalDurationMs.asClock())
                        StatRow("累计卡壳", "${current.totalStuck}")
                        StatRow("累计题目", "${current.totalQuizzes}")
                        StatRow("累计答题", "${current.totalAnswered}")
                        StatRow("隐性盲区", "${current.totalHiddenBlind} 次（高信心答错）")
                        StatRow(
                            "高信心错误率",
                            if (current.highConfidenceTotal == 0) "—" else {
                                "${current.totalHiddenBlind * 100 / current.highConfidenceTotal}%"
                            }
                        )
                        Text(
                            StuckKind.entries.joinToString(" · ") { kind ->
                                "${kind.asLabel()} ${current.byKind[kind] ?: 0}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (message.isNotEmpty()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                SectionCard("卡壳类型分布") {
                    val maxCount = StuckKind.entries
                        .maxOfOrNull { kind -> session.stuckEvents.count { it.kind == kind } }
                        ?.coerceAtLeast(1) ?: 1

                    StuckKind.entries.forEach { kind ->
                        val count = session.stuckEvents.count { it.kind == kind }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                kind.asLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(72.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                // fillMaxWidth 不接受 0，无事件的类型直接不画条
                                if (count > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(count.toFloat() / maxCount)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(20.dp)
                            )
                        }
                    }
                }
            }

            item {
                SectionCard("卡壳事件") {
                    if (session.stuckEvents.isEmpty()) {
                        Text(
                            "本次会话未检出卡壳事件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    session.stuckEvents.forEach { event ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    event.kind.asLabel(),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${event.fromMs.asClock()} – ${event.toMs.asClock()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            event.summary?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "${event.paragraphId ?: "-"} · 置信度 ${(event.confidence * 100).toInt()}% · 证据 ${event.evidenceFrameIds.size} 帧",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text("历史会话", style = MaterialTheme.typography.titleSmall)
            }
            if (pipelinesRunning) {
                item {
                    Text(
                        "流程运行中，暂不可删除历史会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val history = summary?.sessions.orEmpty()
            if (history.isEmpty()) {
                item {
                    Text(
                        "暂无历史会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(history, key = { it.sessionId }) { archived ->
                    ArchivedSessionRow(
                        archived = archived,
                        isCurrent = archived.sessionId == session.sessionId,
                        deleteEnabled = !pipelinesRunning,
                        onOpen = {
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) {
                                    TimelineStore.load(context, archived.sessionId)
                                }
                                if (loaded == null) {
                                    message = "历史会话读取失败，可能已被删除"
                                } else {
                                    message = ""
                                    AlignmentPipeline.open(loaded)
                                    onOpenSession()
                                }
                            }
                        },
                        onDelete = { pendingDelete = archived }
                    )
                }
            }
        }

        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除历史会话？") },
                text = {
                    Text("将删除「${target.title.ifBlank { "未命名文档" }}」的原始事件、音频、关键帧与时间轴，且不可恢复。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val sessionId = target.sessionId
                            pendingDelete = null
                            scope.launch {
                                runCatching { SessionDataManager.deleteSession(context, sessionId) }
                                    .onSuccess {
                                        message = ""
                                        refreshKey++
                                    }
                                    .onFailure {
                                        message = "删除失败：${it.message ?: it.javaClass.simpleName}"
                                        refreshKey++
                                    }
                            }
                        },
                        enabled = !pipelinesRunning
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun ArchivedSessionRow(
    archived: ArchivedSession,
    isCurrent: Boolean,
    deleteEnabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    archived.title.ifBlank { "未命名文档" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Tag(if (isCurrent) "当前" else "历史", accent = isCurrent)
            }
            Text(
                "${archived.startedAt.take(19).replace('T', ' ')} · " +
                    "${archived.durationMs.asClock()} · ${archived.frameCount} 帧",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "卡壳 ${archived.stuckCount} · 题目 ${archived.quizCount} · " +
                        "答题 ${archived.answered} · 隐性盲区 ${archived.hiddenBlind}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpen) {
                    Text("打开回放")
                }
                TextButton(onClick = onDelete, enabled = deleteEnabled) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
