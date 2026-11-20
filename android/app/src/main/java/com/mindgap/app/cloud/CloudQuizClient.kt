package com.mindgap.app.cloud

import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.QuizItem
import com.mindgap.app.data.model.QuizMode
import com.mindgap.app.data.model.StuckEvent
import com.mindgap.app.data.model.TimelineFrame
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.quiz.QuizValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

@Serializable
internal data class CloudEvidence(
    val frameId: String,
    val paragraphId: String? = null,
    val text: String
)

@Serializable
internal data class CloudSnippet(
    val paragraphId: String,
    val text: String,
    val evidence: List<CloudEvidence>
)

@Serializable
internal data class CloudEventState(
    val eventId: String,
    val kind: String,
    val paragraphId: String? = null,
    val term: String? = null,
    val dwellMs: Long? = null,
    val revisitCount: Int? = null,
    val switchedApp: Boolean? = null,
    val fromMs: Long,
    val toMs: Long
)

@Serializable
internal data class CloudGenerateRequest(
    val schemaVersion: String = "1.0.0",
    val sessionId: String,
    val event: CloudEventState,
    val focus: CloudSnippet,
    val context: List<CloudSnippet>,
    val speech: String = "",
    val optionCount: Int = 3,
    val questionType: String = "counterfactual_quote",
    val mutationCount: Int = 2,
    val locale: String = "zh-CN"
)

/**
 * 云端生成客户端：只上传压缩后的文本片段与证据帧 ID，不上传音频、截图或原始事件。
 * 未授权、网络失败、返回结构不合法或证据校验失败时返回 null，由调用方回退离线模板。
 */
object CloudQuizClient {
    private const val MAX_TEXT = 800
    private const val MAX_SPEECH = 120

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    suspend fun generate(session: TimelineSession, event: StuckEvent): QuizItem? =
        withContext(Dispatchers.IO) {
            if (session.capture.consent != Consent.GRANTED) return@withContext null
            val materials = buildMaterials(session)
            val focus = materials[event.paragraphId] ?: return@withContext null
            val context = materials.values
                .filter { it.paragraphId != focus.paragraphId }
                .sortedBy { abs(it.order - focus.order) }
                .take(2)
            if (context.isEmpty()) return@withContext null

            val request = CloudGenerateRequest(
                sessionId = session.sessionId,
                event = CloudEventState(
                    eventId = event.eventId,
                    kind = event.kind.name.lowercase(),
                    paragraphId = event.paragraphId,
                    term = event.term,
                    dwellMs = event.signals?.dwellMs,
                    revisitCount = event.signals?.revisitCount,
                    switchedApp = event.signals?.switchedApp,
                    fromMs = event.fromMs,
                    toMs = event.toMs
                ),
                focus = CloudSnippet(
                    paragraphId = focus.paragraphId,
                    text = focus.text.take(MAX_TEXT),
                    evidence = focus.evidence
                ),
                context = context.map { material ->
                    CloudSnippet(
                        paragraphId = material.paragraphId,
                        text = material.text.take(MAX_TEXT),
                        evidence = material.evidence
                    )
                },
                speech = speechDuring(session, event)
            )

            val body = json.encodeToString(CloudGenerateRequest.serializer(), request)
            val connection = runCatching {
                (URL(CloudConfig.BASE_URL + CloudConfig.GENERATE_PATH)
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "MindGap/0.6")
                }
            }.getOrNull() ?: return@withContext null

            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                if (connection.responseCode !in 200..299) return@withContext null
                val text = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val quiz = runCatching {
                    json.decodeFromString(QuizItem.serializer(), text)
                }.getOrNull() ?: return@withContext null

                val normalized = quiz.copy(mode = QuizMode.ONLINE)
                if (normalized.eventId != event.eventId) return@withContext null
                if (QuizValidator.problems(normalized, session).isNotEmpty()) {
                    return@withContext null
                }
                normalized
            } catch (error: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    private data class Material(
        val paragraphId: String,
        val text: String,
        val evidence: List<CloudEvidence>,
        val order: Int
    )

    private fun buildMaterials(session: TimelineSession): Map<String, Material> {
        val framesByParagraph = LinkedHashMap<String, MutableList<TimelineFrame>>()
        session.frames.forEach { frame ->
            val paragraphId = frame.screen?.paragraphId ?: return@forEach
            framesByParagraph.getOrPut(paragraphId) { mutableListOf() }.add(frame)
        }
        val indexByParagraph = session.document.paragraphs.associate { it.paragraphId to it.index }
        return framesByParagraph.mapValues { (paragraphId, frames) ->
            val text = frames.asSequence()
                .mapNotNull { frame -> frame.screen?.text?.takeIf { it.isNotBlank() } }
                .maxByOrNull { it.length }
                .orEmpty()
            val evidence = frames
                .filter { it.screen?.text?.isNotBlank() == true }
                .sortedByDescending { it.screen?.text?.length ?: 0 }
                .take(2)
                .map { frame ->
                    CloudEvidence(
                        frameId = frame.frameId,
                        paragraphId = paragraphId,
                        text = frame.screen?.text.orEmpty().take(MAX_TEXT)
                    )
                }
            Material(
                paragraphId = paragraphId,
                text = text,
                evidence = evidence,
                order = indexByParagraph[paragraphId] ?: Int.MAX_VALUE
            )
        }.filterValues { it.text.isNotBlank() && it.evidence.isNotEmpty() }
    }

    private fun speechDuring(session: TimelineSession, event: StuckEvent): String =
        session.frames.asSequence()
            .filter { it.t in event.fromMs..event.toMs }
            .mapNotNull { it.speech?.text }
            .distinct()
            .joinToString("；")
            .take(MAX_SPEECH)
}
