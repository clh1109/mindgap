package com.mindgap.app.quiz

import com.mindgap.app.data.model.MutationType
import com.mindgap.app.data.model.QuizMutation

internal enum class QuizDifficulty(val wire: String) {
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard")
}

/**
 * 一次反事实改写：把原文整句按固定规则改成一句「看似合理但为假」的话。
 * changedToken 形如「多数→少数」，用于向用户解释到底改在哪里。
 */
internal data class CounterfactualMutation(
    val type: MutationType,
    val originalText: String,
    val editedText: String,
    val changedToken: String,
    val difficulty: QuizDifficulty
)

/**
 * 反事实改写引擎（纯本地、可复现、可验证）：
 * - 否定：必须→不必、可以→不能
 * - 情态：需要→无需、只能→也能
 * - 量化：多数→少数、至少→至多、超过→少于
 * - 数值：两→三、过半→不足一半
 * - 关系反转：A 导致 B → B 导致 A（字词级交换主客）
 *
 * 只做词表与结构级改写，保证每一句都能回指原文整句；
 * 改写后不得与原文相同，也不得恰好是文档里另一句真话（由生成器再校验）；
 * 已处于否定语境的词不会再做否定类改写，避免「不不允许」式双重否定。
 */
internal object CounterfactualMutator {
    private data class TokenRule(
        val type: MutationType,
        val from: String,
        val to: String,
        val difficulty: QuizDifficulty,
        /** 动词否定优先改最后一个出现位置，避开「日志复制」这类名词性开头。 */
        val preferLast: Boolean = false
    )

    private val TOKEN_RULES = listOf(
        // 否定 / 反义
        TokenRule(MutationType.NEGATION, "必须", "不必", QuizDifficulty.HARD),
        TokenRule(MutationType.NEGATION, "可以", "不能", QuizDifficulty.EASY),
        TokenRule(MutationType.NEGATION, "能够", "无法", QuizDifficulty.EASY),
        TokenRule(MutationType.NEGATION, "允许", "不允许", QuizDifficulty.EASY),
        TokenRule(MutationType.NEGATION, "保证", "无法保证", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "一定", "不一定", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "支持", "不支持", QuizDifficulty.EASY),
        TokenRule(MutationType.NEGATION, "包含", "不包含", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "属于", "不属于", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "相同", "不同", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "不同", "相同", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NEGATION, "连续", "不连续", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "复制", "不复制", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "保存", "不保存", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "执行", "不执行", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "更新", "不更新", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "提交", "不提交", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "返回", "不返回", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "生效", "不生效", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "划分", "不划分", QuizDifficulty.MEDIUM, preferLast = true),
        TokenRule(MutationType.NEGATION, "生成", "不生成", QuizDifficulty.MEDIUM, preferLast = true),
        // 情态 / 强度
        TokenRule(MutationType.MODALITY, "需要", "无需", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.MODALITY, "只能", "也能", QuizDifficulty.HARD),
        TokenRule(MutationType.MODALITY, "建议", "禁止", QuizDifficulty.HARD),
        TokenRule(MutationType.MODALITY, "通过", "绕过", QuizDifficulty.MEDIUM),
        // 量化 / 范围
        TokenRule(MutationType.QUANTIFIER, "所有", "部分", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "全部", "少数", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "多数", "少数", QuizDifficulty.HARD),
        TokenRule(MutationType.QUANTIFIER, "少数", "多数", QuizDifficulty.HARD),
        TokenRule(MutationType.QUANTIFIER, "至少", "至多", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "至多", "至少", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "超过", "少于", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "少于", "超过", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "每个", "部分", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "最多", "最少", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "最少", "最多", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "任意", "固定", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.QUANTIFIER, "固定", "任意", QuizDifficulty.MEDIUM),
        // 数值 / 比例
        TokenRule(MutationType.NUMERIC, "过半", "不足一半", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.NUMERIC, "两", "三", QuizDifficulty.EASY),
        TokenRule(MutationType.NUMERIC, "三", "两", QuizDifficulty.EASY),
        // 时间 / 读写方向
        TokenRule(MutationType.RELATION_REVERSE, "先于", "晚于", QuizDifficulty.HARD),
        TokenRule(MutationType.RELATION_REVERSE, "之前", "之后", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.RELATION_REVERSE, "之后", "之前", QuizDifficulty.MEDIUM),
        TokenRule(MutationType.RELATION_REVERSE, "写入", "读取", QuizDifficulty.HARD),
        TokenRule(MutationType.RELATION_REVERSE, "读取", "写入", QuizDifficulty.HARD),
        TokenRule(MutationType.RELATION_REVERSE, "发送", "接收", QuizDifficulty.HARD),
        TokenRule(MutationType.RELATION_REVERSE, "接收", "发送", QuizDifficulty.HARD)
    )

    private val REVERSE_VERBS = listOf("导致", "决定", "依赖")

