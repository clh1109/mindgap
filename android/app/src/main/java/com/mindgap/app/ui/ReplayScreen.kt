package com.mindgap.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession

@Composable
fun ReplayScreen(session: TimelineSession, generated: Boolean = false) {
    var hideDuplicates by rememberSaveable { mutableStateOf(true) }

    val stuckByFrame = remember(session) {
        buildMap<String, StuckEvent> {
            session.stuckEvents.forEach { event ->
                event.evidenceFrameIds.forEach { frameId -> put(frameId, event) }
            }
        }
    }

    val visibleFrames = remember(session, hideDuplicates) {
        session.frames.filter { frame ->
            // 纯重复帧才隐藏；带语音或操作的重复帧必须保留，否则会漏掉「说什么/点什么」
            val pureDuplicate = hideDuplicates &&
                frame.screen?.isDuplicate == true &&
                frame.speech == null &&
                frame.action.type == ActionType.NONE
            !pureDuplicate
        }
    }

    // 重复帧不重复存正文；回放时向前回溯最近一次可见文本
    val screenTextByFrame = remember(session) {
        var lastText: String? = null
        session.frames.associate { frame ->
            val text = frame.screen?.text?.takeIf { it.isNotBlank() }
            if (text != null) lastText = text
            frame.frameId to if (frame.screen != null) lastText else null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(if (generated) "真实会话" else "假数据", accent = generated)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = hideDuplicates,
                    onClick = { hideDuplicates = !hideDuplicates },
                    label = { Text("隐藏重复帧") }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${visibleFrames.size} / ${session.frames.size} 帧",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${session.document.title} · ${session.capture.startedAt.take(19).replace('T', ' ')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibleFrames, key = { it.frameId }) { frame ->
                FrameRow(
                    frame = frame,
                    stuck = stuckByFrame[frame.frameId],
                    screenText = screenTextByFrame[frame.frameId]
                )
            }
        }
    }
}

@Composable
private fun FrameRow(frame: TimelineFrame, stuck: StuckEvent?, screenText: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    frame.t.asClock(),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    frame.screen?.paragraphId ?: frame.action.appPackage ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (frame.screen?.isDuplicate == true) {
                    Tag("重复")
                    Spacer(Modifier.width(4.dp))
                }
                stuck?.let { Tag(it.kind.asLabel(), accent = true) }
            }

            screenText?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            frame.speech?.text?.takeIf { it.isNotBlank() }?.let { speech ->
                Text(
                    "语音：$speech",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    "行为 ${frame.action.type.asLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                frame.action.query?.let { query ->
                    Text(
                        " · 搜索「$query」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                frame.features?.let { features ->
                    Text(
                        " · 停留 ${features.dwellMs / 1000}s / 回看 ${features.revisitCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
