package com.mindgap.app.judge

import android.content.Context
import android.os.SystemClock
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.align.TimelineStore
import com.mindgap.app.data.model.FrameFeatures
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.quiz.QuizPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class JudgementStage { IDLE, RUNNING, DONE, FAILED }

data class JudgementSnapshot(
    val stage: JudgementStage = JudgementStage.IDLE,
    val sessionId: String? = null,
    val progress: Float = 0f,
    val step: String = "",
    val message: String = "",
    val report: JudgementReport? = null,
    val elapsedMs: Long = 0L
)

/**
 * M4 编排：对 M3 时间轴跑规则判定，把 stuckEvents 回写时间轴并落盘。
 * 回写走 AlignmentPipeline，回放/档案/找茬页无需各自维护状态。
 */
object JudgementPipeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(JudgementSnapshot())
    val state: StateFlow<JudgementSnapshot> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun start(context: Context, session: TimelineSession) {
        if (running) return
        running = true
        // 卡壳事件变化后旧题目失效
        QuizPipeline.invalidate(session.sessionId)
        val app = context.applicationContext
        _state.value = JudgementSnapshot(
            stage = JudgementStage.RUNNING,
            sessionId = session.sessionId,
            progress = 0.1f,
            step = "构建段落访问序列"
        )

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                _state.value = _state.value.copy(step = "校准基线并匹配规则", progress = 0.45f)
                val outcome = StuckDetector(session).detect()

                _state.value = _state.value.copy(step = "写入判定结果", progress = 0.85f)
                if (!AlignmentPipeline.isCurrent(session)) {
                    throw IllegalStateException("时间轴已更新，本次判定结果作废，请重跑")
                }
                val evidenceIds = outcome.events
                    .flatMap { it.evidenceFrameIds }
                    .toSet()
                val frames = session.frames.map { frame ->
                    val stalled = frame.frameId in evidenceIds
                    val features = frame.features ?: FrameFeatures()
                    if (features.isStalled == stalled) {
                        frame
                    } else {
                        frame.copy(features = features.copy(isStalled = stalled))
                    }
                }
                val judged = session.copy(
                    frames = frames,
                    stuckEvents = outcome.events,
                    quizItems = emptyList(),
                    notes = mergeNotes(session.notes, outcome.report.noteText())
                )
                TimelineStore.save(app, judged)
                AlignmentPipeline.updateSession(judged)

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                _state.value = JudgementSnapshot(
                    stage = JudgementStage.DONE,
                    sessionId = session.sessionId,
                    progress = 1f,
                    step = "完成",
                    message = "${outcome.events.size} 个卡壳事件",
                    report = outcome.report,
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                _state.value = JudgementSnapshot(
                    stage = JudgementStage.FAILED,
                    sessionId = session.sessionId,
                    step = "失败",
                    message = e.message ?: e.javaClass.simpleName,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                )
            } finally {
                running = false
            }
        }
    }

    /** M3 重新对齐时清除旧的判定状态，避免界面展示过期基线。 */
    fun invalidate(sessionId: String) {
        if (running) return
        if (_state.value.sessionId == sessionId) {
            _state.value = JudgementSnapshot()
        }
    }

    /** 重跑时去掉上一条 M4 备注，避免 notes 无限增长。 */
    private fun mergeNotes(notes: String?, judgementNote: String): String {
        val base = notes?.substringBefore("；M4 ")?.trim().orEmpty()
        return if (base.isEmpty()) judgementNote else "$base；$judgementNote"
    }
}
