package com.mindgap.app.judge

import com.mindgap.app.data.model.ActionType
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.data.model.StuckSignals
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import java.util.Locale

data class JudgementReport(
    val baselineP90Ms: Long,
    val baselineP50Ms: Long,
    val warmupEndMs: Long,
    val timeThresholdMs: Long,
    val skimThresholdMs: Long,
    val calibrationSamples: Int,
    val timeCount: Int,
    val behaviorCount: Int,
    val semanticCount: Int
) {
    fun noteText(): String =
        "M4 卡壳判定：${timeCount + behaviorCount + semanticCount} 事件" +
            "（时间 $timeCount / 行为 $behaviorCount / 语义 $semanticCount）；" +
            "基线 P90 ${formatSeconds(baselineP90Ms)} / P50 ${formatSeconds(baselineP50Ms)}；" +
            "校准 0–${formatSeconds(warmupEndMs)}；" +
            "行为通道覆盖回看/切窗/搜索/删除"
}

data class DetectionOutcome(
    val events: List<StuckEvent>,
    val report: JudgementReport
)

internal fun formatSeconds(ms: Long): String = "${ms / 1000}s"

/**
 * M4 规则判定：只读 M3 的 frames 与 document，不再回看原始音频/截图。
 *
 * 时间异常：段落连续停留超过基线 P90（且不低于 20s）。
 * 行为异常：短时间内回看同一段落；若中间切过应用，则标记 switchedApp。
 * 语义异常：低频技术术语在当前/后文未解释、语音里也未提及，用户很快跳过该段。
 *
 * 基线：前 5 分钟静默校准；不足 5 分钟的会话用前 1/3 时长（最多 60s）校准。
 * 同一段落最多输出一个事件，取置信度最高的规则，保留可解释信号。
 */
class StuckDetector(private val session: TimelineSession) {
    private val bucketMs = session.capture.bucketMs.toLong().coerceAtLeast(100L)
    private val frames = session.frames
    private val paragraphById = session.document.paragraphs.associateBy { it.paragraphId }

    fun detect(): DetectionOutcome {
        if (frames.size < 2) {
            return DetectionOutcome(emptyList(), emptyReport())
        }

        val visits = buildVisits()
        val sessionEnd = frames.last().t + bucketMs
        val warmupEnd = if (sessionEnd <= FULL_CALIBRATION_MS) {
            (sessionEnd / 3).coerceAtMost(SHORT_WARMUP_MS)
        } else {
            FULL_CALIBRATION_MS
        }

        val samples = visits
            .filter { it.endMs <= warmupEnd && it.maxDwellMs >= MIN_SAMPLE_DWELL_MS }
            .map { it.maxDwellMs }
        val p90 = if (samples.size >= MIN_SAMPLES) {
            percentile(samples, 0.9).coerceIn(MIN_BASELINE_MS, MAX_BASELINE_MS)
        } else {
            DEFAULT_P90_MS
        }
        val p50 = if (samples.size >= MIN_SAMPLES) {
            percentile(samples, 0.5).coerceIn(MIN_BASELINE_MS, MAX_BASELINE_MS)
        } else {
            DEFAULT_P50_MS
        }
        val timeThreshold = maxOf(p90 * 6 / 5, MIN_TIME_THRESHOLD_MS)
        val skimThreshold = minOf(maxOf(p50, MIN_SKIM_MS), MAX_SKIM_MS)

        val frequencies = TermExtractor.frequencies(
            visits.mapNotNull { it.text }.distinct() +
                session.document.paragraphs.mapNotNull { it.preview }.distinct()
        )

        val candidates = mutableListOf<StuckEvent>()
        candidates += detectTimeAnomalies(visits, warmupEnd, p90, timeThreshold)
        candidates += detectRevisits(visits, warmupEnd)
        candidates += detectSemanticSkips(visits, warmupEnd, frequencies, skimThreshold)

        val events = mergeCandidates(candidates).mapIndexed { index, event ->
            event.copy(eventId = String.format(Locale.ROOT, "e%03d", index + 1))
        }
        val report = JudgementReport(
            baselineP90Ms = p90,
            baselineP50Ms = p50,
            warmupEndMs = warmupEnd,
            timeThresholdMs = timeThreshold,
            skimThresholdMs = skimThreshold,
            calibrationSamples = samples.size,
            timeCount = events.count { it.kind == StuckKind.TIME_ANOMALY },
            behaviorCount = events.count { it.kind == StuckKind.BEHAVIOR_ANOMALY },
            semanticCount = events.count { it.kind == StuckKind.SEMANTIC_ANOMALY }
        )
        return DetectionOutcome(events, report)
    }

