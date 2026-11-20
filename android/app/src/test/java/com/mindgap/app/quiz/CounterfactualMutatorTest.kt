package com.mindgap.app.quiz

import com.mindgap.app.data.model.MutationType
import com.mindgap.app.data.model.QuizMutation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterfactualMutatorTest {
    private val raftSentence = "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。"

    @Test
    fun `量化词可以被改写成反事实`() {
        val mutations = CounterfactualMutator.mutate(raftSentence)
        val quorum = mutations.first { it.changedToken == "多数→少数" }
        assertEquals(MutationType.QUANTIFIER, quorum.type)
        assertEquals(QuizDifficulty.HARD, quorum.difficulty)
        assertEquals(
            "领导者通过 AppendEntries 复制日志，少数节点写入后更新 commit index",
            quorum.editedText
        )
    }

    @Test
    fun `改写记录必须能被重新验证`() {
        val mutation = CounterfactualMutator.mutate(raftSentence)
            .first { it.changedToken == "写入→读取" }
        val record = QuizMutation(
            type = mutation.type,
            originalText = mutation.originalText,
            editedText = mutation.editedText,
            changedToken = mutation.changedToken,
            sourceFrameId = "f0000"
        )
        assertTrue(CounterfactualMutator.verify(record))

        val forged = record.copy(editedText = record.originalText)
        assertFalse(CounterfactualMutator.verify(forged))
    }

    @Test
    fun `否定与关系反转都能被识别`() {
        val negation = CounterfactualMutator.mutate("这个配置允许客户端直接写入数据。")
            .firstOrNull { it.type == MutationType.NEGATION }
        assertTrue(negation != null && negation.editedText.contains("不允许"))

        val reversed = CounterfactualMutator.mutate("日志复制过程依赖领导者节点。")
            .firstOrNull { it.type == MutationType.RELATION_REVERSE }
        assertTrue(reversed != null && reversed.editedText == "领导者节点依赖日志复制过程")
    }

    @Test
    fun `已否定的词不会再被否定改写`() {
        val mutations = CounterfactualMutator.mutate("该配置不允许客户端复制日志。")
        assertTrue(mutations.none { it.changedToken == "允许→不允许" })
        assertTrue(mutations.none { it.editedText.contains("不不") })

        val modal = CounterfactualMutator.mutate("该机制无法保证数据一定写入。")
        assertTrue(modal.none { it.changedToken == "保证→无法保证" })
        assertTrue(modal.none { it.editedText.contains("无法无法") })
    }

    @Test
    fun `否定改写跳过已否定位置并改用其他命中`() {
        val mutations = CounterfactualMutator.mutate("不允许直接写入，但允许后台写入。")
        val negation = mutations.first { it.changedToken == "允许→不允许" }
        assertEquals("不允许直接写入，但不允许后台写入", negation.editedText)
    }

    @Test
    fun `双重否定的伪造改写记录会被拒绝`() {
        val forged = QuizMutation(
            type = MutationType.NEGATION,
            originalText = "该配置不允许客户端复制日志",
            editedText = "该配置不不允许客户端复制日志",
            changedToken = "允许→不允许",
            sourceFrameId = "f0000"
        )
        assertFalse(CounterfactualMutator.verify(forged))

        val legitimate = CounterfactualMutator.mutate("不允许直接写入，但允许后台写入。")
            .first { it.changedToken == "允许→不允许" }
        val record = QuizMutation(
            type = legitimate.type,
            originalText = legitimate.originalText,
            editedText = legitimate.editedText,
            changedToken = legitimate.changedToken,
            sourceFrameId = "f0000"
        )
        assertTrue(CounterfactualMutator.verify(record))
    }
}
