package com.mindgap.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.TimelineSession
import java.util.Locale

private enum class Tab(val label: String, val icon: ImageVector) {
    SESSION("采集", Icons.Filled.Home),
    REPLAY("回放", Icons.AutoMirrored.Filled.List),
    CHALLENGE("找茬", Icons.Filled.Star),
    ARCHIVE("档案", Icons.Filled.Person)
}

@Composable
fun MindGapApp(session: TimelineSession) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(Tab.SESSION.name) }
    var showMockSession by rememberSaveable { mutableStateOf(false) }
    val onboardingPrefs = context.getSharedPreferences("mindgap", Context.MODE_PRIVATE)
    var showOnboarding by rememberSaveable {
        mutableStateOf(!onboardingPrefs.getBoolean("onboarding_done", false))
    }
    val current = Tab.entries.first { it.name == currentTab }

    // M3 生成的真实时间轴优先；应用重启后恢复最近一次产物
    val alignment by AlignmentPipeline.state.collectAsState()
    LaunchedEffect(Unit) { AlignmentPipeline.restore(context) }
    val activeSession = alignment.session ?: session

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { currentTab = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showMockSession) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showMockSession = false }) {
                            Text("← 返回采集")
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SessionScreen(
                            session = session,
                            onOpenReplay = {
                                showMockSession = false
                                currentTab = Tab.REPLAY.name
                            }
                        )
                    }
                }
            } else {
                when (current) {
                    Tab.SESSION -> CaptureScreen(
                        onOpenMockSession = { showMockSession = true },
                        onOpenTimeline = {
                            showMockSession = false
                            currentTab = Tab.REPLAY.name
                        },
                        onOpenChallenge = {
                            showMockSession = false
                            currentTab = Tab.CHALLENGE.name
                        }
                    )
                    Tab.REPLAY -> ReplayScreen(
                        session = activeSession,
                        generated = alignment.session != null
                    )
                    Tab.CHALLENGE -> ChallengeScreen(
                        activeSession,
                        generated = alignment.session != null
                    )
                    Tab.ARCHIVE -> ArchiveScreen(
                        session = activeSession,
                        generated = alignment.session != null,
                        onOpenSession = {
                            showMockSession = false
                            currentTab = Tab.REPLAY.name
                        }
                    )
                }
            }
        }
    }

    if (showOnboarding) {
        OnboardingDialog(
            onDismiss = {
                onboardingPrefs.edit().putBoolean("onboarding_done", true).apply()
                showOnboarding = false
            }
        )
    }
}

/** 相对会话开始的毫秒偏移 → mm:ss */
fun Long.asClock(): String {
    val totalSeconds = this / 1000
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

fun StuckKind.asLabel(): String = when (this) {
    StuckKind.TIME_ANOMALY -> "时间异常"
    StuckKind.BEHAVIOR_ANOMALY -> "行为异常"
    StuckKind.SEMANTIC_ANOMALY -> "语义异常"
}

fun ActionType.asLabel(): String = when (this) {
    ActionType.NONE -> "无"
    ActionType.SCROLL -> "滚动"
    ActionType.CLICK -> "点击"
    ActionType.SWITCH_APP -> "切换应用"
    ActionType.SEARCH -> "搜索"
    ActionType.ERASE -> "删除"
    ActionType.TYPE_TEXT -> "输入"
    ActionType.IDLE -> "静默"
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun Tag(text: String, accent: Boolean = false, warn: Boolean = false) {
    val container = when {
        warn -> MaterialTheme.colorScheme.errorContainer
        accent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        warn -> MaterialTheme.colorScheme.onErrorContainer
        accent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