    private data class Visit(
        val paragraphId: String,
        val frames: List<TimelineFrame>,
        val text: String?,
        val startMs: Long,
        val endMs: Long,
        val maxDwellMs: Long,
        val maxRevisitCount: Int
    ) {
        val firstFrame: TimelineFrame get() = frames.first()
        val lastFrame: TimelineFrame get() = frames.last()
    }

    private fun buildVisits(): List<Visit> {
        val visits = mutableListOf<Visit>()
        var index = 0
        while (index < frames.size) {
            val paragraphId = frames[index].screen?.paragraphId
            if (paragraphId == null) {
                index++
                continue
            }
            var end = index
            while (end + 1 < frames.size && frames[end + 1].screen?.paragraphId == paragraphId) {
                end++
            }
            val group = frames.subList(index, end + 1).toList()
            val text = group.asSequence()
                .mapNotNull { frame -> frame.screen?.text?.takeIf { it.isNotBlank() } }
                .maxByOrNull { it.length }
                ?: paragraphById[paragraphId]?.preview
            visits += Visit(
                paragraphId = paragraphId,
                frames = group,
                text = text,
                startMs = group.first().t,
                endMs = group.last().t + bucketMs,
                maxDwellMs = group.maxOf { it.features?.dwellMs ?: 0L },
                maxRevisitCount = group.maxOf { it.features?.revisitCount ?: 0 }
            )
            index = end + 1
        }
        return visits
    }

    private fun detectTimeAnomalies(
        visits: List<Visit>,
        warmupEnd: Long,
        baselineP90: Long,
        threshold: Long
    ): List<StuckEvent> = visits.mapNotNull { visit ->
        // 停留跨过校准窗口才判定；完全落在静默期内的停留只用于建基线
        if (visit.endMs < warmupEnd) return@mapNotNull null
        if (visit.maxDwellMs < threshold) return@mapNotNull null

        val overRatio = (visit.maxDwellMs - threshold).toDouble() / threshold
        val confidence = (0.55 + 0.4 * overRatio.coerceIn(0.0, 1.0)).coerceIn(0.5, 0.95)
        StuckEvent(
            eventId = "",
            kind = StuckKind.TIME_ANOMALY,
            paragraphId = visit.paragraphId,
            fromMs = visit.startMs,
            toMs = visit.endMs,
            fromFrameId = visit.firstFrame.frameId,
            toFrameId = visit.lastFrame.frameId,
            evidenceFrameIds = evidenceFor(visit),
            confidence = confidence,
            signals = StuckSignals(
                dwellMs = visit.maxDwellMs,
                baselineDwellMs = baselineP90,
                revisitCount = visit.maxRevisitCount
            ),
            summary = "在 ${visit.paragraphId} 停留 ${formatSeconds(visit.maxDwellMs)}，" +
                "超过基线 P90（${formatSeconds(baselineP90)}）"
        )
    }

    private fun detectRevisits(visits: List<Visit>, warmupEnd: Long): List<StuckEvent> {
        val lastVisit = HashMap<String, Visit>()
        val events = mutableListOf<StuckEvent>()

        visits.forEach { visit ->
            val previous = lastVisit[visit.paragraphId]
            if (previous != null) {
                val gap = visit.startMs - previous.endMs
                if (gap in 0L..REVISIT_GAP_MS &&
                    previous.maxDwellMs >= MIN_READ_MS &&
                    visit.startMs >= warmupEnd
                ) {
                    // 只有短暂空档或期间有真实操作，才视为回看；长时间无操作的空档更像暂停
                    val gapHasAction = frames.any { frame ->
                        frame.t >= previous.endMs &&
                            frame.t < visit.startMs &&
                            frame.action.type != ActionType.NONE
                    }
                    val gapBuckets = ((visit.startMs - previous.endMs) / bucketMs).toInt()
                    if (gapHasAction || gapBuckets < PAUSE_GAP_BUCKETS) {
                        val switchFrame = frames.firstOrNull { frame ->
                            frame.t >= previous.endMs &&
                                frame.t < visit.startMs &&
                                frame.action.type == ActionType.SWITCH_APP
                        }
                        val switched = switchFrame != null
                        val confidence = (
                            0.55 +
                                (if (gap <= QUICK_RETURN_MS) 0.15 else 0.05) +
                                (if (switched) 0.1 else 0.0) +
                                (if (visit.maxRevisitCount >= 2) 0.05 else 0.0)
                            ).coerceAtMost(0.9)

                        val packageLabel = switchFrame?.action?.appPackage ?: "其他应用"
                        val summary = if (switched) {
                            "切到 $packageLabel ${formatSeconds(gap)}后回到 ${visit.paragraphId}，疑似回看查证"
                        } else {
                            "离开 ${formatSeconds(gap)} 后回看 ${visit.paragraphId}" +
                                "（第 ${visit.maxRevisitCount + 1} 次进入）"
                        }

                        events += StuckEvent(
                            eventId = "",
                            kind = StuckKind.BEHAVIOR_ANOMALY,
                            paragraphId = visit.paragraphId,
                            fromMs = previous.lastFrame.t,
                            toMs = visit.endMs,
                            fromFrameId = previous.lastFrame.frameId,
                            toFrameId = visit.lastFrame.frameId,
                            evidenceFrameIds = (listOf(previous.lastFrame.frameId) + evidenceFor(visit))
                                .distinct()
                                .take(MAX_EVIDENCE + 1),
                            confidence = confidence,
                            signals = StuckSignals(
                                revisitCount = visit.maxRevisitCount,
                                switchedApp = switched
                            ),
                            summary = summary.take(MAX_SUMMARY)
                        )
                    }
                }
            }
            lastVisit[visit.paragraphId] = visit
        }
        return events
    }

