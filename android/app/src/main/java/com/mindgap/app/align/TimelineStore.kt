package com.mindgap.app.align

import android.content.Context
import com.mindgap.app.capture.EventStore
import com.mindgap.app.data.model.TimelineSession
import kotlinx.serialization.json.Json
import java.io.File

/** M3 产物读写：与会话事件同目录，一个会话一份时间轴。 */
object TimelineStore {
    private const val SUFFIX = ".timeline.json"

    private val json = Json {
        // CaptureMeta.bucketMs 等字段在模型里有默认值，但 Schema 要求必填，必须写默认值
        encodeDefaults = true
        // Schema 里可选字段只允许缺失、不允许 null，因此不写显式 null
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun file(context: Context, sessionId: String): File =
        File(EventStore.dir(context), "$sessionId$SUFFIX")

    fun save(context: Context, session: TimelineSession) {
        file(context, session.sessionId).writeText(
            json.encodeToString(TimelineSession.serializer(), session)
        )
    }

    fun load(context: Context, sessionId: String): TimelineSession? {
        val target = file(context, sessionId)
        if (!target.exists()) return null
        return runCatching {
            json.decodeFromString(TimelineSession.serializer(), target.readText())
        }.getOrNull()
    }

    /** 最近一次生成的时间轴，用于应用重启后恢复回放页。 */
    fun latest(context: Context): TimelineSession? {
        val newest = EventStore.dir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching {
            json.decodeFromString(TimelineSession.serializer(), newest.readText())
        }.getOrNull()
    }

    /** 全部历史时间轴，按会话开始时间倒序。 */
    fun list(context: Context): List<TimelineSession> =
        EventStore.dir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString(TimelineSession.serializer(), file.readText())
                }.getOrNull()
            }
            ?.sortedWith(compareByDescending { it.capture.startedAt })
            ?: emptyList()
}
