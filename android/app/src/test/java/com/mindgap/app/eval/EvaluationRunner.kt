package com.mindgap.app.eval

import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.data.model.Verdict
import com.mindgap.app.judge.StuckDetector
import com.mindgap.app.quiz.CounterfactualMutator
import com.mindgap.app.quiz.QuizGenerator
import com.mindgap.app.quiz.QuizValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

@Serializable
data class ExpectedEvent(
    val kind: String,
    val paragraphId: String? = null
)

@Serializable
data class SessionLabel(
    val file: String,
    val expected: List<ExpectedEvent> = emptyList()
)

@Serializable
data class EvalLabels(
    val sessions: List<SessionLabel> = emptyList()
)

@Serializable
data class KindMetrics(
    val expected: Int = 0,
    val detected: Int = 0,
    val truePositives: Int = 0,
    val falsePositives: Int = 0,
    val falseNegatives: Int = 0
)

@Serializable
data class SessionResult(
    val file: String,
    val expected: Int,
    val detected: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val quizzes: Int,
    val skipped: Int,
    val validationFailures: Int
)

@Serializable
data class EvalReport(
    val sessionCount: Int,
    val expectedEvents: Int,
    val detectedEvents: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val byKind: Map<String, KindMetrics>,
    val quizzesGenerated: Int,
    val quizSkippedEvents: Int,
    val quizValidationFailures: Int,
    val distractors: Int,
    val distractorsWithMutation: Int,
    val sameSentenceTraps: Int,
    val difficulty: Map<String, Int>,
    val sessions: List<SessionResult>,
    val failures: List<String>
) {
    fun toMarkdown(): String = buildString {
        appendLine("# MindGap 离线评测报告")
        appendLine()
        appendLine("- 会话：$sessionCount")
        appendLine("- 期望卡壳事件：$expectedEvents，检出：$detectedEvents")
        appendLine(
            "- 精确率 ${percent(precision)} / 召回率 ${percent(recall)} / F1 ${percent(f1)}" +
                "（TP $truePositives / FP $falsePositives / FN $falseNegatives）"
        )
        appendLine()
        appendLine("| 类型 | 期望 | 检出 | TP | FP | FN |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        byKind.forEach { (kind, m) ->
            appendLine(
                "| $kind | ${m.expected} | ${m.detected} | ${m.truePositives} " +
                    "| ${m.falsePositives} | ${m.falseNegatives} |"
            )
        }
        appendLine()
        appendLine("## 找茬题")
        appendLine("- 生成 $quizzesGenerated 题；跳过 $quizSkippedEvents 个事件")
        appendLine("- 校验失败：$quizValidationFailures")
        val mutationRate = if (distractors == 0) 0.0 else {
            distractorsWithMutation.toDouble() / distractors
        }
        appendLine(
            "- 干扰项 $distractors，带可验证改写 $distractorsWithMutation" +
                "（${percent(mutationRate)}）"
        )
        appendLine("- 同句陷阱（正确句本身被改写）：$sameSentenceTraps 题")
        appendLine(
            "- 难度分布：" + difficulty.entries.joinToString(" / ") { "${it.key} ${it.value}" }
        )
        appendLine()
        appendLine("## 失败用例")
        if (failures.isEmpty()) {
            appendLine("无")
        } else {
            failures.forEach { appendLine("- $it") }
        }
    }

    private fun percent(value: Double): String =
        "${(value * 1000).roundToInt() / 10.0}%"
}

private class KindAccumulator {
    var expected = 0
    var detected = 0
    var truePositives = 0
    var falsePositives = 0
    var falseNegatives = 0
}

/**
 * 直接调用真实的 StuckDetector 与 QuizGenerator，不用另一套「评测版算法」。
 * 语料与标签由 eval/generate_fixtures.py 生成。
 */
object EvaluationRunner {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val prettyJson = Json { prettyPrint = true }