    private fun detectSemanticSkips(
        visits: List<Visit>,
        warmupEnd: Long,
        frequencies: Map<String, Int>,
        skimThreshold: Long
    ): List<StuckEvent> {
        val events = mutableListOf<StuckEvent>()

        visits.forEachIndexed { index, visit ->
            if (visit.endMs < warmupEnd) return@forEachIndexed
            if (visit.maxDwellMs > skimThreshold) return@forEachIndexed

            // 必须确实进入了下一段；会话末尾或暂停后回到同一段都不算「跳过」
            val nextVisit = visits.getOrNull(index + 1)
            if (nextVisit == null || nextVisit.paragraphId == visit.paragraphId) {
                return@forEachIndexed
            }

            val text = visit.text?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val paragraph = paragraphById[visit.paragraphId]
            val nextPreview = paragraph?.let { current ->
                session.document.paragraphs
                    .firstOrNull { it.index == current.index + 1 }
                    ?.preview
            }.orEmpty()
            val nextVisitText = nextVisit.text?.take(NEXT_CONTEXT_CHARS).orEmpty()
            val context = listOf(text, nextPreview, nextVisitText)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            val spokenText = frames
                .mapNotNull { it.speech }
                .filter { speech ->
                    val speechEnd = speech.endMs ?: (speech.startMs + bucketMs)
                    speech.startMs < visit.endMs + SPEECH_MARGIN_MS &&
                        speechEnd > visit.startMs - SPEECH_MARGIN_MS
                }
                .joinToString(" ") { it.text }

            val term = TermExtractor.candidates(text, frequencies)
                .firstOrNull { candidate ->
                    !TermExtractor.isExplained(candidate, context) &&
                        !spokenText.contains(candidate, ignoreCase = true)
                }
                ?: return@forEachIndexed

            val evidence = LinkedHashSet<String>()
            visit.frames.firstOrNull { frame ->
                frame.screen?.text?.contains(term, ignoreCase = true) == true
            }?.let { evidence += it.frameId }
            evidence += evidenceFor(visit)

            val confidence = (0.6 + (if (term.any { it.isUpperCase() }) 0.05 else 0.0))
                .coerceAtMost(0.85)
            events += StuckEvent(
                eventId = "",
                kind = StuckKind.SEMANTIC_ANOMALY,
                paragraphId = visit.paragraphId,
                term = term.take(MAX_TERM_CHARS),
                fromMs = visit.startMs,
                toMs = visit.endMs,
                fromFrameId = visit.firstFrame.frameId,
                toFrameId = visit.lastFrame.frameId,
                evidenceFrameIds = evidence.take(MAX_EVIDENCE),
                confidence = confidence,
                signals = StuckSignals(
                    dwellMs = visit.maxDwellMs,
                    revisitCount = visit.maxRevisitCount,
                    skippedTerm = term.take(MAX_TERM_CHARS),
                    skippedSentence = TermExtractor.sentenceAround(text, term)
                ),
                summary = "术语「${term.take(MAX_TERM_CHARS)}」未在上下文解释，" +
                    "${formatSeconds(visit.maxDwellMs)} 后跳过 ${visit.paragraphId}"
            )
        }
        return events
    }

    /** 证据帧必须落在事件区间内，且都来自 frames。 */
    private fun evidenceFor(visit: Visit): List<String> {
        val ids = LinkedHashSet<String>()
        ids += visit.firstFrame.frameId
        ids += visit.lastFrame.frameId
        visit.frames.maxByOrNull { it.features?.dwellMs ?: 0L }?.let { ids += it.frameId }
        if (visit.frames.size > 4) {
            ids += visit.frames[visit.frames.size / 2].frameId
        }
        return ids.take(MAX_EVIDENCE)
    }

