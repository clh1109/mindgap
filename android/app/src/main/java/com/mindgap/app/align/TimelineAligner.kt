package com.mindgap.app.align

import com.mindgap.app.capture.RawEvent
import com.mindgap.app.capture.RawEventType
import com.mindgap.app.data.model.Action
import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.AppInfo
import com.mindgap.app.data.model.CaptureMeta
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.DeviceInfo
import com.mindgap.app.data.model.Document
import com.mindgap.app.data.model.FrameFeatures
import com.mindgap.app.data.model.OnDeviceModel
import com.mindgap.app.data.model.Paragraph
import com.mindgap.app.data.model.ScreenObservation
import com.mindgap.app.data.model.ScreenSize
import com.mindgap.app.data.model.ScreenSource
import com.mindgap.app.data.model.SourceKind
import com.mindgap.app.data.model.SpeechSegment
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.perception.FrameDedup
import com.mindgap.app.perception.PerceptionResult
import com.mindgap.app.perception.SpeechTranscript
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

private const val DEFAULT_BUCKET_MS = 500L

/** 设备与模型信息，由流水线注入，便于对齐器保持纯函数。 */
data class AlignmentMeta(
    val deviceModel: String,
    val androidApi: Int,
    val locale: String,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val appVersion: String,
    val asrModel: String?,
    val ocrModel: String?
)

/**
 * M3 时空对齐：把 M1 原始事件与 M2 感知结果按 500ms 桶聚合为 TimelineSession。
 *
 * 屏幕：无障碍文本优先、OCR 兜底；应用切换后旧文本失效。
 * 语音：取与本桶重叠最多的转写段。
 * 操作：切应用 > 点击 > 滚动，同桶只保留优先级最高的一条。
 * 特征：停留时长与回看次数在此计算，isStalled 留给 M4，attentionScore 暂不臆造。
 * 暂停：暂停区间输出空帧且不累计停留，避免把离开时间算成卡壳。
 */
