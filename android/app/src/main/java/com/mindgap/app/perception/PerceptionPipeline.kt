package com.mindgap.app.perception

import android.content.Context
import android.os.SystemClock
import com.mindgap.app.capture.EventStore
import com.mindgap.app.capture.RawEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class PerceptionStage { IDLE, RUNNING, DONE, FAILED }

data class PerceptionSnapshot(
    val stage: PerceptionStage = PerceptionStage.IDLE,
    val sessionId: String? = null,
    val progress: Float = 0f,
    val step: String = "",
    val message: String = "",
    val result: PerceptionResult? = null,
    val elapsedMs: Long = 0L
)

/**
 * M2 编排：读取一次会话的原始事件 → 语音转写 → 关键帧 OCR → 落盘感知结果。
 * 各阶段相互独立，ASR 模型缺失时仍会执行 OCR，并把原因写进 notes。
 */
object PerceptionPipeline {
    private const val TEXT_DUPLICATE_THRESHOLD = 0.90
    private const val PCM_BYTES_PER_MS = 32 // 16kHz × 16bit 单声道

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PerceptionSnapshot())
    val state: StateFlow<PerceptionSnapshot> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun start(context: Context, sessionId: String) {
        if (running) return
        running = true
        val app = context.applicationContext
        _state.value = PerceptionSnapshot(
            stage = PerceptionStage.RUNNING,
            sessionId = sessionId,
            step = "读取采集数据"
        )

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val notes = mutableListOf<String>()
            val speech = mutableListOf<SpeechTranscript>()
            val ocr = mutableListOf<OcrFrameText>()
            var sampledFrames = 0
            var keyframeCount = 0

            try {
                val events = EventStore.readAll(app, sessionId)
                if (events.isEmpty()) {
                    throw IllegalStateException("没有找到本次会话的采集事件")
                }

                val audioOriginMs = events
                    .firstOrNull { it.type == RawEventType.AUDIO_START }
                    ?.startMs
                    ?: 0L
                val pcmFile = EventStore.audioFile(app, sessionId)
                val audioDurationMs = if (pcmFile.exists()) pcmFile.length() / PCM_BYTES_PER_MS else 0L

                // 1) 语音：Silero VAD 分段 + Paraformer int8 识别
                if (pcmFile.length() > 0 && ModelManager.isReady(app)) {
                    _state.value = _state.value.copy(
                        step = "语音分段与离线转写",
                        progress = 0.03f
                    )
                    val outcome = runCatching {
                        OfflineTranscriber(
                            modelFile = ModelManager.asrModelFile(app),
                            tokensFile = ModelManager.asrTokensFile(app),
                            vadFile = ModelManager.vadModelFile(app)
                        ).transcribe(pcmFile, audioOriginMs) { fraction ->
                            _state.value = _state.value.copy(progress = 0.03f + 0.55f * fraction)
                        }
                    }
                    outcome.onSuccess { speech.addAll(it) }
                        .onFailure {
                            notes += "离线转写失败：${it.message ?: it.javaClass.simpleName}"
                        }
                } else if (!ModelManager.isReady(app)) {
                    notes += "离线 ASR 模型未下载，本次只执行 OCR"
                } else {
                    notes += "本次没有音频数据，跳过转写"
                }

                // 2) 屏幕：对感知哈希去重后的关键帧做中文 OCR
                val frames = events.filter { it.type == RawEventType.SCREEN_FRAME }
                sampledFrames = frames.size
                val keyframes = frames.filter { !it.imagePath.isNullOrEmpty() }
                keyframeCount = keyframes.size

                if (keyframes.isNotEmpty()) {
                    val engine = OcrEngine()
                    var lastAccepted = ""
                    try {
                        keyframes.forEachIndexed { index, event ->
                            val path = event.imagePath ?: return@forEachIndexed
                            val file = File(path)
                            if (!file.exists()) return@forEachIndexed

                            val bitmap = OcrEngine.decode(file) ?: return@forEachIndexed
                            try {
                                val text = FrameDedup.normalize(engine.recognize(bitmap))
                                if (text.isNotEmpty()) {
                                    val compact = FrameDedup.compact(text)
                                    val similarity = if (lastAccepted.isEmpty()) {
                                        0.0
                                    } else {
                                        FrameDedup.similarity(lastAccepted, compact)
                                    }
                                    val duplicate = lastAccepted.isNotEmpty() &&
                                        similarity >= TEXT_DUPLICATE_THRESHOLD
                                    if (!duplicate) lastAccepted = compact

                                    ocr += OcrFrameText(
                                        frameId = "f${ocr.size + 1}",
                                        t = event.t,
                                        text = text,
                                        textHash = FrameDedup.hash(compact),
                                        isDuplicate = duplicate,
                                        similarity = similarity,
                                        imagePath = path
                                    )
                                }
                            } catch (e: Exception) {
                                notes += "第 ${index + 1} 个关键帧 OCR 失败：${e.message}"
                            } finally {
                                bitmap.recycle()
                            }

                            _state.value = _state.value.copy(
                                step = "OCR ${index + 1}/${keyframes.size}",
                                progress = 0.60f + 0.37f * (index + 1) / keyframes.size
                            )
                        }
                    } finally {
                        engine.close()
                    }
                } else if (frames.isNotEmpty()) {
                    notes += "本次采集没有保存关键帧（画面未变化或采集端未升级）"
                }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val duplicates = ocr.count { it.isDuplicate }
                val stats = PerceptionStats(
                    sampledFrames = sampledFrames,
                    keyframes = keyframeCount,
                    ocrFrames = ocr.size,
                    ocrDuplicates = duplicates,
                    speechSegments = speech.size,
                    speechChars = speech.sumOf { it.text.length },
                    frameDedupRatio = if (sampledFrames > 0) {
                        1.0 - keyframeCount.toDouble() / sampledFrames
                    } else {
                        0.0
                    },
                    textDedupRatio = if (ocr.isNotEmpty()) {
                        duplicates.toDouble() / ocr.size
                    } else {
                        0.0
                    },
                    elapsedMs = elapsedMs
                )
                val result = PerceptionResult(
                    sessionId = sessionId,
                    createdAtWallClock = System.currentTimeMillis(),
                    audioOriginMs = audioOriginMs,
                    audioDurationMs = audioDurationMs,
                    speech = speech,
                    ocr = ocr,
                    stats = stats,
                    notes = notes
                )
                PerceptionStore.save(app, result)

                _state.value = PerceptionSnapshot(
                    stage = PerceptionStage.DONE,
                    sessionId = sessionId,
                    progress = 1f,
                    step = "完成",
                    message = "语音 ${speech.size} 段 / OCR ${ocr.size} 帧",
                    result = result,
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                _state.value = PerceptionSnapshot(
                    stage = PerceptionStage.FAILED,
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

    /** 删除会话数据后清理内存中的感知结果。 */
    fun clear(sessionId: String) {
        if (running) return
        if (_state.value.sessionId == sessionId) {
            _state.value = PerceptionSnapshot()
        }
    }
}
