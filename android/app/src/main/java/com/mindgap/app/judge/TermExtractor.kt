package com.mindgap.app.judge

import java.util.Locale

/**
 * 语义异常的轻量术语抽取，不做完整 NLP：
 * - 引号内的短语优先（「…」「…」《…》）
 * - 会话内低频的拉丁技术词（含大写或较长）
 * - 用解释标记与括号定义判断是否已经在上下文里解释过
 * 所有结果都能回指原句，供 M5 做证据校验。
 */
internal object TermExtractor {
    private val WORD = Regex("""[A-Za-z][A-Za-z0-9_\-]{2,}""")
    private val QUOTED = Regex("""[「“《]([^」”》]{2,16})[」”》]""")

    // 只保留通用英语停用词：server/log/leader/node 等领域词恰是技术语料的核心术语，
    // 放进停用词会让语义通道对分布式/系统类内容整体失明
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "have", "will", "would",
        "can", "could", "should", "are", "was", "were", "not", "but", "you", "your",
        "they", "their", "what", "when", "where", "which", "how", "all", "any", "has",
        "had", "its", "our", "out", "one", "two", "use", "using", "used", "into",
        "than", "then", "more", "most", "some", "such", "only", "also", "may", "might",
        "must", "about", "after", "before", "between", "each", "other", "over", "same",
        "very", "well", "make", "made", "like", "just", "time", "data",
        "first", "second", "every", "here",
        "there", "these", "those", "been", "being", "does", "doing", "done", "shall",
        "upon", "while", "within", "without", "through", "during", "should", "many"
    )

    private val EXPLANATION_MARKERS = listOf(
        "是指", "也就是说", "称为", "称作", "定义为", "所谓", "意味着", "也就是",
        "是一种", "指的是", "即为", "means", "refers to", "is defined as", "i.e.", "e.g."
    )

    fun frequencies(texts: List<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        texts.forEach { text ->
            WORD.findAll(text).forEach { match ->
                val key = match.value.lowercase(Locale.ROOT)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }

    fun candidates(text: String, frequencies: Map<String, Int>): List<String> {
        val result = LinkedHashSet<String>()

        QUOTED.findAll(text).forEach { match ->
            val term = match.groupValues[1].trim()
            if (term.length in 2..16 && term.any { it.isLetter() }) {
                result += term
            }
        }

        val words = WORD.findAll(text).map { it.value }.distinct().toList()
        words.sortedWith(
            compareByDescending<String> { word -> word.any { it.isUpperCase() } }
                .thenByDescending { it.length }
        ).forEach { word ->
            val lower = word.lowercase(Locale.ROOT)
            if (lower in STOPWORDS) return@forEach
            if (word.length < MIN_WORD_LENGTH) return@forEach
            if ((frequencies[lower] ?: 0) > MAX_SESSION_FREQUENCY) return@forEach
            if (word.any { it.isUpperCase() } || word.length >= LONG_WORD_LENGTH) {
                result += word
            }
        }
        return result.take(MAX_CANDIDATES)
    }

    fun isExplained(term: String, context: String): Boolean {
        var index = context.indexOf(term, ignoreCase = true)
        while (index >= 0) {
            val from = (index - CONTEXT_BEFORE).coerceAtLeast(0)
            val to = (index + term.length + CONTEXT_AFTER).coerceAtMost(context.length)
            val window = context.substring(from, to)
            if (EXPLANATION_MARKERS.any { window.contains(it, ignoreCase = true) }) {
                return true
            }

            val after = context.substring(index + term.length).trimStart()
            if (after.startsWith("（") || after.startsWith("(")) {
                val close = if (after.startsWith("（")) '）' else ')'
                val inside = after.drop(1).substringBefore(close)
                if (inside.length >= 2) return true
            }
            index = context.indexOf(term, index + term.length, ignoreCase = true)
        }
        return false
    }

    fun sentenceAround(text: String, term: String): String? {
        val index = text.indexOf(term, ignoreCase = true)
        if (index < 0) return null
        var start = index
        while (start > 0 && text[start - 1] !in SENTENCE_DELIMITERS) start--
        var end = index + term.length
        while (end < text.length && text[end] !in SENTENCE_DELIMITERS) end++
        val sentence = text.substring(start, (end + 1).coerceAtMost(text.length)).trim()
        return sentence.take(MAX_SENTENCE).takeIf { it.isNotEmpty() }
    }

    private val SENTENCE_DELIMITERS = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')

    private const val MIN_WORD_LENGTH = 4
    private const val LONG_WORD_LENGTH = 6
    private const val MAX_SESSION_FREQUENCY = 2
    private const val MAX_CANDIDATES = 4
    private const val MAX_SENTENCE = 120
    private const val CONTEXT_BEFORE = 40
    private const val CONTEXT_AFTER = 80
}