class TimelineAligner(
    private val sessionId: String,
    private val startedAtWallClock: Long,
    private val events: List<RawEvent>,
    private val perception: PerceptionResult?,
    private val meta: AlignmentMeta,
    private val bucketMs: Long = DEFAULT_BUCKET_MS
) {
    fun align(): TimelineSession {
        require(events.isNotEmpty()) { "没有采集事件" }
        require(bucketMs in 100L..5000L) { "时间桶宽度不合法" }

        val observations = buildObservations()
        val maxT = sessionEndMs()
        val frameCount = (((maxT + bucketMs - 1) / bucketMs).toInt()).coerceIn(1, MAX_FRAMES)
        val docId = "doc-${sessionId.take(8)}"

        val switches = events
            .filter { it.type == RawEventType.APP_SWITCH }
            .map { it.t to it.appPackage }
            .sortedBy { it.first }
        val bucketObservations = selectObservations(observations, switches, frameCount)

        // 只在真正被选中的观测序列上建段落，避免未被采用的 OCR 变体撑大文档
        val paragraphIndex = ParagraphIndex()
        val bucketParagraphs = arrayOfNulls<Paragraph>(frameCount)
        var previousHash: String? = null
        var previousParagraph: Paragraph? = null
        for (i in 0 until frameCount) {
            val observation = bucketObservations[i] ?: continue
            val paragraph = if (observation.textHash == previousHash && previousParagraph != null) {
                previousParagraph
            } else {
                paragraphIndex.resolve(observation)
            }
            bucketParagraphs[i] = paragraph
            previousHash = observation.textHash
            previousParagraph = paragraph
        }

        val paragraphs = paragraphIndex.paragraphs().ifEmpty {
            listOf(
                Paragraph(
                    paragraphId = "p000",
                    index = 0,
                    heading = null,
                    charStart = 0,
                    charEnd = 0,
                    preview = null
                )
            )
        }
        val totalChars = paragraphIndex.totalChars()
        val selected = bucketObservations.filterNotNull()
        val document = Document(
            docId = docId,
            title = documentTitle(selected),
            appPackage = dominantPackage(selected),
            totalChars = totalChars,
            paragraphs = paragraphs
        )

        val speech = perception?.speech.orEmpty().sortedBy { it.startMs }
        val actionsByBucket = buildActionBuckets(frameCount)
        val pauseIntervals = buildPauseIntervals(maxT)
        var pauseCursor = 0
        var speechCursor = 0
        var previousParagraphId: String? = null
        var dwellMs = 0L
        var previousScreenHash: String? = null
        val entryCounts = HashMap<String, Int>()

        val startWall = if (startedAtWallClock > 0L) {
            startedAtWallClock
        } else {
            System.currentTimeMillis() - maxT
        }
        val baseTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(startWall), ZoneId.systemDefault())

        val frames = ArrayList<TimelineFrame>(frameCount)
        for (i in 0 until frameCount) {
            val t = i * bucketMs
            val bucketEnd = t + bucketMs
            val frameId = String.format(Locale.ROOT, "f%04d", i)

            while (pauseCursor < pauseIntervals.size && pauseIntervals[pauseCursor].to <= t) {
                pauseCursor++
            }
            val pause = pauseIntervals.getOrNull(pauseCursor)
            // 只要与暂停区间有交集就按空帧处理，避免边界桶把离开时间计入停留
            if (pause != null && t < pause.to && bucketEnd > pause.from) {
                // 暂停必须切断段落上下文：恢复后停留重新累计、文本重新输出、记一次新进入，
                // 与 eval/generate_fixtures.py 的暂停重置保持一致
                dwellMs = 0L
                previousParagraphId = null
                previousScreenHash = null
                frames += TimelineFrame(
                    frameId = frameId,
                    t = t,
                    capturedAt = baseTime.plusNanos(t * NANOS_PER_MS).toString(),
                    screen = null,
                    speech = null,
                    action = Action(ActionType.NONE),
                    features = FrameFeatures(dwellMs = 0, revisitCount = 0, isStalled = false)
                )
                continue
            }

            val observation = bucketObservations[i]
            val paragraph = bucketParagraphs[i]
            val screen = observation?.let {
                val duplicate = it.textHash == previousScreenHash
                previousScreenHash = it.textHash
                val progress = if (totalChars > 0 && paragraph != null) {
                    (paragraph.charEnd.toDouble() / totalChars).coerceIn(0.0, 1.0)
                } else {
                    null
                }
                ScreenObservation(
                    docId = docId,
                    paragraphId = paragraph?.paragraphId,
                    text = if (duplicate) null else it.text.take(MAX_SCREEN_TEXT),
                    textHash = it.textHash,
                    isDuplicate = duplicate,
                    progress = progress,
                    source = it.source
                )
            }

            val speechPick = pickSpeech(speech, speechCursor, t, bucketEnd)
            speechCursor = speechPick.cursor
            val speechSegment = speechPick.transcript?.let {
                SpeechSegment(
                    segmentId = it.segmentId,
                    text = it.text,
                    isFinal = it.isFinal,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    asrConfidence = it.asrConfidence,
                    lang = "zh"
                )
            }

            val rawAction = actionsByBucket[i]
            val actionType = when (rawAction?.type) {
                RawEventType.APP_SWITCH -> ActionType.SWITCH_APP
                RawEventType.SEARCH -> ActionType.SEARCH
                RawEventType.ERASE -> ActionType.ERASE
                RawEventType.CLICK -> ActionType.CLICK
                RawEventType.SCROLL -> ActionType.SCROLL
                else -> ActionType.NONE
            }
            val action = if (actionType == ActionType.NONE) {
                Action(ActionType.NONE)
            } else {
                Action(
                    type = actionType,
                    appPackage = rawAction?.appPackage,
                    targetParagraphId = paragraph?.paragraphId
                )
            }

            val paragraphId = paragraph?.paragraphId
            if (paragraphId != null && paragraphId == previousParagraphId) {
                dwellMs += bucketMs
            } else {
                dwellMs = 0L
            }
            if (paragraphId != null && paragraphId != previousParagraphId) {
                entryCounts[paragraphId] = (entryCounts[paragraphId] ?: 0) + 1
            }
            val revisitCount = if (paragraphId != null) {
                ((entryCounts[paragraphId] ?: 1) - 1).coerceAtLeast(0)
            } else {
                0
            }
            previousParagraphId = paragraphId

            frames += TimelineFrame(
                frameId = frameId,
                t = t,
                capturedAt = baseTime.plusNanos(t * NANOS_PER_MS).toString(),
                screen = screen,
                speech = speechSegment,
                action = action,
                features = FrameFeatures(
                    dwellMs = dwellMs,
                    revisitCount = revisitCount,
                    isStalled = false,
                    attentionScore = null
                )
            )
        }

        require(frames.isNotEmpty()) { "时间轴为空" }
        require(frames.all { it.t >= 0 }) { "帧时间为负" }
        require(frames.zipWithNext().all { (a, b) -> b.t > a.t }) { "帧时间未严格递增" }
        val paragraphIds = paragraphs.map { it.paragraphId }.toSet()
        require(
            frames.all { frame ->
                val pid = frame.screen?.paragraphId
                pid == null || pid in paragraphIds
            }
        ) { "帧引用了不存在的段落" }

        val width = meta.screenWidth
        val height = meta.screenHeight
        val screenSize = if (width != null && height != null && width > 0 && height > 0) {
            ScreenSize(width = width, height = height)
        } else {
            null
        }

        val noteParts = mutableListOf<String>()
        noteParts += "M3 自动对齐：${bucketMs}ms × ${frameCount} 帧；段落 ${paragraphs.size}；" +
            "语音 ${speech.size} 段；OCR ${perception?.ocr?.size ?: 0} 帧"
        if (perception == null) {
            noteParts += "未找到 perception.json，语音通道为空（先运行 M2）"
        }
        if (selected.isEmpty()) {
            noteParts += "未获取到屏幕文本"
        }
        noteParts += "未执行卡壳判定；时间轴仅保存在本机"

        return TimelineSession(
            schemaVersion = "1.0.0",
            sessionId = sessionId,
            app = AppInfo(name = "MindGap", version = meta.appVersion),
            device = DeviceInfo(
                model = meta.deviceModel,
                androidApi = meta.androidApi,
                locale = meta.locale,
                screen = screenSize,
                onDeviceModel = OnDeviceModel(asr = meta.asrModel, ocr = meta.ocrModel)
            ),
            capture = CaptureMeta(
                startedAt = baseTime.toString(),
                endedAt = baseTime.plusNanos(maxT * NANOS_PER_MS).toString(),
                bucketMs = bucketMs.toInt(),
                screenSampleMs = SCREEN_SAMPLE_MS,
                audioSampleRate = if (hasAudio()) AUDIO_SAMPLE_RATE else null,
                sources = sources(),
                // 本地优先：默认不授权上传，用户在采集页显式打开后才允许云端生成
                consent = Consent.REVOKED
            ),
            document = document,
            frames = frames,
            stuckEvents = emptyList(),
            quizItems = emptyList(),
            notes = noteParts.joinToString("；")
        )
    }

    private fun buildObservations(): List<TextObservation> {
        val result = mutableListOf<TextObservation>()

        events.forEach { event ->
            if (event.type != RawEventType.SCREEN_TEXT) return@forEach
            val text = FrameDedup.normalize(event.text.orEmpty())
            val compact = FrameDedup.compact(text)
            if (compact.isEmpty()) return@forEach
            result += TextObservation(
                t = event.t,
                text = text,
                compact = compact,
                textHash = FrameDedup.hash(compact),
                source = ScreenSource.ACCESSIBILITY,
                appPackage = event.appPackage
            )
        }

        perception?.ocr?.forEach { observation ->
            val text = FrameDedup.normalize(observation.text)
            val compact = FrameDedup.compact(text)
            if (compact.isEmpty()) return@forEach
            result += TextObservation(
                t = observation.t,
                text = text,
                compact = compact,
                textHash = FrameDedup.hash(compact),
                source = ScreenSource.OCR,
                appPackage = null
            )
        }

        return result.sortedBy { it.t }
    }

    /** 无障碍文本优先；应用切换后，切换前的文本不再代表当前屏幕。 */
    private fun selectObservations(
        observations: List<TextObservation>,
        switches: List<Pair<Long, String?>>,
        frameCount: Int
    ): Array<TextObservation?> {
        val accessibility = observations.filter { it.source == ScreenSource.ACCESSIBILITY }
        val ocr = observations.filter { it.source == ScreenSource.OCR }
        val result = arrayOfNulls<TextObservation>(frameCount)

        var accessibilityCursor = 0
        var ocrCursor = 0
        var switchCursor = 0
        var latestAccessibility: TextObservation? = null
        var latestOcr: TextObservation? = null
        var lastSwitchAt = -1L
        var lastSwitchPackage: String? = null

        for (i in 0 until frameCount) {
            // 桶是左闭右开的 [t, t+bucketMs)：桶内发生的文本变化应归入本桶
            val bucketEnd = i * bucketMs + bucketMs
            while (switchCursor < switches.size && switches[switchCursor].first < bucketEnd) {
                val (switchAt, packageName) = switches[switchCursor]
                // 同一应用内的窗口变化（弹窗/换页）不使当前文本失效
                if (packageName != lastSwitchPackage) {
                    lastSwitchAt = switchAt
                }
                lastSwitchPackage = packageName
                switchCursor++
            }
            while (accessibilityCursor < accessibility.size &&
                accessibility[accessibilityCursor].t < bucketEnd
            ) {
                latestAccessibility = accessibility[accessibilityCursor]
                accessibilityCursor++
            }
            while (ocrCursor < ocr.size && ocr[ocrCursor].t < bucketEnd) {
                latestOcr = ocr[ocrCursor]
                ocrCursor++
            }
            val accessible = latestAccessibility?.takeIf { it.t >= lastSwitchAt }
            val fromOcr = latestOcr?.takeIf { it.t >= lastSwitchAt }
            result[i] = accessible ?: fromOcr
        }
        return result
    }

    private fun buildActionBuckets(frameCount: Int): Map<Int, RawEvent> {
        val priority = mapOf(
            RawEventType.APP_SWITCH to 4,
            RawEventType.SEARCH to 3,
            RawEventType.ERASE to 2,
            RawEventType.CLICK to 2,
            RawEventType.SCROLL to 1
        )
        val chosen = HashMap<Int, RawEvent>()
        events.forEach { event ->
            val rank = priority[event.type] ?: return@forEach
            val bucket = (event.t / bucketMs).toInt()
            if (bucket !in 0 until frameCount) return@forEach
            val current = chosen[bucket]
            if (current == null || rank >= (priority[current.type] ?: 0)) {
                chosen[bucket] = event
            }
        }
        return chosen
    }

    private data class SpeechPick(val transcript: SpeechTranscript?, val cursor: Int)

    private fun pickSpeech(
        speech: List<SpeechTranscript>,
        cursor: Int,
        from: Long,
        to: Long
    ): SpeechPick {
        var index = cursor
        while (index < speech.size && (speech[index].endMs ?: speech[index].startMs) <= from) {
            index++
        }
        var best: SpeechTranscript? = null
        var bestOverlap = 0L
        var scan = index
        while (scan < speech.size && speech[scan].startMs < to) {
            val segment = speech[scan]
            val end = segment.endMs ?: segment.startMs
            val overlap = minOf(end, to) - maxOf(segment.startMs, from)
            if (overlap > bestOverlap) {
                best = segment
                bestOverlap = overlap
            }
            scan++
        }
        return SpeechPick(best, index)
    }

    private data class Interval(val from: Long, val to: Long)

    private fun buildPauseIntervals(endMs: Long): List<Interval> {
        val intervals = mutableListOf<Interval>()
        var pausedAt = -1L
        events.filter { it.type == RawEventType.MARKER }
            .sortedBy { it.t }
            .forEach { marker ->
                when (marker.note) {
                    "pause" -> if (pausedAt < 0) pausedAt = marker.t
                    "resume" -> if (pausedAt >= 0) {
                        intervals += Interval(pausedAt, marker.t)
                        pausedAt = -1L
                    }
                }
            }
        if (pausedAt >= 0 && endMs > pausedAt) {
            intervals += Interval(pausedAt, endMs)
        }
        return intervals
    }

    private fun sessionEndMs(): Long {
        var end = 0L
        events.forEach { event ->
            end = maxOf(end, event.t, event.startMs ?: 0L, event.endMs ?: 0L)
        }
        perception?.let { result ->
            result.speech.forEach { end = maxOf(end, it.startMs, it.endMs) }
            result.ocr.forEach { end = maxOf(end, it.t) }
        }
        return end
    }

    private fun documentTitle(selected: List<TextObservation>): String =
        selected.firstOrNull()
            ?.text
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(TITLE_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "未命名文档（仅语音）"

    private fun dominantPackage(selected: List<TextObservation>): String? =
        selected.mapNotNull { it.appPackage }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: events.mapNotNull { it.appPackage }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

    private fun hasAudio(): Boolean =
        events.any { it.type == RawEventType.AUDIO_START || it.type == RawEventType.AUDIO_SEGMENT } ||
            !perception?.speech.isNullOrEmpty()

    private fun sources(): List<SourceKind> {
        val result = mutableListOf<SourceKind>()
        if (events.any { it.type == RawEventType.SCREEN_FRAME } || !perception?.ocr.isNullOrEmpty()) {
            result += SourceKind.SCREEN
        }
        if (events.any { it.type == RawEventType.SCREEN_TEXT }) {
            result += SourceKind.ACCESSIBILITY
        }
        if (hasAudio()) {
            result += SourceKind.AUDIO
        }
        if (result.isEmpty()) {
            result += SourceKind.SCREEN
        }
        return result
    }

    private companion object {
        const val SCREEN_SAMPLE_MS = 1000
        const val AUDIO_SAMPLE_RATE = 16000
        const val MAX_FRAMES = 120_000
        const val MAX_SCREEN_TEXT = 800
        const val TITLE_CHARS = 40
        const val NANOS_PER_MS = 1_000_000L
    }
}
