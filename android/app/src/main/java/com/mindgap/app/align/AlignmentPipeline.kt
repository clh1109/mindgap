package com.mindgap.app.align

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.mindgap.app.capture.EventStore
import com.mindgap.app.capture.RawEvent
import com.mindgap.app.capture.RawEventType
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.judge.JudgementPipeline
import com.mindgap.app.perception.ModelManager
import com.mindgap.app.perception.PerceptionResult
import com.mindgap.app.perception.PerceptionStore
import com.mindgap.app.quiz.QuizPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class AlignmentStage { IDLE, RUNNING, DONE, FAILED }

data class AlignmentSnapshot(
    val stage: AlignmentStage = AlignmentStage.IDLE,
    val sessionId: String? = null,
    val progress: Float = 0f,
    val step: String = "",
    val message: String = "",
    val session: TimelineSession? = null,
    val elapsedMs: Long = 0L
)

/**
 * M3 编排：读取采集事件与 M2 感知结果，按 500ms 桶对齐为 TimelineSession 并落盘。
 * 生成结果留在内存中，回放页与后续 M4 直接消费，不必重新读盘。
 */
object AlignmentPipeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AlignmentSnapshot())
    val state: StateFlow<AlignmentSnapshot> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun start(context: Context, sessionId: String, startedAtWallClock: Long) {
        if (running) return
        running = true
        // 重新对齐会让旧的判定与找茬题失效
        JudgementPipeline.invalidate(sessionId)
        QuizPipeline.invalidate(sessionId)
        val app = context.applicationContext
        _state.value = AlignmentSnapshot(
            stage = AlignmentStage.RUNNING,
            sessionId = sessionId,
            progress = 0.05f,
            step = "读取采集事件"
        )

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val events = EventStore.readAll(app, sessionId)
                if (events.isEmpty()) throw IllegalStateException("没有找到本次会话的采集事件")

                _state.value = _state.value.copy(step = "读取离线转写与 OCR", progress = 0.2f)
                val perception = PerceptionStore.load(app, sessionId)
                if (!hasSignal(events, perception)) {
                    throw IllegalStateException("没有可对齐的屏幕/语音/操作数据")
                }

                _state.value = _state.value.copy(step = "按 500ms 桶聚合", progress = 0.4f)
                val session = TimelineAligner(
                    sessionId = sessionId,
                    startedAtWallClock = startedAtWallClock,
                    events = events,
                    perception = perception,
                    meta = metaOf(app)
                ).align()

                _state.value = _state.value.copy(step = "写入时间轴", progress = 0.9f)
                TimelineStore.save(app, session)

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                _state.value = AlignmentSnapshot(
                    stage = AlignmentStage.DONE,
                    sessionId = sessionId,
                    progress = 1f,
                    step = "完成",
                    message = "500ms × ${session.frames.size} 帧 / ${session.document.paragraphs.size} 段",
                    session = session,
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                _state.value = AlignmentSnapshot(
                    stage = AlignmentStage.FAILED,
                    sessionId = sessionId,
                    step = "失败",
                    message = e.message ?: e.javaClass.simpleName,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                )
            } finally {
                running = false
            }
        }
    }

    /** 从成长档案打开历史会话：替换当前时间轴，回放与找茬立即切换。 */
    fun open(session: TimelineSession) {
        if (running) return
        _state.value = AlignmentSnapshot(
            stage = AlignmentStage.DONE,
            sessionId = session.sessionId,
            progress = 1f,
            step = "从档案打开",
            message = "${session.frames.size} 帧",
            session = session
        )
    }

    /** 应用重启后恢复最近一次时间轴，回放页无需重新对齐。 */
    fun restore(context: Context) {
        if (running) return
        val app = context.applicationContext
        scope.launch {
            val latest = TimelineStore.latest(app) ?: return@launch
            if (_state.value.stage != AlignmentStage.IDLE) return@launch
            _state.value = AlignmentSnapshot(
                stage = AlignmentStage.DONE,
                sessionId = latest.sessionId,
                progress = 1f,
                step = "已载入上次时间轴",
                message = "${latest.frames.size} 帧",
                session = latest
            )
        }
    }

    /** M4 回写判定结果：替换当前时间轴，回放/档案/找茬页立即生效。 */
    fun updateSession(session: TimelineSession) {
        val current = _state.value
        if (current.session?.sessionId != session.sessionId) return
        _state.value = current.copy(session = session)
    }

    /** 判定开始后时间轴是否仍是同一份，防止 M3 重跑后 M4 回写旧结果。 */
    fun isCurrent(session: TimelineSession): Boolean = _state.value.session === session

    /** 删除会话数据后清理内存中的时间轴。 */
    fun clear(sessionId: String) {
        if (running) return
        if (_state.value.sessionId == sessionId) {
            _state.value = AlignmentSnapshot()
        }
    }

    private fun hasSignal(events: List<RawEvent>, perception: PerceptionResult?): Boolean =
        events.any {
            it.type == RawEventType.SCREEN_TEXT ||
                it.type == RawEventType.SCREEN_FRAME ||
                it.type == RawEventType.AUDIO_SEGMENT ||
                it.type == RawEventType.APP_SWITCH ||
                it.type == RawEventType.SCROLL ||
                it.type == RawEventType.CLICK ||
                it.type == RawEventType.SEARCH ||
                it.type == RawEventType.ERASE
        } || !perception?.speech.isNullOrEmpty() || !perception?.ocr.isNullOrEmpty()

    private fun metaOf(context: Context): AlignmentMeta {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.3.0"
        val metrics = context.resources.displayMetrics
        return AlignmentMeta(
            deviceModel = Build.MODEL ?: "Android",
            androidApi = Build.VERSION.SDK_INT,
            locale = Locale.getDefault().toLanguageTag(),
            screenWidth = metrics.widthPixels.takeIf { it > 0 },
            screenHeight = metrics.heightPixels.takeIf { it > 0 },
            appVersion = version,
            asrModel = if (ModelManager.isReady(context)) "paraformer-zh-small-int8" else null,
            ocrModel = "mlkit-text-chinese"
        )
    }
}
