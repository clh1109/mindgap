package com.mindgap.app.quiz

import com.mindgap.app.data.model.QuizItem
import com.mindgap.app.data.model.QuizMode
import com.mindgap.app.data.model.QuizMutation
import com.mindgap.app.data.model.QuizOption
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.data.model.Verdict
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.random.Random

data class QuizGenerationResult(
    val quizzes: List<QuizItem>,
    val skippedEvents: Int,
    val note: String
)

/**
 * M5 反事实找茬题生成（离线，可复现）。
 *
 * 题目结构固定为「1 句原文 + 2 句反事实改写」：
 * - 正确项从卡壳段原文逐字摘一句；
 * - 每个干扰项都由同一段的原文整句按固定规则改写（否定/情态/量化/数值/关系反转），
 *   并记录 originalText、editedText、changedToken 与 sourceFrameId；
 * - 改写后不得恰好是文档里另一句真话，否则丢弃换一条规则。
 *
 * 这样「找茬」找的是错误理解，而不是从别段拿来一句话；
 * 每个选项都能回指原文，M6 的答题数据又能反过来度量高信心错误。
 */
class QuizGenerator(private val session: TimelineSession) {
    private val frameTextById: Map<String, String> = session.frames.associate { frame ->
        frame.frameId to normalizeForEvidence(frame.screen?.text.orEmpty())
    }

    /** 整个会话的可见文本，用于确认改写句不是文档里的另一句真话。 */
    private val sessionText: String = buildString {
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

    fun generate(): QuizGenerationResult {
        val materials = buildMaterials()
        val quizzes = mutableListOf<QuizItem>()
        var skipped = 0

        for (event in session.stuckEvents) {
            var quiz: QuizItem? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                val candidate = generateFor(event, materials, attempt) ?: continue
                if (QuizValidator.problems(candidate, session).isEmpty()) {
                    quiz = candidate
                    break
                }
            }
            if (quiz != null) quizzes += quiz else skipped++
        }

        val note = "M5 反事实找茬：${quizzes.size} 题（原文 1 + 改写 2）；" +
            "跳过 $skipped 个事件（可用句或证据不足）；答案键仅本地演示"
        return QuizGenerationResult(quizzes, skipped, note)
    }

    private data class ParagraphMaterial(
        val paragraphId: String,
        val text: String,
        val frameIds: List<String>
    )

    private data class MutatedSource(
        val sourceSentence: String,
        val mutation: CounterfactualMutation,
        val evidenceFrameIds: List<String>
    )

    private fun buildMaterials(): Map<String, ParagraphMaterial> {
        val framesByParagraph = LinkedHashMap<String, MutableList<TimelineFrame>>()
        session.frames.forEach { frame ->
            val paragraphId = frame.screen?.paragraphId ?: return@forEach
            framesByParagraph.getOrPut(paragraphId) { mutableListOf() }.add(frame)
        }
        return framesByParagraph.mapNotNull { (paragraphId, frames) ->
            val text = frames.asSequence()
                .mapNotNull { frame -> frame.screen?.text?.takeIf { it.isNotBlank() } }
                .maxByOrNull { it.length }
                ?: return@mapNotNull null
            paragraphId to ParagraphMaterial(
                paragraphId = paragraphId,
                text = text,
                frameIds = frames.map { it.frameId }
            )
        }.toMap()
    }

