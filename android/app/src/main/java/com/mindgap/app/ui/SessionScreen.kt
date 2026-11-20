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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.SourceKind
import com.mindgap.app.data.model.TimelineSession

@Composable
fun SessionScreen(
    session: TimelineSession,
    onOpenReplay: () -> Unit,
    onConsentChange: ((Boolean) -> Unit)? = null
) {
    // null 视为未授权：只有显式 granted 才算授权，避免缺字段被误读为同意上传
    var consentGranted by rememberSaveable {
        mutableStateOf(session.capture.consent == Consent.GRANTED)
    }
    val duration = session.frames.lastOrNull()?.t ?: 0L

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(session.document.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${session.capture.startedAt} · 总时长 ${duration.asClock()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard("本次会话") {
                StatRow("会话 ID", session.sessionId.take(8))
                StatRow(
                    "设备",
                    session.device?.let { "${it.model} · API ${it.androidApi}" } ?: "未记录"
                )
                StatRow(
                    "采集通道",
                    session.capture.sources.joinToString(" + ") { it.asLabel() }
                )
                StatRow("时间桶", "${session.capture.bucketMs} ms")
                StatRow("总帧数", "${session.frames.size}")
                StatRow("文档段落", "${session.document.paragraphs.size}")
            }
        }

        item {
            SectionCard("采集授权") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (consentGranted) "已授权采集与上传" else "已撤回：数据不出设备",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (onConsentChange == null) {
                                "示例会话：授权开关不可用，真实会话请在采集页设置"
                            } else {
                                "录制期间常驻状态提示，可随时暂停或删除"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = consentGranted,
                        enabled = onConsentChange != null,
                        onCheckedChange = { checked ->
                            consentGranted = checked
                            onConsentChange?.invoke(checked)
                        }
                    )
                }
            }
        }

        item {
            SectionCard("卡壳概览") {
                if (session.stuckEvents.isEmpty()) {
                    Text(
                        "本次会话未检出卡壳事件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    session.stuckEvents.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(event.kind.asLabel(), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${event.fromMs.asClock()} · ${event.paragraphId ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${(event.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = onOpenReplay, modifier = Modifier.fillMaxWidth()) {
                Text("查看时间轴回放")
            }
        }

        session.notes?.let { notes ->
            item {
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun SourceKind.asLabel(): String = when (this) {
    SourceKind.SCREEN -> "屏幕"
    SourceKind.ACCESSIBILITY -> "无障碍"
    SourceKind.AUDIO -> "音频"
}
