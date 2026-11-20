package com.mindgap.app.privacy

import android.content.Context
import android.util.Log
import com.mindgap.app.align.AlignmentPipeline
import com.mindgap.app.align.TimelineStore
import com.mindgap.app.capture.CapturePhase
import com.mindgap.app.capture.CaptureSession
import com.mindgap.app.capture.EventStore
import com.mindgap.app.data.model.Consent
import com.mindgap.app.data.model.TimelineSession
import com.mindgap.app.judge.JudgementPipeline
import com.mindgap.app.perception.PerceptionPipeline
import com.mindgap.app.perception.PerceptionStore
import com.mindgap.app.quiz.QuizAttemptStore
import com.mindgap.app.quiz.QuizPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * M6 会话数据生命周期：显式上传授权、占用统计、一键删除。
 * 删除范围：原始事件 JSONL、PCM、关键帧目录、感知结果、时间轴；不影响已下载模型。
 */
object SessionDataManager {
    private const val TAG = "MindGapPrivacy"

    data class Footprint(val files: Int, val bytes: Long)

    suspend fun updateConsent(
        context: Context,
        session: TimelineSession,
        granted: Boolean
    ): TimelineSession = withContext(Dispatchers.IO) {
        val updated = session.copy(
            capture = session.capture.copy(
                consent = if (granted) Consent.GRANTED else Consent.REVOKED
            )
        )
        TimelineStore.save(context, updated)
        Log.i(TAG, "consent=${if (granted) "granted" else "revoked"} " +
            "session=${session.sessionId.take(8)}")
        updated
    }

    suspend fun footprint(context: Context, sessionId: String): Footprint =
        withContext(Dispatchers.IO) {
            measure(sessionFiles(context, sessionId))
        }

    suspend fun deleteSession(context: Context, sessionId: String): Footprint =
        withContext(Dispatchers.IO) {
            val current = CaptureSession.state.value
            if (current.sessionId == sessionId &&
                (current.phase == CapturePhase.RUNNING || current.phase == CapturePhase.PAUSED)
            ) {
                throw IllegalStateException("采集中不能删除，请先结束会话")
            }

            val files = sessionFiles(context, sessionId)
            val report = measure(files)
            files.forEach { file ->
                runCatching {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }.onFailure { error ->
                    Log.w(TAG, "delete failed: ${file.name}", error)
                }
            }
            val remaining = files.count { it.exists() }
            if (remaining > 0) {
                Log.w(TAG, "delete incomplete: $remaining path(s) still exist")
            }

            CaptureSession.resetAfterDelete(sessionId)
            PerceptionPipeline.clear(sessionId)
            AlignmentPipeline.clear(sessionId)
            JudgementPipeline.invalidate(sessionId)
            QuizPipeline.invalidate(sessionId)
            Log.i(TAG, "deleted session=${sessionId.take(8)} " +
                "files=${report.files} bytes=${report.bytes}")
            report
        }

    private fun sessionFiles(context: Context, sessionId: String): List<File> = listOf(
        File(EventStore.dir(context), "$sessionId.jsonl"),
        EventStore.audioFile(context, sessionId),
        EventStore.framesDir(context, sessionId),
        PerceptionStore.file(context, sessionId),
        TimelineStore.file(context, sessionId),
        QuizAttemptStore.file(context, sessionId)
    )

    private fun measure(files: List<File>): Footprint {
        var count = 0
        var bytes = 0L
        files.forEach { file ->
            if (!file.exists()) return@forEach
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach { child ->
                    count++
                    bytes += child.length()
                }
            } else {
                count++
                bytes += file.length()
            }
        }
        return Footprint(count, bytes)
    }
}
