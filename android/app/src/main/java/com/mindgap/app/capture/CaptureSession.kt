package com.mindgap.app.capture

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

enum class CapturePhase { IDLE, RUNNING, PAUSED, FINISHED }

data class CaptureSnapshot(
    val phase: CapturePhase = CapturePhase.IDLE,
    val sessionId: String? = null,
    val startedAtWallClock: Long = 0L,
    val elapsedMs: Long = 0L,
    val screenTextCount: Int = 0,
    val screenFrameCount: Int = 0,
    val keyframeCount: Int = 0,
    val audioSegmentCount: Int = 0,
    val actionCount: Int = 0,
    val lastEvent: String = "",
    val eventFile: String? = null,
    val audioFile: String? = null,
    val message: String = ""
)

/**
 * 采集会话的唯一入口：服务、无障碍、UI 三方都只跟它打交道。
 * 状态放在 StateFlow 里，UI 直接 collect，不需要 bindService。
 */
object CaptureSession {
    private val _state = MutableStateFlow(CaptureSnapshot())
    val state: StateFlow<CaptureSnapshot> = _state.asStateFlow()

    private var startElapsedRealtime = 0L
    private var lastScreenTextHash: String? = null
    private var lastScreenTextAt = 0L

    fun start(context: Context, includeAudio: Boolean): String {
        val sessionId = UUID.randomUUID().toString()
        startElapsedRealtime = SystemClock.elapsedRealtime()
        lastScreenTextHash = null
        lastScreenTextAt = 0L

        val jsonl = EventStore.open(context, sessionId)
        val audio: File? = if (includeAudio) {
            EventStore.audioFile(context, sessionId).also { it.delete() }
        } else {
            null
        }

        _state.value = CaptureSnapshot(
            phase = CapturePhase.RUNNING,
            sessionId = sessionId,
            startedAtWallClock = System.currentTimeMillis(),
            eventFile = jsonl.absolutePath,
            audioFile = audio?.absolutePath,
            message = if (includeAudio) "" else "未授权麦克风，本次仅采集屏幕与文本"
        )
        emit(RawEventType.MARKER, note = "session_start")
        return sessionId
    }

    fun pause() {
        if (_state.value.phase != CapturePhase.RUNNING) return
        emit(RawEventType.MARKER, note = "pause")
        _state.value = _state.value.copy(phase = CapturePhase.PAUSED, message = "已暂停，事件不再写入")
    }

    fun resume() {
        if (_state.value.phase != CapturePhase.PAUSED) return
        _state.value = _state.value.copy(phase = CapturePhase.RUNNING, message = "")
        emit(RawEventType.MARKER, note = "resume")
    }

    fun finish(message: String = "采集结束，原始事件已落盘") {
        val phase = _state.value.phase
        if (phase == CapturePhase.IDLE || phase == CapturePhase.FINISHED) return
        emit(RawEventType.MARKER, note = "session_end")
        EventStore.close()
        _state.value = _state.value.copy(
            phase = CapturePhase.FINISHED,
            elapsedMs = nowMs(),
            message = message
        )
    }

    fun onTick(elapsedMs: Long) {
        val phase = _state.value.phase
        if (phase == CapturePhase.RUNNING || phase == CapturePhase.PAUSED) {
            _state.value = _state.value.copy(elapsedMs = elapsedMs)
        }
    }

    fun nowMs(): Long =
        if (startElapsedRealtime == 0L) 0L else SystemClock.elapsedRealtime() - startElapsedRealtime

    fun recordScreenText(text: String, hash: String, appPackage: String?) {
        if (_state.value.phase != CapturePhase.RUNNING) return
        val now = nowMs()
        if (hash == lastScreenTextHash && now - lastScreenTextAt < 1500) return
        lastScreenTextHash = hash
        lastScreenTextAt = now
        emit(
            RawEventType.SCREEN_TEXT,
            text = text.take(4000),
            textHash = hash,
            appPackage = appPackage
        )
    }

    fun recordScreenFrame(hash: String, luminance: Double, imagePath: String? = null) {
        if (_state.value.phase != CapturePhase.RUNNING) return
        emit(RawEventType.SCREEN_FRAME, frameHash = hash, luminance = luminance, imagePath = imagePath)
    }