    fun run(fixturesDir: File, reportDir: File): EvalReport {
        val labels = json.decodeFromString(
            EvalLabels.serializer(),
            File(fixturesDir, "labels.json").readText()
        )
        require(labels.sessions.isNotEmpty()) { "评测标签为空" }

        var expectedEvents = 0
        var detectedEvents = 0
        var tp = 0
        var fp = 0
        var fn = 0
        var quizzes = 0
        var skipped = 0
        var validationFailures = 0
        var distractors = 0
        var distractorsWithMutation = 0
        var sameSentenceTraps = 0
        val difficulty = LinkedHashMap<String, Int>()
        val kindAcc = LinkedHashMap<String, KindAccumulator>()
        val sessionResults = mutableListOf<SessionResult>()
        val failures = mutableListOf<String>()

        labels.sessions.forEach { label ->
            val session = json.decodeFromString(
                TimelineSession.serializer(),
                File(File(fixturesDir, "sessions"), label.file).readText()
            )
            val detected = StuckDetector(session).detect().events
            val judged = session.copy(stuckEvents = detected)
            val unmatched = detected.toMutableList()

            expectedEvents += label.expected.size
            detectedEvents += detected.size
            label.expected.forEach { expected ->
                kindAcc.getOrPut(expected.kind) { KindAccumulator() }.expected++
            }
            detected.forEach { event ->
                kindAcc.getOrPut(wire(event.kind)) { KindAccumulator() }.detected++
            }

            var sessionTp = 0
            var sessionFn = 0
            label.expected.forEach { expected ->
                val index = unmatched.indexOfFirst { event ->
                    wire(event.kind) == expected.kind && event.paragraphId == expected.paragraphId
                }
                if (index >= 0) {
                    unmatched.removeAt(index)
                    sessionTp++
                    kindAcc.getOrPut(expected.kind) { KindAccumulator() }.truePositives++
                } else {
                    sessionFn++
                    kindAcc.getOrPut(expected.kind) { KindAccumulator() }.falseNegatives++
                }
            }
            unmatched.forEach { event ->
                kindAcc.getOrPut(wire(event.kind)) { KindAccumulator() }.falsePositives++
            }

            tp += sessionTp
            fn += sessionFn
            fp += unmatched.size

            val quizResult = QuizGenerator(judged).generate()
            quizzes += quizResult.quizzes.size
            skipped += quizResult.skippedEvents
            var sessionValidationFailures = 0
            quizResult.quizzes.forEach { quiz ->
                if (QuizValidator.problems(quiz, judged).isNotEmpty()) {
                    validationFailures++
                    sessionValidationFailures++
                }
                quiz.difficulty?.let { level ->
                    difficulty[level] = (difficulty[level] ?: 0) + 1
                }
                val correctText = quiz.options.firstOrNull { it.verdict == Verdict.CORRECT }?.text
                quiz.options.filter { it.verdict == Verdict.DISTRACTOR }.forEach { option ->
                    distractors++
                    if (option.mutation?.let { CounterfactualMutator.verify(it) } == true) {
                        distractorsWithMutation++
                    }
                }
                if (correctText != null &&
                    quiz.options.any { it.mutation?.originalText == correctText }
                ) {
                    sameSentenceTraps++
                }
            }

            sessionResults += SessionResult(
                file = label.file,
                expected = label.expected.size,
                detected = detected.size,
                truePositives = sessionTp,
                falsePositives = unmatched.size,
                falseNegatives = sessionFn,
                quizzes = quizResult.quizzes.size,
                skipped = quizResult.skippedEvents,
                validationFailures = sessionValidationFailures
            )
            if (unmatched.isNotEmpty() || sessionFn > 0) {
                failures += "${label.file}: TP $sessionTp / FP ${unmatched.size} / FN $sessionFn"
            }
        }

        val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else {
            2 * precision * recall / (precision + recall)
        }
        val report = EvalReport(
            sessionCount = labels.sessions.size,
            expectedEvents = expectedEvents,
            detectedEvents = detectedEvents,
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            precision = precision,
            recall = recall,
            f1 = f1,
            byKind = kindAcc.mapValues { (_, acc) ->
                KindMetrics(
                    expected = acc.expected,
                    detected = acc.detected,
                    truePositives = acc.truePositives,
                    falsePositives = acc.falsePositives,
                    falseNegatives = acc.falseNegatives
                )
            },
            quizzesGenerated = quizzes,
            quizSkippedEvents = skipped,
            quizValidationFailures = validationFailures,
            distractors = distractors,
            distractorsWithMutation = distractorsWithMutation,
            sameSentenceTraps = sameSentenceTraps,
            difficulty = difficulty,
            sessions = sessionResults,
            failures = failures
        )
        reportDir.mkdirs()
        File(reportDir, "report.json").writeText(
            prettyJson.encodeToString(EvalReport.serializer(), report)
        )
        File(reportDir, "report.md").writeText(report.toMarkdown())
        return report
    }

    private fun wire(kind: StuckKind): String = kind.name.lowercase()
}
