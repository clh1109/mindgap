package com.mindgap.app.align

import com.mindgap.app.data.model.Paragraph
import com.mindgap.app.data.model.ScreenSource
import com.mindgap.app.perception.FrameDedup
import java.util.Locale

/** 一路屏幕文本快照：无障碍优先，OCR 兜底，二者共用同一套段落索引。 */
internal data class TextObservation(
    val t: Long,
    val text: String,
    val compact: String,
    val textHash: String,
    val source: ScreenSource,
    val appPackage: String?
)

/**
 * 把屏幕文本快照归并为文档段落。
 *
 * 段落按首次出现顺序编号；完全相同的文本复用同一段落，
 * 与最近几个段落高度相似（OCR 噪声 / 空白差异）时也复用，避免段落爆炸。
 * 段落 ID 是 M4 停留与回看统计的锚点，因此必须稳定。
 */
internal class ParagraphIndex {
    private data class Entry(val paragraph: Paragraph, val compact: String)

    private val entries = mutableListOf<Entry>()
    private val byHash = HashMap<String, Paragraph>()

    fun resolve(observation: TextObservation): Paragraph {
        byHash[observation.textHash]?.let { return it }

        // 只与最近几个段落比对：精确回看靠 hash，近似匹配用于吸收 OCR 抖动
        for (i in entries.indices.reversed().take(RECENT_WINDOW)) {
            val entry = entries[i]
            if (entry.compact.length < MIN_COMPARE || observation.compact.length < MIN_COMPARE) continue
            if (FrameDedup.similarity(entry.compact, observation.compact) >= SIMILARITY) {
                byHash[observation.textHash] = entry.paragraph
                return entry.paragraph
            }
        }

        val charStart = entries.lastOrNull()?.paragraph?.charEnd ?: 0
        val paragraph = Paragraph(
            paragraphId = String.format(Locale.ROOT, "p%03d", entries.size),
            index = entries.size,
            heading = headingOf(observation.text),
            charStart = charStart,
            charEnd = charStart + observation.compact.length,
            preview = observation.text.replace('\n', ' ').take(PREVIEW_CHARS)
        )
        entries += Entry(paragraph, observation.compact)
        byHash[observation.textHash] = paragraph
        return paragraph
    }

    fun paragraphs(): List<Paragraph> = entries.map { it.paragraph }

    fun totalChars(): Int = entries.lastOrNull()?.paragraph?.charEnd ?: 0

    private fun headingOf(text: String): String? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val first = lines.first().trim()
        return first.takeIf { it.length <= MAX_HEADING }
    }

    private companion object {
        const val RECENT_WINDOW = 6
        const val SIMILARITY = 0.92
        const val MIN_COMPARE = 12
        const val MAX_HEADING = 24
        const val PREVIEW_CHARS = 120
    }
}
