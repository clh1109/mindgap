package com.mindgap.app.perception

import java.security.MessageDigest

/**
 * 文本侧的帧去重工具。感知哈希（画面）在采集阶段完成，
 * 这里补上 OCR 文本的规范化、指纹与相似度判定，避免同一屏反复计入时间轴。
 */
object FrameDedup {
    /** 去掉空行与行首尾空白，保留段落结构。 */
    fun normalize(raw: String): String = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    /** 计算相似度前先去掉全部空白，避免排版差异影响判断。 */
    fun compact(text: String): String = text.filterNot { it.isWhitespace() }

    fun hash(compactText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(compactText.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 字符二元组 Dice 系数，取值 0..1。
     * 适合中文 OCR 的「同一段文字轻微识别差异」判定，不做编辑距离的平方级开销。
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0

        val first = bigrams(a)
        val second = bigrams(b)
        if (first.isEmpty() || second.isEmpty()) return 0.0

        val counts = HashMap<String, Int>(first.size)
        for (gram in first) counts[gram] = (counts[gram] ?: 0) + 1

        var overlap = 0
        for (gram in second) {
            val remaining = counts[gram] ?: continue
            if (remaining > 0) {
                overlap++
                counts[gram] = remaining - 1
            }
        }
        return 2.0 * overlap / (first.size + second.size)
    }

    private fun bigrams(text: String): List<String> {
        if (text.length < 2) return emptyList()
        val grams = ArrayList<String>(text.length - 1)
        for (i in 0 until text.length - 1) {
            grams.add(text.substring(i, i + 2))
        }
        return grams
    }
}
