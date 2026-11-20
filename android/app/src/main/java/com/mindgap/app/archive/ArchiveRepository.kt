package com.mindgap.app.archive

import android.content.Context
import com.mindgap.app.align.TimelineStore
import com.mindgap.app.data.model.StuckKind
import com.mindgap.app.quiz.ConfidenceLevel
import com.mindgap.app.quiz.QuizAttemptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ArchivedSession(
    val sessionId: String,
    val title: String,
    val startedAt: String,
    val durationMs: Long,
    val frameCount: Int,
    val stuckCount: Int,
    val quizCount: Int,
    val answered: Int = 0,
    val hiddenBlind: Int = 0
)

data class ArchiveSummary(
    val sessions: List<ArchivedSession>,
    val totalDurationMs: Long,
    val totalStuck: Int,
    val totalQuizzes: Int,
    val byKind: Map<StuckKind, Int>,
    val totalAnswered: Int = 0,
    val totalHiddenBlind: Int = 0,
    val highConfidenceTotal: Int = 0
)

/** 读取本地全部时间轴，形成跨会话的成长档案；不访问网络。 */
object ArchiveRepository {
    suspend fun load(context: Context): ArchiveSummary = withContext(Dispatchers.IO) {
        val timelines = TimelineStore.list(context)
        val attempts = QuizAttemptStore.loadAll(context)
        val attemptsBySession = attempts.groupBy { it.sessionId }
        val sessions = timelines.map { session ->
            val answers = attemptsBySession[session.sessionId].orEmpty()
            ArchivedSession(
                sessionId = session.sessionId,
                title = session.document.title,
                startedAt = session.capture.startedAt,
                durationMs = session.frames.lastOrNull()?.t ?: 0L,
                frameCount = session.frames.size,
                stuckCount = session.stuckEvents.size,
                quizCount = session.quizItems.size,
                answered = answers.size,
                hiddenBlind = answers.count {
                    it.confidence == ConfidenceLevel.HIGH.wire && !it.correct
                }
            )
        }
        val byKind = StuckKind.entries.associateWith { kind ->
            timelines.sumOf { session -> session.stuckEvents.count { it.kind == kind } }
        }
        ArchiveSummary(
            sessions = sessions,
            totalDurationMs = sessions.sumOf { it.durationMs },
            totalStuck = sessions.sumOf { it.stuckCount },
            totalQuizzes = sessions.sumOf { it.quizCount },
            byKind = byKind,
            totalAnswered = attempts.size,
            totalHiddenBlind = attempts.count {
                it.confidence == ConfidenceLevel.HIGH.wire && !it.correct
            },
            highConfidenceTotal = attempts.count {
                it.confidence == ConfidenceLevel.HIGH.wire
            }
        )
    }
}