    private fun generateFor(
        event: StuckEvent,
        materials: Map<String, ParagraphMaterial>,
        attempt: Int
    ): QuizItem? {
        val focusParagraphId = event.paragraphId ?: return null
        val focus = materials[focusParagraphId] ?: return null
        val term = event.term?.takeIf { it.isNotBlank() }
            ?: event.signals?.skippedTerm?.takeIf { it.isNotBlank() }

        val allSentences = splitSentences(focus.text)
        if (allSentences.isEmpty()) return null
        val withTerm = if (term != null) {
            allSentences.filter { it.contains(term, ignoreCase = true) }
        } else {
            emptyList()
        }
        val preferredSentences = withTerm.ifEmpty { allSentences }

        // 每句先生成候选改写；没有可用改写的句子无法出题
        val sentenceMutations = preferredSentences.mapNotNull { sentence ->
            val evidence = evidenceFor(sentence, focus)
            if (evidence.isEmpty()) return@mapNotNull null
            val mutations = CounterfactualMutator.mutate(sentence)
                .filter { mutation ->
                    mutation.editedText != sentence &&
                        !isKnownTrue(mutation.editedText)
                }
                .filter { mutation -> evidenceFor(mutation.originalText, focus).isNotEmpty() }
            if (mutations.isEmpty()) return@mapNotNull null
            sentence to mutations
        }
        if (sentenceMutations.isEmpty()) return null

        // 选正确句：优先有 HARD 改写、句子较长；attempt 让重试换一条
        val rankedSentences = sentenceMutations.sortedWith(
            compareByDescending<Pair<String, List<CounterfactualMutation>>> { entry ->
                entry.second.any { it.difficulty == QuizDifficulty.HARD }
            }.thenByDescending { it.first.length }
        )
        val correctSentence = rankedSentences[attempt % rankedSentences.size].first
        val correctEvidence = evidenceFor(correctSentence, focus)
        if (correctEvidence.isEmpty()) return null

        val distractorPool = sentenceMutations.flatMap { (sentence, mutations) ->
            mutations.map { mutation ->
                MutatedSource(sentence, mutation, evidenceFor(mutation.originalText, focus))
            }
        }.filter { candidate ->
            normalizeForEvidence(candidate.mutation.editedText) != normalizeForEvidence(correctSentence) &&
                candidate.evidenceFrameIds.isNotEmpty()
        }
        if (distractorPool.isEmpty()) return null

        val chosen = chooseDistractors(distractorPool, correctSentence, attempt)
        if (chosen.size < 2) return null

        val seeds = mutableListOf<Seed>()
        seeds += Seed(
            text = correctSentence,
            correct = true,
            mutation = null,
            evidenceFrameIds = correctEvidence
        )
        chosen.forEach { candidate ->
            seeds += Seed(
                text = candidate.mutation.editedText,
                correct = false,
                mutation = candidate.mutation,
                evidenceFrameIds = candidate.evidenceFrameIds,
                sourceFrameId = candidate.evidenceFrameIds.first()
            )
        }
        if (seeds.map { normalizeForEvidence(it.text) }.distinct().size != seeds.size) return null

        val shuffled = seeds.shuffled(Random(event.eventId.hashCode() * 31 + attempt * 7 + 11))
        val options = shuffled.mapIndexed { index, seed ->
            val optionId = ('A' + index).toString()
            QuizOption(
                optionId = optionId,
                text = seed.text,
                verdict = if (seed.correct) Verdict.CORRECT else Verdict.DISTRACTOR,
                rationale = if (seed.correct) {
                    "正确：该句可在证据帧 ${seed.evidenceFrameIds.first()} 的段落原文中逐字找到。"
                } else {
                    val mutation = seed.mutation!!
                    "反事实：原文是「${mutation.originalText}」，" +
                        "这项${describeChange(mutation.changedToken)}，与原文不符。"
                },
                evidenceFrameIds = seed.evidenceFrameIds,
                mutation = seed.mutation?.let { mutation ->
                    QuizMutation(
                        type = mutation.type,
                        originalText = mutation.originalText,
                        editedText = mutation.editedText,
                        changedToken = mutation.changedToken,
                        sourceFrameId = seed.sourceFrameId
                            ?: seed.evidenceFrameIds.first()
                    )
                }
            )
        }

        val correctOption = options.first { it.verdict == Verdict.CORRECT }
        val changes = chosen.map { it.mutation.changedToken }.distinct()
        val hardest = chosen.maxByOrNull { difficultyRank(it.mutation.difficulty) }?.mutation?.difficulty
            ?: QuizDifficulty.MEDIUM
        val explanation = buildString {
            append("正确项 ${correctOption.optionId} 可在证据帧 ")
            append(correctOption.evidenceFrameIds.first())
            append(" 的原文中逐字找到；")
            append("另外两项分别改写了「")
            append(changes.joinToString("」「"))
            append("」，意思与原句不同。")
        }

        return QuizItem(
            quizId = "q-${event.eventId}",
            eventId = event.eventId,
            mode = QuizMode.OFFLINE_TEMPLATE,
            questionType = QUESTION_TYPE,
            difficulty = hardest.wire,
            stem = stemFor(event, term),
            options = options,
            explanation = explanation,
            model = "mindgap-counterfactual-v1",
            createdAt = OffsetDateTime.now().toString()
        )
    }

    private data class Seed(
        val text: String,
        val correct: Boolean,
        val mutation: CounterfactualMutation?,
        val evidenceFrameIds: List<String>,
        val sourceFrameId: String? = null
    )