    fun mutate(sentence: String): List<CounterfactualMutation> {
        val base = sentence.trimEnd('。', '！', '？', '；', '\n', ' ')
        if (base.length < MIN_SENTENCE_CHARS) return emptyList()
        val results = LinkedHashMap<String, CounterfactualMutation>()

        TOKEN_RULES.forEach { rule ->
            val index = findMutableIndex(base, rule)
            if (index < 0) return@forEach
            val edited = base.substring(0, index) +
                rule.to +
                base.substring(index + rule.from.length)
            if (edited == base) return@forEach
            val changed = "${rule.from}→${rule.to}"
            results.putIfAbsent(
                "$changed@$index",
                CounterfactualMutation(
                    type = rule.type,
                    originalText = base,
                    editedText = edited,
                    changedToken = changed,
                    difficulty = rule.difficulty
                )
            )
        }

        reverseRelation(base)?.let { mutation ->
            results.putIfAbsent("reverse@${mutation.changedToken}", mutation)
        }
        return results.values.toList()
    }

    /** 校验一条外部（云端）改写记录是否严格由本地规则产生。 */
    fun verify(mutation: QuizMutation): Boolean {
        val changed = mutation.changedToken ?: return false
        if (changed.endsWith(REVERSE_SUFFIX)) {
            val reverse = reverseRelation(mutation.originalText) ?: return false
            return mutation.type == MutationType.RELATION_REVERSE &&
                reverse.editedText == mutation.editedText
        }
        val parts = changed.split("→")
        if (parts.size != 2) return false
        val rule = TOKEN_RULES.firstOrNull { it.from == parts[0] && it.to == parts[1] }
            ?: return false
        if (rule.type != mutation.type) return false
        val guarded = createsNegation(rule.to)
        var index = mutation.originalText.indexOf(rule.from)
        while (index >= 0) {
            if (!guarded || !isNegatedBefore(mutation.originalText, index)) {
                val expected = mutation.originalText.substring(0, index) +
                    rule.to +
                    mutation.originalText.substring(index + rule.from.length)
                if (expected == mutation.editedText) return true
            }
            index = mutation.originalText.indexOf(rule.from, index + 1)
        }
        return false
    }

    /** A 导致 B → B 导致 A；只在两侧都是短句、无标点时使用，保证改写后仍可读。 */
    private fun reverseRelation(sentence: String): CounterfactualMutation? {
        REVERSE_VERBS.forEach { verb ->
            val index = sentence.indexOf(verb)
            if (index <= 0) return@forEach
            val left = sentence.substring(0, index).trim()
            val right = sentence.substring(index + verb.length).trim()
            if (left.length !in MIN_SIDE_CHARS..MAX_SIDE_CHARS) return@forEach
            if (right.length !in MIN_SIDE_CHARS..MAX_SIDE_CHARS) return@forEach
            if ((left + right).any { it in SENTENCE_BREAKERS }) return@forEach
            val edited = right + verb + left
            if (edited == sentence) return@forEach
            return CounterfactualMutation(
                type = MutationType.RELATION_REVERSE,
                originalText = sentence,
                editedText = edited,
                changedToken = "$verb$REVERSE_SUFFIX",
                difficulty = QuizDifficulty.HARD
            )
        }
        return null
    }

    /**
     * 按 preferLast 偏好顺序找第一个可安全改写的位置。
     * 若改写结果以否定词开头（如 允许→不允许），而命中位置前已有否定前缀
     * （如「不允许」里的「允许」），跳过该命中，避免产生「不不允许」式双重否定；
     * 同一句里还有其他未否定的命中时继续用它们，全部被保护时返回 -1。
     */
    private fun findMutableIndex(text: String, rule: TokenRule): Int {
        val guarded = createsNegation(rule.to)
        val indices = mutableListOf<Int>()
        var i = text.indexOf(rule.from)
        while (i >= 0) {
            indices.add(i)
            i = text.indexOf(rule.from, i + 1)
        }
        if (rule.preferLast) indices.reverse()
        return indices.firstOrNull { !guarded || !isNegatedBefore(text, it) } ?: -1
    }

    private fun createsNegation(to: String): Boolean =
        NEGATION_STARTS.any { to.startsWith(it) }

    private fun isNegatedBefore(text: String, index: Int): Boolean {
        if (index <= 0) return false
        val before = text.substring(0, index)
        return NEGATION_PREFIXES.any { before.endsWith(it) }
    }

    private const val MIN_SENTENCE_CHARS = 12
    private const val MIN_SIDE_CHARS = 2
    private const val MAX_SIDE_CHARS = 28
    private const val REVERSE_SUFFIX = "（主客互换）"
    private const val SENTENCE_BREAKERS = "，。；！？：,.;!?:\n"

    /** 改写结果若以这些字开头，视为否定类改写。 */
    private val NEGATION_STARTS = listOf("不", "无", "未", "非")

    /** 命中位置前若以这些词结尾，说明原词已处于否定语境（多字词在前，避免漏判「无法/不能」）。 */
    private val NEGATION_PREFIXES = listOf(
        "无法", "不能", "没有", "不必", "无需", "未能", "不会", "不可",
        "不", "无", "未", "非", "没"
    )
}
