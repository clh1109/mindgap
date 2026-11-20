package com.mindgap.app.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * M1 采集层的原始事件，直接按行写入 JSONL。
 * 与 TimelineFrame 的区别：这里是未经对齐的原始流，M3 才把它聚合成 500ms 帧。
 */
@Serializable
enum class RawEventType {
    @SerialName("screen_text") SCREEN_TEXT,
    @SerialName("screen_frame") SCREEN_FRAME,
    /** 音频线程真正开始录音的时刻（t = 会话偏移），PCM 文件从此处起算。 */
    @SerialName("audio_start") AUDIO_START,
    @SerialName("audio_segment") AUDIO_SEGMENT,
    @SerialName("app_switch") APP_SWITCH,
    @SerialName("scroll") SCROLL,
    @SerialName("click") CLICK,
    /** 在搜索框中输入；只记录动作，不记录输入内容。 */
    @SerialName("search") SEARCH,
    /** 删除或改写输入；只记录动作，不记录前后文本。 */
    @SerialName("erase") ERASE,
    @SerialName("marker") MARKER
}

@Serializable
data class RawEvent(
    /** 相对会话开始的毫秒偏移，与 TimelineFrame.t 同基准。 */
    val t: Long,
    val type: RawEventType,
    val text: String? = null,
    val textHash: String? = null,
    val appPackage: String? = null,
    val rmsDb: Double? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val frameHash: String? = null,
    val luminance: Double? = null,
    /** 仅关键帧（感知哈希判定为画面变化）才有值，指向本地 JPEG。 */
    val imagePath: String? = null,
    val note: String? = null
)
