package com.mindgap.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 离线评测入口：直接跑真实判定器与出题器。
 * 运行：./gradlew :app:testDebugUnitTest --tests com.mindgap.app.eval.MindGapEvaluationTest
 * 报告：app/build/reports/mindgap-eval/report.md
 */
class MindGapEvaluationTest {
    @Test
    fun `评测语料能复现判定与出题指标`() {
        val fixturesDir = listOf(
            File("src/test/resources/eval"),
            File("app/src/test/resources/eval")
        ).firstOrNull { it.isDirectory } ?: File("src/test/resources/eval")

        val report = EvaluationRunner.run(
            fixturesDir = fixturesDir,
            reportDir = File("build/reports/mindgap-eval")
        )
        println(report.toMarkdown())

        assertEquals(15, report.sessionCount)
        assertTrue("应至少检出一个事件", report.detectedEvents > 0)
        assertTrue("精确率过低：${report.precision}", report.precision >= 0.5)
        assertTrue("召回率过低：${report.recall}", report.recall >= 0.5)
        assertTrue("题目必须全部通过证据与改写校验", report.quizValidationFailures == 0)
        assertTrue("应至少生成一道题", report.quizzesGenerated > 0)
        assertEquals(
            "每个干扰项都必须带可验证改写",
            report.distractors,
            report.distractorsWithMutation
        )
    }
}