    private fun chooseDistractors(
        pool: List<MutatedSource>,
        correctSentence: String,
        attempt: Int
    ): List<MutatedSource> {
        // 先要同句改写（最像原文、最难），再从其他句补一条；每条最多贡献一句
        val sorted = pool.sortedWith(
            compareByDescending<MutatedSource> { it.sourceSentence == correctSentence }
                .thenByDescending { difficultyRank(it.mutation.difficulty) }
                .thenBy { abs(it.mutation.editedText.length - correctSentence.length) }
        )
        val chosen = mutableListOf<MutatedSource>()
        for (candidate in sorted) {
            if (chosen.size == 2) break
            if (chosen.any { it.sourceSentence == candidate.sourceSentence }) continue
            if (chosen.any {
                    normalizeForEvidence(it.mutation.editedText) ==
                        normalizeForEvidence(candidate.mutation.editedText)
                }
            ) {
                continue
            }
            chosen += candidate
        }
        if (chosen.size < 2) {
            // 只有一句可用时，允许同一句的另一条改写规则
            for (candidate in sorted) {
                if (chosen.size == 2) break
                if (chosen.contains(candidate)) continue
                if (chosen.any {
                        normalizeForEvidence(it.mutation.editedText) ==
                            normalizeForEvidence(candidate.mutation.editedText)
                    }
                ) {
                    continue
                }
                chosen += candidate
            }
        }
        // attempt 只用于打破完全并列，保证重跑稳定
        if (chosen.size == 2 && attempt % 2 == 1) {
            val first = chosen[0]
            val second = chosen[1]
            if (first.sourceSentence == second.sourceSentence) {
                return listOf(second, first)
            }
        }
        return chosen
    }

    private fun evidenceFor(sentence: String, material: ParagraphMaterial): List<String> {
        val needle = normalizeForEvidence(sentence)
        if (needle.isEmpty()) return emptyList()
        return material.frameIds
            .filter { frameId -> frameTextById[frameId]?.contains(needle) == true }
            .take(MAX_EVIDENCE_PER_OPTION)
    }

    private fun isKnownTrue(text: String): Boolean {
        val normalized = normalizeForEvidence(text)
        return normalized.isNotEmpty() && sessionText.contains(normalized)
    }

    private fun splitSentences(text: String): List<String> =
        text.split('。', '！', '？', '；', '\n')
            .map { sentence ->
                sentence.trim().trim('，', ',', '、', '；', ';', ':', '：', '…', ' ')
            }
            .filter { it.length in MIN_SENTENCE_CHARS..MAX_SENTENCE_CHARS }
            .distinct()

    private fun describeChange(token: String): String {
        val parts = token.split("→")
        return if (parts.size == 2) {
            "把「${parts[0]}」改成了「${parts[1]}」"
        } else {
            "把主客顺序互换"
        }
    }

    private fun stemFor(event: StuckEvent, term: String?): String = when (event.kind) {
        StuckKind.SEMANTIC_ANOMALY ->
            "术语「${term ?: "该术语"}」出现在你快速跳过的那一段。" +
                "下面哪一句与原文完全一致？另外两句各被改了一个词。"
        StuckKind.BEHAVIOR_ANOMALY -> if (event.signals?.switchedApp == true) {
            "你切到其他应用后回看了这一段。" +
                "下面哪一句与原文完全一致？另外两句各被改了一个词。"
        } else {
            "你曾回看这一段。下面哪一句与原文完全一致？另外两句各被改了一个词。"
        }
        StuckKind.TIME_ANOMALY -> {
            val dwell = event.signals?.dwellMs ?: (event.toMs - event.fromMs)
            "你在这一段停留了 ${seconds(dwell)}。" +
                "下面哪一句与原文完全一致？另外两句各被改了一个词。"
        }
    }

    private fun seconds(ms: Long): String = "${ms / 1000}s"

    private fun difficultyRank(difficulty: QuizDifficulty): Int = when (difficulty) {
        QuizDifficulty.EASY -> 1
        QuizDifficulty.MEDIUM -> 2
        QuizDifficulty.HARD -> 3
    }

    private companion object {
        const val QUESTION_TYPE = "counterfactual_quote"
        const val MAX_ATTEMPTS = 3
        const val MIN_SENTENCE_CHARS = 12
        const val MAX_SENTENCE_CHARS = 80
        const val MAX_EVIDENCE_PER_OPTION = 2
    }
}
