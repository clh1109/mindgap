package com.mindgap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mindgap.app.data.model.QuizItem
import com.mindgap.app.data.model.QuizMode
import com.mindgap.app.data.model.QuizOption
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.data.model.Verdict
import com.mindgap.app.quiz.ConfidenceLevel
import com.mindgap.app.quiz.QuizAttempt
import com.mindgap.app.quiz.QuizAttemptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChallengeScreen(session: TimelineSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val frameById = remember(session) { session.frames.associateBy { it.frameId } }
    val eventById = remember(session) { session.stuckEvents.associateBy { it.eventId } }
    val answers = remember(session) { mutableStateMapOf<String, String>() }
    val confidenceByQuiz = remember(session) { mutableStateMapOf<String, ConfidenceLevel>() }

    // 切页回来时恢复已答与信心，避免重复记录把隐性盲区统计刷高
    LaunchedEffect(session.sessionId) {
        val saved = withContext(Dispatchers.IO) {
            runCatching { QuizAttemptStore.load(context, session.sessionId) }
                .getOrDefault(emptyList())
        }
        saved.forEach { attempt ->
            answers[attempt.quizId] = attempt.selectedOptionId
            confidenceByQuiz[attempt.quizId] = ConfidenceLevel.entries
                .firstOrNull { it.wire == attempt.confidence } ?: ConfidenceLevel.LOW
        }
    }

    fun selectedVerdict(quiz: QuizItem): Verdict? {
        val selected = answers[quiz.quizId] ?: return null
        return quiz.options.firstOrNull { it.optionId == selected }?.verdict
    }

    val answered = answers.size
    val correct = session.quizItems.count { selectedVerdict(it) == Verdict.CORRECT }
    val hiddenBlind = session.quizItems.count { quiz ->
        confidenceByQuiz[quiz.quizId] == ConfidenceLevel.HIGH &&
            selectedVerdict(quiz) == Verdict.DISTRACTOR
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("找茬挑战", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "先报告把握，再看选项。答错且高信心 = 一次隐性盲区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (session.quizItems.isNotEmpty()) {
            item {
                SectionCard("本次挑战") {
                    StatRow("题目", "${session.quizItems.size}")
                    StatRow("已答", "$answered / ${session.quizItems.size}")
                    StatRow(
                        "正确率",
                        if (answered == 0) "—" else "${correct * 100 / answered}%"
                    )
                    StatRow("隐性盲区", "$hiddenBlind 次（高信心答错）")
                }
            }
        }

        if (session.quizItems.isEmpty()) {
            item {
                Text(
                    "本次会话没有生成题目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(session.quizItems, key = { it.quizId }) { quiz ->
            QuizCard(
                quiz = quiz,
                event = eventById[quiz.eventId],
                frameById = frameById,
                confidence = confidenceByQuiz[quiz.quizId],
                selectedOptionId = answers[quiz.quizId],
                onConfidence = { level ->
                    if (confidenceByQuiz[quiz.quizId] == null) {
                        confidenceByQuiz[quiz.quizId] = level
                    }
                },
                onSelect = { optionId ->
                    if (answers[quiz.quizId] == null) {
                        answers[quiz.quizId] = optionId
                        val option = quiz.options.firstOrNull { it.optionId == optionId }
                        val attempt = QuizAttempt(
                            sessionId = session.sessionId,
                            quizId = quiz.quizId,
                            eventId = quiz.eventId,
                            confidence = (confidenceByQuiz[quiz.quizId] ?: ConfidenceLevel.LOW).wire,
                            selectedOptionId = optionId,
                            correct = option?.verdict == Verdict.CORRECT,
                            answeredAtWallClock = System.currentTimeMillis()
                        )
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { QuizAttemptStore.append(context, attempt) }
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun QuizCard(
    quiz: QuizItem,
    event: StuckEvent?,
    frameById: Map<String, TimelineFrame>,
    confidence: ConfidenceLevel?,
    selectedOptionId: String?,
    onConfidence: (ConfidenceLevel) -> Unit,
    onSelect: (String) -> Unit
) {
    val chosen = selectedOptionId?.let { id -> quiz.options.firstOrNull { it.optionId == id } }
    val answered = selectedOptionId != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                event?.let {
                    Tag(it.kind.asLabel(), accent = true)
                    Spacer(Modifier.width(4.dp))
                }
                Tag(if (quiz.mode == QuizMode.ONLINE) "在线生成" else "离线模板")
                quiz.difficulty?.let {
                    Spacer(Modifier.width(4.dp))
                    Tag("难度 $it")
                }
            }

            Text(
                quiz.stem,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 10.dp)
            )

            if (confidence == null) {
                Text(
                    "你对这一段有多确定？先选一个，再展开选项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onConfidence(ConfidenceLevel.HIGH) },
                        modifier = Modifier.weight(1f)
                    ) { Text("我很确定") }
                    OutlinedButton(
                        onClick = { onConfidence(ConfidenceLevel.LOW) },
                        modifier = Modifier.weight(1f)
                    ) { Text("不太确定") }
                }
                return@Column
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tag(
                    if (confidence == ConfidenceLevel.HIGH) "高信心" else "低信心",
                    accent = confidence == ConfidenceLevel.HIGH
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (answered) "已作答" else "请选择与原文完全一致的一句",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            quiz.options.forEach { option ->
                OptionRow(
                    option = option,
                    selectedOptionId = selectedOptionId,
                    onClick = { onSelect(option.optionId) }
                )
            }

            if (chosen != null) {
                val isCorrect = chosen.verdict == Verdict.CORRECT
                val highConfidence = confidence == ConfidenceLevel.HIGH
                val headline = when {
                    isCorrect && highConfidence -> "判断正确：这一段确实在掌握中"
                    isCorrect -> "判断正确：但这次是低信心，属于侥幸答对"
                    highConfidence -> "隐性盲区：你以为自己懂了"
                    else -> "已意识到不确定：这是显性卡点"
                }
                Text(
                    headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        isCorrect -> MaterialTheme.colorScheme.primary
                        highConfidence -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
                chosen.rationale?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                quiz.explanation?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Text(
                    "证据帧",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                chosen.evidenceFrameIds.forEach { frameId ->
                    val frame = frameById[frameId]
                    Text(
                        "${frame?.t?.asClock() ?: "--:--"} · $frameId · ${frame?.screen?.paragraphId ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    option: QuizOption,
    selectedOptionId: String?,
    onClick: () -> Unit
) {
    val answered = selectedOptionId != null
    val isChosen = selectedOptionId == option.optionId
    val container = when {
        !answered -> MaterialTheme.colorScheme.surface
        isChosen && option.verdict == Verdict.CORRECT -> MaterialTheme.colorScheme.primaryContainer
        isChosen -> MaterialTheme.colorScheme.errorContainer
        option.verdict == Verdict.CORRECT -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        !answered -> MaterialTheme.colorScheme.onSurface
        isChosen && option.verdict == Verdict.CORRECT -> MaterialTheme.colorScheme.onPrimaryContainer
        isChosen -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(enabled = !answered, onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                "${option.optionId}.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Text(option.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