    private fun mergeCandidates(candidates: List<StuckEvent>): List<StuckEvent> {
        if (candidates.isEmpty()) return emptyList()

        val sorted = candidates.sortedWith(compareBy({ it.fromMs }, { -it.confidence }))
        val merged = mutableListOf<StuckEvent>()
        for (candidate in sorted) {
            val index = merged.indexOfFirst { existing ->
                existing.paragraphId != null &&
                    existing.paragraphId == candidate.paragraphId &&
                    candidate.fromMs <= existing.toMs + MERGE_GAP_MS &&
                    candidate.toMs >= existing.fromMs - MERGE_GAP_MS
            }
            if (index < 0) {
                merged += candidate
            } else {
                merged[index] = mergeTwo(merged[index], candidate)
            }
        }

        // 同一段落最多一个事件：取置信度最高者，避免 M5 对同一段重复出题
        val bestByParagraph = LinkedHashMap<String, StuckEvent>()
        merged.forEach { event ->
            val paragraphId = event.paragraphId ?: return@forEach
            val existing = bestByParagraph[paragraphId]
            if (existing == null || event.confidence > existing.confidence) {
                bestByParagraph[paragraphId] = event
            }
        }

        return bestByParagraph.values
            .sortedWith(compareByDescending<StuckEvent> { it.confidence }.thenBy { it.fromMs })
            .take(MAX_EVENTS)
            .sortedBy { it.fromMs }
    }

    private fun mergeTwo(a: StuckEvent, b: StuckEvent): StuckEvent {
        val primary = when {
            b.confidence > a.confidence + 0.05 -> b
            a.confidence > b.confidence + 0.05 -> a
            b.kind == StuckKind.BEHAVIOR_ANOMALY -> b
            else -> a
        }
        val secondary = if (primary === a) b else a
        val earlier = if (a.fromMs <= b.fromMs) a else b
        val later = if (a.toMs >= b.toMs) a else b

        return primary.copy(
            fromMs = earlier.fromMs,
            toMs = later.toMs,
            fromFrameId = earlier.fromFrameId,
            toFrameId = later.toFrameId,
            evidenceFrameIds = (a.evidenceFrameIds + b.evidenceFrameIds)
                .distinct()
                .take(MAX_EVIDENCE + 2),
            signals = mergeSignals(primary.signals, secondary.signals),
            term = primary.term ?: secondary.term,
            summary = primary.summary ?: secondary.summary
        )
    }

    private fun mergeSignals(primary: StuckSignals?, secondary: StuckSignals?): StuckSignals? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return StuckSignals(
            dwellMs = primary.dwellMs ?: secondary.dwellMs,
            baselineDwellMs = primary.baselineDwellMs ?: secondary.baselineDwellMs,
            revisitCount = primary.revisitCount ?: secondary.revisitCount,
            switchedApp = primary.switchedApp ?: secondary.switchedApp,
            searchQuery = primary.searchQuery ?: secondary.searchQuery,
            skippedTerm = primary.skippedTerm ?: secondary.skippedTerm,
            skippedSentence = primary.skippedSentence ?: secondary.skippedSentence
        )
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        val sorted = values.sorted()
        val rank = kotlin.math.ceil((sorted.size - 1) * p).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    private fun emptyReport() = JudgementReport(
        baselineP90Ms = DEFAULT_P90_MS,
        baselineP50Ms = DEFAULT_P50_MS,
        warmupEndMs = 0,
        timeThresholdMs = MIN_TIME_THRESHOLD_MS,
        skimThresholdMs = MIN_SKIM_MS,
        calibrationSamples = 0,
        timeCount = 0,
        behaviorCount = 0,
        semanticCount = 0
    )

    private companion object {
        const val FULL_CALIBRATION_MS = 300_000L
        const val SHORT_WARMUP_MS = 60_000L
        const val MIN_SAMPLES = 3
        const val MIN_SAMPLE_DWELL_MS = 1_000L
        const val DEFAULT_P90_MS = 15_000L
        const val DEFAULT_P50_MS = 6_000L
        const val MIN_BASELINE_MS = 8_000L
        const val MAX_BASELINE_MS = 180_000L
        const val MIN_TIME_THRESHOLD_MS = 20_000L
        const val MIN_SKIM_MS = 6_000L
        const val MAX_SKIM_MS = 20_000L
        const val REVISIT_GAP_MS = 90_000L
        const val QUICK_RETURN_MS = 30_000L
        const val MIN_READ_MS = 3_000L
        const val PAUSE_GAP_BUCKETS = 4
        const val SPEECH_MARGIN_MS = 3_000L
        const val NEXT_CONTEXT_CHARS = 400
        const val MERGE_GAP_MS = 10_000L
        const val MAX_EVENTS = 50
        const val MAX_EVIDENCE = 4
        const val MAX_TERM_CHARS = 24
        const val MAX_SUMMARY = 200
    }
}
