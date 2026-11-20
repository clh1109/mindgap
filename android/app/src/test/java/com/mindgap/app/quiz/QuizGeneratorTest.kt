package com.mindgap.app.quiz

import com.mindgap.app.data.model.Action
import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.CaptureMeta
import com.mindgap.app.data.model.Document
import com.mindgap.app.data.model.FrameFeatures
import com.mindgap.app.data.model.Paragraph
import com.mindgap.app.data.model.ScreenObservation
import com.mindgap.app.data.model.ScreenSource
import com.mindgap.app.data.model.SourceKind
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.data.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizGeneratorTest {
    private val raftSentence =
        "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。"
    private val electionSentence =
        "任期用连续的整数编号，每个任期最多有一个领导者。"

    private fun session(): TimelineSession = TimelineSession(
        schemaVersion = "1.0.0",
        sessionId = "7a3f2c18-55d4-4d21-9c6e-0b6f4a1d9e32",
        capture = CaptureMeta(
            startedAt = "2026-11-18T00:00:00Z",
            sources = listOf(SourceKind.ACCESSIBILITY)
        ),
        document = Document(
            docId = "doc-test",
            title = "Raft 共识算法",
            paragraphs = listOf(
                Paragraph(
                    paragraphId = "p000",
                    index = 0,
                    charStart = 0,
                    charEnd = raftSentence.length,
                    preview = "Raft 日志复制与提交规则"
                ),
                Paragraph(
                    paragraphId = "p001",
                    index = 1,
                    charStart = raftSentence.length,
                    charEnd = raftSentence.length + electionSentence.length,
                    preview = "任期与选举规则"
                )
            )
        ),
        frames = listOf(
            frame("f0000", 0, "p000", raftSentence),
            frame("f0020", 10_000, "p001", electionSentence)
        ),
        stuckEvents = listOf(
            StuckEvent(
                eventId = "e001",
                kind = StuckKind.SEMANTIC_ANOMALY,
                paragraphId = "p000",
                term = "AppendEntries",
                fromMs = 0,
                toMs = 10_000,
                fromFrameId = "f0000",
                toFrameId = "f0000",
                evidenceFrameIds = listOf("f0000"),
                confidence = 0.8,
                summary = "快速跳过包含 AppendEntries 的段落"
            )
        )
    )

    private fun frame(
        frameId: String,
        t: Long,
        paragraphId: String,
        text: String
    ): TimelineFrame = TimelineFrame(
        frameId = frameId,
        t = t,
        capturedAt = "2026-11-18T00:00:00Z",
        screen = ScreenObservation(
            docId = "doc-test",
            paragraphId = paragraphId,
            text = text,
            textHash = "hash-$frameId",
            isDuplicate = false,
            source = ScreenSource.ACCESSIBILITY
        ),
        action = Action(ActionType.NONE),
        features = FrameFeatures(dwellMs = 8_000)
    )

    @Test
    fun `生成的是原文加两句反事实改写`() {
        val session = session()
        val result = QuizGenerator(session).generate()
        assertEquals(1, result.quizzes.size)

        val quiz = result.quizzes.first()
        assertEquals("counterfactual_quote", quiz.questionType)
        assertEquals(3, quiz.options.size)
        assertEquals(1, quiz.options.count { it.verdict == Verdict.CORRECT })
        assertTrue(quiz.stem.contains("与原文完全一致"))

        val correct = quiz.options.first { it.verdict == Verdict.CORRECT }
        assertTrue(correct.text.contains("AppendEntries"))
        assertTrue(correct.mutation == null)

        quiz.options.filter { it.verdict == Verdict.DISTRACTOR }.forEach { option ->
            assertNotNull(option.mutation)
            val mutation = option.mutation!!
            assertEquals(option.text, mutation.editedText)
            assertTrue(CounterfactualMutator.verify(mutation))
            assertTrue(mutation.originalText.contains("AppendEntries"))
            assertTrue(mutation.sourceFrameId in option.evidenceFrameIds)
        }
        assertTrue(QuizValidator.problems(quiz, session).isEmpty())
    }

    @Test
    fun `缺少改写记录的干扰项会被拒绝`() {
        val session = session()
        val quiz = QuizGenerator(session).generate().quizzes.first()
        val tampered = quiz.copy(
            options = quiz.options.map { option ->
                if (option.verdict == Verdict.DISTRACTOR) option.copy(mutation = null) else option
            }
        )
        val problems = QuizValidator.problems(tampered, session)
        assertTrue("应报告缺少改写记录：$problems", problems.any { it.contains("缺少反事实改写记录") })
    }

    @Test
    fun `改写句与记录不一致会被拒绝`() {
        val session = session()
        val quiz = QuizGenerator(session).generate().quizzes.first()
        val tampered = quiz.copy(
            options = quiz.options.map { option ->
                if (option.verdict == Verdict.DISTRACTOR) {
                    option.copy(text = option.text.replace("少数", "多数"))
                } else {
                    option
                }
            }
        )
        val problems = QuizValidator.problems(tampered, session)
        assertTrue("应报告改写不一致：$problems", problems.isNotEmpty())
    }

    @Test
    fun `正确项证据必须落在卡壳段`() {
        val session = session()
        val quiz = QuizGenerator(session).generate().quizzes.first()
        val tampered = quiz.copy(
            options = quiz.options.map { option ->
                if (option.verdict == Verdict.CORRECT) {
                    option.copy(evidenceFrameIds = listOf("f0020"))
                } else {
                    option
                }
            }
        )
        val problems = QuizValidator.problems(tampered, session)
        assertTrue(
            "应报告证据跨段：$problems",
            problems.any { it.contains("正确项无法回指卡壳段原文") }
        )
    }
}