    /**
     * 记录音频线程真正开始录音的会话时刻。
     * PCM 文件从此刻起按 16k/mono/16bit 连续写入，M2 用它把采样位置换算回会话时间。
     */
    fun recordAudioStart(originMs: Long) {
        if (_state.value.phase != CapturePhase.RUNNING) return
        emit(RawEventType.AUDIO_START, startMs = originMs, note = "16k/mono/pcm16")
    }

    fun recordAudioSegment(startMs: Long, endMs: Long, rmsDb: Double) {
        if (_state.value.phase != CapturePhase.RUNNING) return
        emit(RawEventType.AUDIO_SEGMENT, startMs = startMs, endMs = endMs, rmsDb = rmsDb)
    }

    fun recordAction(type: RawEventType, appPackage: String?) {
        if (_state.value.phase != CapturePhase.RUNNING) return
        emit(type, appPackage = appPackage)
    }

    fun recordMarker(note: String) {
        if (_state.value.phase == CapturePhase.IDLE) return
        emit(RawEventType.MARKER, note = note)
    }

    /** 删除会话数据后清空界面状态；采集中不处理。 */
    fun resetAfterDelete(sessionId: String) {
        val current = _state.value
        if (current.sessionId != sessionId) return
        if (current.phase == CapturePhase.RUNNING || current.phase == CapturePhase.PAUSED) return
        startElapsedRealtime = 0L
        lastScreenTextHash = null
        lastScreenTextAt = 0L
        _state.value = CaptureSnapshot()
    }

    @Synchronized
    private fun emit(
        type: RawEventType,
        text: String? = null,
        textHash: String? = null,
        appPackage: String? = null,
        rmsDb: Double? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        frameHash: String? = null,
        luminance: Double? = null,
        imagePath: String? = null,
        note: String? = null
    ) {
        val event = RawEvent(
            t = nowMs(),
            type = type,
            text = text,
            textHash = textHash,
            appPackage = appPackage,
            rmsDb = rmsDb,
            startMs = startMs,
            endMs = endMs,
            frameHash = frameHash,
            luminance = luminance,
            imagePath = imagePath,
            note = note
        )
        EventStore.append(event)

        val snapshot = _state.value
        val isKeyframe = type == RawEventType.SCREEN_FRAME && imagePath != null
        _state.value = snapshot.copy(
            screenTextCount = snapshot.screenTextCount + if (type == RawEventType.SCREEN_TEXT) 1 else 0,
            screenFrameCount = snapshot.screenFrameCount + if (type == RawEventType.SCREEN_FRAME) 1 else 0,
            keyframeCount = snapshot.keyframeCount + if (isKeyframe) 1 else 0,
            audioSegmentCount = snapshot.audioSegmentCount + if (type == RawEventType.AUDIO_SEGMENT) 1 else 0,
            actionCount = snapshot.actionCount + if (
                type == RawEventType.APP_SWITCH ||
                type == RawEventType.SCROLL ||
                type == RawEventType.CLICK ||
                type == RawEventType.SEARCH ||
                type == RawEventType.ERASE
            ) 1 else 0,
            lastEvent = describe(event)
        )
    }

    private fun describe(event: RawEvent): String {
        val time = "${event.t / 1000.0}s"
        return when (event.type) {
            RawEventType.SCREEN_TEXT -> "$time 屏幕文本：${event.text?.take(24).orEmpty()}…"
            RawEventType.SCREEN_FRAME -> "$time 画面指纹 ${event.frameHash}" +
                (if (event.imagePath != null) "（关键帧）" else "")
            RawEventType.AUDIO_START -> "$time 音频起点 ${(event.startMs ?: 0) / 1000.0}s"
            RawEventType.AUDIO_SEGMENT -> "$time 语音段 ${(event.startMs ?: 0) / 1000.0}s – ${(event.endMs ?: 0) / 1000.0}s"
            RawEventType.APP_SWITCH -> "$time 切到 ${event.appPackage}"
            RawEventType.SCROLL -> "$time 滚动"
            RawEventType.CLICK -> "$time 点击"
            RawEventType.SEARCH -> "$time 搜索输入（内容不落盘）"
            RawEventType.ERASE -> "$time 删除/改写（内容不落盘）"
            RawEventType.MARKER -> "$time 标记 ${event.note}"
        }
    }
}

/** 采集结束后用于回放的文件读取入口，UI 侧不必知道目录结构。 */
fun loadRawEvents(context: Context, sessionId: String): List<RawEvent> =
    EventStore.readAll(context, sessionId)
