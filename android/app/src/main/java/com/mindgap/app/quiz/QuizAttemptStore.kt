package com.mindgap.app.quiz

import android.content.Context
import com.mindgap.app.capture.EventStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ConfidenceLevel(val wire: String) {
    HIGH("high"),
    LOW("low")
}

/**
 * 一次作答记录。先记信心再记对错，才能把「以为自己懂」与「知道自己没懂」分开。
 */
@Serializable
data class QuizAttempt(
    val sessionId: String,
    val quizId: String,
    val eventId: String,
    val confidence: String,
    val selectedOptionId: String,
    val correct: Boolean,
    val answeredAtWallClock: Long
)

/**
 * 作答记录存储：与采集数据同目录，一行一条 JSONL。
 * 删除会话时由 SessionDataManager 一并清理。
 */
object QuizAttemptStore {
    private const val SUFFIX = ".attempts.jsonl"

    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context, sessionId: String): File =
        File(EventStore.dir(context), "$sessionId$SUFFIX")

    @Synchronized
    fun append(context: Context, attempt: QuizAttempt) {
        // 同一题只保留第一次作答，避免切页或重进后重复计数
        if (load(context, attempt.sessionId).any { it.quizId == attempt.quizId }) return
        file(context, attempt.sessionId)
            .appendText(json.encodeToString(QuizAttempt.serializer(), attempt) + "\n")
    }

    fun load(context: Context, sessionId: String): List<QuizAttempt> =
        read(file(context, sessionId))

    fun loadAll(context: Context): List<QuizAttempt> =
        EventStore.dir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .flatMap { read(it) }

    private fun read(file: File): List<QuizAttempt> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching {
                        json.decodeFromString(QuizAttempt.serializer(), line)
                    }.getOrNull()
                }.toList()
            }
        }.getOrElse { emptyList() }
    }
}
