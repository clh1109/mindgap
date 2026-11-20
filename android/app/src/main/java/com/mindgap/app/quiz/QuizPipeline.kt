package com.mindgap.app.quiz

import android.content.Context
import android.os.SystemClock
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.align.TimelineStore
import com.mindgap.app.cloud.CloudQuizClient
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.QuizItem
import com.mindgap.app.data.model.TimelineSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class QuizStage { IDLE, RUNNING, DONE, FAILED }

data class QuizSnapshot(
    val stage: QuizStage = QuizStage.IDLE,
    val sessionId: String? = null,
    val progress: Float = 0f,
    val step: String = "",
    val message: String = "",
    val generated: Int = 0,
    val skipped: Int = 0,
    val elapsedMs: Long = 0L
)

/**
 * M5 编排：对卡壳事件生成三选项找茬题，逐项校验证据后回写时间轴。
 * 当前离线模板模式；后续接云端 LLM 时只替换 QuizGenerator，校验与回写链路不变。
 */
object QuizPipeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(QuizSnapshot())
    val state: StateFlow<QuizSnapshot> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun start(context: Context, session: TimelineSession) {
        if (running) return
        running = true
        val app = context.applicationContext
        _state.value = QuizSnapshot(
            stage = QuizStage.RUNNING,
            sessionId = session.sessionId,
            progress = 0.1f,
            step = "压缩理解状态"
        )

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                _state.value = _state.value.copy(step = "生成三选项并校验证据", progress = 0.4f)
                val result = QuizGenerator(session).generate()

                _state.value = _state.value.copy(step = "写入找茬题", progress = 0.85f)
                if (!AlignmentPipeline.isCurrent(session)) {
                    throw IllegalStateException("时间轴已更新，本次找茬题作废，请重跑")
                }
                val updated = session.copy(
                    quizItems = result.quizzes,
                    notes = mergeNotes(session.notes, result.note)
                )
                TimelineStore.save(app, updated)
                AlignmentPipeline.updateSession(updated)

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                _state.value = QuizSnapshot(
                    stage = QuizStage.DONE,
                    sessionId = session.sessionId,
                    progress = 1f,
                    step = "完成",
                    message = "${result.quizzes.size} 题 / 跳过 ${result.skippedEvents} 个事件",
                    generated = result.quizzes.size,
                    skipped = result.skippedEvents,
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                _state.value = QuizSnapshot(
                    stage = QuizStage.FAILED,
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

    /**
     * 云端生成：逐事件调用云端，失败或证据不合法时回退离线模板。
     * 需要 capture.consent == granted；未授权直接失败，不发请求。
     */
    fun startOnline(context: Context, session: TimelineSession) {
        if (running) return
        if (session.capture.consent != Consent.GRANTED) {
            _state.value = QuizSnapshot(
                stage = QuizStage.FAILED,
                sessionId = session.sessionId,
                step = "未授权",
                message = "未授权云端生成，请先在数据与隐私中打开上传授权"
            )
            return
        }
        running = true
        val app = context.applicationContext
        _state.value = QuizSnapshot(
            stage = QuizStage.RUNNING,
            sessionId = session.sessionId,
            progress = 0.05f,
            step = "准备压缩状态"
        )

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val local = QuizGenerator(session).generate()
                val localByEvent = local.quizzes.associateBy { it.eventId }
                val quizzes = mutableListOf<QuizItem>()
                var onlineCount = 0
                var cloudAvailable = true
                val events = session.stuckEvents

                events.forEachIndexed { index, event ->
                    _state.value = _state.value.copy(
                        step = "云端生成 ${index + 1}/${events.size}",
                        progress = if (events.isEmpty()) {
                            0.8f
                        } else {
                            0.1f + 0.7f * (index + 1) / events.size
                        }
                    )
                    val onlineQuiz = if (cloudAvailable) {
                        CloudQuizClient.generate(session, event).also { quiz ->
                            if (quiz == null) cloudAvailable = false
                        }
                    } else {
                        null
                    }
                    if (onlineQuiz != null) {
                        quizzes += onlineQuiz
                        onlineCount++
                    } else {
                        localByEvent[event.eventId]?.let { quizzes += it }
                    }
                }

                if (!AlignmentPipeline.isCurrent(session)) {
                    throw IllegalStateException("时间轴已更新，本次找茬题作废，请重跑")
                }
                val fallbackCount = quizzes.size - onlineCount
                val outcome = if (cloudAvailable) {
                    "在线 $onlineCount / 离线 $fallbackCount"
                } else {
                    "在线 $onlineCount / 离线 $fallbackCount（云端不可用，已回退离线模板）"
                }
                val updated = session.copy(
                    quizItems = quizzes,
                    notes = mergeNotes(
                        session.notes,
                        "M5 云端生成：$outcome；逐项证据已校验"
                    )
                )
                TimelineStore.save(app, updated)
                AlignmentPipeline.updateSession(updated)

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                _state.value = QuizSnapshot(
                    stage = QuizStage.DONE,
                    sessionId = session.sessionId,
                    progress = 1f,
                    step = "完成",
                    message = outcome,
                    generated = quizzes.size,
                    skipped = (events.size - quizzes.size).coerceAtLeast(0),
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                _state.value = QuizSnapshot(
                    stage = QuizStage.FAILED,
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

    /** M3/M4 重跑时清除旧题目状态。 */
    fun invalidate(sessionId: String) {
        if (running) return
        if (_state.value.sessionId == sessionId) {
            _state.value = QuizSnapshot()
        }
    }

    private fun mergeNotes(notes: String?, quizNote: String): String {
        val base = notes?.substringBefore("；M5 ")?.trim().orEmpty()
        return if (base.isEmpty()) quizNote else "$base；$quizNote"
    }
}
