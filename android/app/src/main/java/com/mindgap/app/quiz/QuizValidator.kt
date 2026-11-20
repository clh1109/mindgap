package com.mindgap.app.quiz

import com.mindgap.app.data.model.QuizItem
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.data.model.Verdict
import java.util.Locale

internal fun normalizeForEvidence(text: String): String =
    text.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

/**
 * 反事实题逐项校验：
 * - 恰好 3 项、A/B/C、唯一 correct；
 * - 正确项必须在卡壳段原文中逐字出现；
 * - 每个干扰项必须带 mutation，editedText 与选项文本一致，originalText 能在卡壳段原文找到；
 * - 改写后的句子不能恰好是文档里另一句真话；
 * - 证据帧必须存在、落在卡壳段，且包含 originalText。
 */
internal object QuizValidator {
    private const val QUESTION_TYPE = "counterfactual_quote"
    private val DIFFICULTIES = setOf("easy", "medium", "hard")

    fun problems(quiz: QuizItem, session: TimelineSession): List<String> {
        val problems = mutableListOf<String>()
        val event = session.stuckEvents.firstOrNull { it.eventId == quiz.eventId }
        if (event == null) {
            problems += "关联的卡壳事件不存在"
            return problems
        }
        if (quiz.stem.isBlank()) problems += "题干为空"
        // 与服务端 validate_quiz 对齐：解析必须非空
        if (quiz.explanation.isNullOrBlank()) problems += "解析为空"
        if (quiz.options.size != 3) problems += "选项数不是 3"
        if (quiz.options.map { it.optionId } != listOf("A", "B", "C")) {
            problems += "选项编号不是 A/B/C"
        }
        if (quiz.options.count { it.verdict == Verdict.CORRECT } != 1) {
            problems += "正确项数量不是 1"
        }
        if (quiz.options.map { normalizeForEvidence(it.text) }.distinct().size != quiz.options.size) {
            problems += "选项文本重复"
        }
        if (quiz.questionType != null && quiz.questionType != QUESTION_TYPE) {
            problems += "题型不是反事实找茬"
        }
        if (quiz.difficulty != null && quiz.difficulty !in DIFFICULTIES) {
            problems += "难度取值不合法"
        }

        val frameById = session.frames.associateBy { it.frameId }
        val eventParagraphId = event.paragraphId
        val focusFrames = session.frames.filter { it.screen?.paragraphId == eventParagraphId }
        val focusText = normalizeForEvidence(
            focusFrames.joinToString("\n") { it.screen?.text.orEmpty() }
        )
        if (focusText.isEmpty()) problems += "卡壳段没有可回指的原文"

        val sessionText = buildString {
            session.frames.forEach { frame ->
                val text = frame.screen?.text
                if (!text.isNullOrBlank()) {
                    append(normalizeForEvidence(text))
                    append('\n')
                }
            }
            session.document.paragraphs.forEach { paragraph ->
                val preview = paragraph.preview
                if (!preview.isNullOrBlank()) {
                    append(normalizeForEvidence(preview))
                    append('\n')
                }
            }
        }

        quiz.options.forEach { option ->
            if (option.text.isBlank()) problems += "${option.optionId} 文本为空"
            if (option.evidenceFrameIds.isEmpty()) problems += "${option.optionId} 缺少证据帧"
            val frames = option.evidenceFrameIds.mapNotNull { frameById[it] }
            if (frames.size != option.evidenceFrameIds.size) {
                problems += "${option.optionId} 引用了不存在的证据帧"
            }

            if (option.verdict == Verdict.CORRECT) {
                val needle = normalizeForEvidence(option.text)
                val grounded = frames.any { frame ->
                    frame.screen?.paragraphId == eventParagraphId &&
                        normalizeForEvidence(frame.screen?.text.orEmpty()).contains(needle)
                }
                if (!grounded) problems += "${option.optionId} 正确项无法回指卡壳段原文"
                return@forEach
            }

            val mutation = option.mutation
            if (mutation == null) {
                problems += "${option.optionId} 缺少反事实改写记录"
                return@forEach
            }
            val original = normalizeForEvidence(mutation.originalText)
            val edited = normalizeForEvidence(mutation.editedText)
            if (edited != normalizeForEvidence(option.text)) {
                problems += "${option.optionId} 改写文本与选项不一致"
            }
            if (original == edited) {
                problems += "${option.optionId} 改写前后相同"
            }
            if (!CounterfactualMutator.verify(mutation)) {
                problems += "${option.optionId} 改写不符合本地规则"
            }
            if (original.isEmpty() || !focusText.contains(original)) {
                problems += "${option.optionId} 改写原文不在卡壳段"
            }
            if (edited.isNotEmpty() && sessionText.contains(edited)) {
                problems += "${option.optionId} 改写句在文档中真实存在"
            }
            if (frameById[mutation.sourceFrameId] == null) {
                problems += "${option.optionId} 来源帧不存在"
            }
            if (mutation.sourceFrameId !in option.evidenceFrameIds) {
                problems += "${option.optionId} 来源帧不在证据帧中"
            }
            // 与服务端对齐：来源帧必须自身包含原文且落在卡壳段，
            // 不能只靠「任一证据帧包含」通过校验
            val sourceFrame = frameById[mutation.sourceFrameId]
            if (sourceFrame != null) {
                val sourceText = normalizeForEvidence(sourceFrame.screen?.text.orEmpty())
                if (sourceFrame.screen?.paragraphId != eventParagraphId ||
                    !sourceText.contains(original)
                ) {
                    problems += "${option.optionId} 来源帧自身未包含卡壳段原文"
                }
            }
            val grounded = frames.any { frame ->
                frame.screen?.paragraphId == eventParagraphId &&
                    normalizeForEvidence(frame.screen?.text.orEmpty()).contains(original)
            }
            if (!grounded) problems += "${option.optionId} 改写证据不成立"
        }
        return problems
    }
}
