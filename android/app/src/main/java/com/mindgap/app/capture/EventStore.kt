package com.mindgap.app.capture

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * 采集事件存储：一行一个 JSON，append-only。
 * 选 JSONL 而不是 Room，是为了 M1 先跑通链路；后续换 Room 时只需替换这个对象。
 */
object EventStore {
    private const val DIR_NAME = "capture"

    private val json = Json { encodeDefaults = false }

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    @Synchronized
    fun open(context: Context, sessionId: String): File {
        close()
        val target = File(dir(context), "$sessionId.jsonl")
        currentFile = target
        writer = BufferedWriter(FileWriter(target, true))
        return target
    }

    @Synchronized
    fun append(event: RawEvent) {
        val out = writer ?: return
        out.write(json.encodeToString(RawEvent.serializer(), event))
        out.newLine()
        out.flush()
    }

    @Synchronized
    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    fun currentFile(): File? = currentFile

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 关键帧目录：一个会话一个子目录，OCR 结束后可整体删除。 */
    fun framesDir(context: Context, sessionId: String): File =
        File(dir(context), "$sessionId-frames").apply { mkdirs() }

    fun audioFile(context: Context, sessionId: String): File =
        File(dir(context), "$sessionId.pcm")

    /** 读取一次会话的全部原始事件，用于 M1 的回放验收。 */
    fun readAll(context: Context, sessionId: String): List<RawEvent> {
        val file = File(dir(context), "$sessionId.jsonl")
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { json.decodeFromString(RawEvent.serializer(), line) }.getOrNull()
            }.toList()
        }
    }
}
