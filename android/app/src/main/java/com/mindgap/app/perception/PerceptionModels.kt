package com.mindgap.app.perception

import kotlinx.serialization.Serializable

/**
 * M2 感知结果：语音转写 + 屏幕 OCR，按会话落盘为
 * filesDir/capture/{sessionId}.perception.json，供 M3 时空对齐消费。
 */

@Serializable
data class SpeechTranscript(
    val segmentId: String,
    /** 相对会话开始的毫秒偏移（已加音频起点偏移）。 */
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 离线批量转写，每条都是最终结果。 */
    val isFinal: Boolean = true,
    /** sherpa-onnx 的 Android API 未暴露逐 token 概率，暂不填伪置信度。 */
    val asrConfidence: Double? = null
)

@Serializable
data class OcrFrameText(
    val frameId: String,
    /** 关键帧在会话时间轴上的位置（毫秒）。 */
    val t: Long,
    val text: String,
    val textHash: String,
    val isDuplicate: Boolean,
    /** 与上一段有效文本的 Dice 相似度；首帧为 0。 */
    val similarity: Double,
    val imagePath: String? = null
)

@Serializable
data class PerceptionStats(
    val sampledFrames: Int = 0,
    val keyframes: Int = 0,
    val ocrFrames: Int = 0,
    val ocrDuplicates: Int = 0,
    val speechSegments: Int = 0,
    val speechChars: Int = 0,
    /** 感知哈希去重率：1 - 关键帧 / 采样帧。 */
    val frameDedupRatio: Double = 0.0,
    /** OCR 文本去重率：重复帧 / OCR 有效帧。 */
    val textDedupRatio: Double = 0.0,
    val elapsedMs: Long = 0L
)

@Serializable
data class PerceptionResult(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val createdAtWallClock: Long,
    /** 音频起点在会话时间轴上的位置；旧会话没有 AUDIO_START 时为 0。 */
    val audioOriginMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val speech: List<SpeechTranscript> = emptyList(),
    val ocr: List<OcrFrameText> = emptyList(),
    val stats: PerceptionStats = PerceptionStats(),
    val notes: List<String> = emptyList()
)
