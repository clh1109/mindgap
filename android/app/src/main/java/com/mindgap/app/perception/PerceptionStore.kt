package com.mindgap.app.perception

import android.content.Context
import com.mindgap.app.capture.EventStore
import kotlinx.serialization.json.Json
import java.io.File

/** 感知结果读写：与 EventStore 同目录，一个会话一个 JSON。 */
object PerceptionStore {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun file(context: Context, sessionId: String): File =
        File(EventStore.dir(context), "$sessionId.perception.json")

    fun save(context: Context, result: PerceptionResult) {
        file(context, result.sessionId)
            .writeText(json.encodeToString(PerceptionResult.serializer(), result))
    }

    fun load(context: Context, sessionId: String): PerceptionResult? {
        val target = file(context, sessionId)
        if (!target.exists()) return null
        return runCatching {
            json.decodeFromString(PerceptionResult.serializer(), target.readText())
        }.getOrNull()
    }
}
