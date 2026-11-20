package com.mindgap.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与 schema/timeline.schema.json 一一对应的数据模型。
 * 字段命名与 JSON 完全一致，解析时开启 ignoreUnknownKeys，便于 Schema 向前兼容。
 */
@Serializable
data class TimelineSession(
    val schemaVersion: String,
    val sessionId: String,
    val app: AppInfo? = null,
    val device: DeviceInfo? = null,
    val capture: CaptureMeta,
    val document: Document,
    val frames: List<TimelineFrame>,
    val stuckEvents: List<StuckEvent> = emptyList(),
    val quizItems: List<QuizItem> = emptyList(),
    val notes: String? = null
)

@Serializable
data class AppInfo(
    val name: String,
    val version: String,
    val buildType: String? = null
)

@Serializable
data class DeviceInfo(
    val model: String,
    val androidApi: Int,
    val locale: String,
    val screen: ScreenSize? = null,
    val onDeviceModel: OnDeviceModel? = null
)

@Serializable
data class ScreenSize(val width: Int, val height: Int)

@Serializable
data class OnDeviceModel(
    val asr: String? = null,
    val ocr: String? = null
)

@Serializable
data class CaptureMeta(
    val startedAt: String,
    val endedAt: String? = null,
    val bucketMs: Int = 500,
    val screenSampleMs: Int? = null,
    val audioSampleRate: Int? = null,
    val sources: List<SourceKind> = emptyList(),
    val consent: Consent? = null
)

@Serializable
enum class SourceKind {
    @SerialName("screen") SCREEN,
    @SerialName("accessibility") ACCESSIBILITY,
    @SerialName("audio") AUDIO
}

@Serializable
enum class Consent {
    @SerialName("granted") GRANTED,
    @SerialName("revoked") REVOKED
}

@Serializable
data class Document(
    val docId: String,
    val title: String,
    val appPackage: String? = null,
    val totalChars: Int = 0,
    val paragraphs: List<Paragraph> = emptyList()
)

@Serializable
data class Paragraph(
    val paragraphId: String,
    val index: Int,
    val heading: String? = null,
    val charStart: Int,
    val charEnd: Int,
    val preview: String? = null,
    val terms: List<String> = emptyList()
)

@Serializable
data class TimelineFrame(
    val frameId: String,
    val t: Long,
    val capturedAt: String,
    val screen: ScreenObservation? = null,
    val speech: SpeechSegment? = null,
    val action: Action,
    val features: FrameFeatures? = null
)

@Serializable
data class ScreenObservation(
    val docId: String,
    val paragraphId: String? = null,
    val text: String? = null,
    val textHash: String,
    val isDuplicate: Boolean,
    val progress: Double? = null,
    val ocrConfidence: Double? = null,
    val source: ScreenSource? = null
)

@Serializable
enum class ScreenSource {
    @SerialName("accessibility") ACCESSIBILITY,
    @SerialName("ocr") OCR
}

@Serializable
data class SpeechSegment(
    val segmentId: String,
    val text: String,
    val isFinal: Boolean,
    val startMs: Long,
    val endMs: Long? = null,
    val asrConfidence: Double? = null,
    val lang: String? = null
)

@Serializable
data class Action(
    val type: ActionType,
    val appPackage: String? = null,
    val targetParagraphId: String? = null,
    val scrollDelta: Int? = null,
    val query: String? = null
)

@Serializable
enum class ActionType {
    @SerialName("none") NONE,
    @SerialName("scroll") SCROLL,
    @SerialName("click") CLICK,
    @SerialName("switch_app") SWITCH_APP,
    @SerialName("search") SEARCH,
    @SerialName("erase") ERASE,
    @SerialName("type_text") TYPE_TEXT,
    @SerialName("idle") IDLE
}

@Serializable
data class FrameFeatures(
    val dwellMs: Long = 0,
    val revisitCount: Int = 0,
    val isStalled: Boolean = false,
    val attentionScore: Double? = null
)

@Serializable
data class StuckEvent(
    val eventId: String,
    val kind: StuckKind,
    val paragraphId: String? = null,
    val term: String? = null,
    val fromMs: Long,
    val toMs: Long,
    val fromFrameId: String? = null,
    val toFrameId: String? = null,
    val evidenceFrameIds: List<String>,
    val confidence: Double,
    val signals: StuckSignals? = null,
    val summary: String? = null
)

@Serializable
enum class StuckKind {
    @SerialName("time_anomaly") TIME_ANOMALY,
    @SerialName("behavior_anomaly") BEHAVIOR_ANOMALY,
    @SerialName("semantic_anomaly") SEMANTIC_ANOMALY
}

@Serializable
data class StuckSignals(
    val dwellMs: Long? = null,
    val baselineDwellMs: Long? = null,
    val revisitCount: Int? = null,
    val switchedApp: Boolean? = null,
    val searchQuery: String? = null,
    val skippedTerm: String? = null,
    val skippedSentence: String? = null
)

@Serializable
data class QuizItem(
    val quizId: String,
    val eventId: String,
    val mode: QuizMode,
    val questionType: String? = null,
    val difficulty: String? = null,
    val stem: String,
    val options: List<QuizOption>,
    val explanation: String? = null,
    val model: String? = null,
    val createdAt: String? = null
)

@Serializable
enum class QuizMode {
    @SerialName("online") ONLINE,
    @SerialName("offline_template") OFFLINE_TEMPLATE
}

@Serializable
data class QuizOption(
    val optionId: String,
    val text: String,
    val verdict: Verdict,
    val rationale: String? = null,
    val evidenceFrameIds: List<String> = emptyList(),
    /** 仅干扰项存在：这一项由原文哪一句、按什么规则改写而来。 */
    val mutation: QuizMutation? = null
)

@Serializable
data class QuizMutation(
    val type: MutationType,
    val originalText: String,
    val editedText: String,
    val changedToken: String? = null,
    val sourceFrameId: String
)

@Serializable
enum class MutationType {
    @SerialName("negation") NEGATION,
    @SerialName("modality") MODALITY,
    @SerialName("quantifier") QUANTIFIER,
    @SerialName("numeric") NUMERIC,
    @SerialName("relation_reverse") RELATION_REVERSE
}

@Serializable
enum class Verdict {
    @SerialName("correct") CORRECT,
    @SerialName("distractor") DISTRACTOR
}
