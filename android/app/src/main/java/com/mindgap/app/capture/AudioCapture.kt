package com.mindgap.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 16kHz 单声道 PCM 采集 + 能量 VAD 断句。
 *
 * 时间基准：语音段边界按「已写入 PCM 的采样数」换算，而不是墙钟。
 * 暂停时写入等长静音而不是跳过，保证 PCM 时长始终等于会话墙钟时长，
 * M2 可以按 `originMs + 采样下标 / 16` 精确还原会话时间，暂停也不会漂移。
 * originProvider 返回音频起点在会话时间轴上的位置，用于与屏幕帧对齐。
 *
 * 这里的能量 VAD 只作为 M1 的粗分段提示；M2 会用 Silero VAD 重新分段后再转写。
 */
class AudioCapture(
    private val target: File,
    private val originProvider: () -> Long,
    private val onSegment: (startMs: Long, endMs: Long, rmsDb: Double) -> Unit
) {
    private val sampleRate = 16000
    private var thread: Thread? = null

    @Volatile
    private var running = true

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    var paused: Boolean = false

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "mindgap-audio").apply { start() }
    }

    fun stop() {
        running = false
        // 先停录音解除 read 阻塞，循环才能立刻退出；
        // 否则 join(800) 可能超时，旧线程继续持有麦克风并向 PCM 追加
        runCatching { record?.stop() }
        runCatching { thread?.join(800) }
        thread = null
    }

    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return

        val chunkSamples = sampleRate / 10 // 100ms 一块
        val bufferSize = maxOf(minBuffer, chunkSamples * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        this.record = record
        if (!running) {
            record.release()
            this.record = null
            return
        }

        record.startRecording()
        val originMs = originProvider()
        val buffer = ShortArray(chunkSamples)
        val silence = ByteArray(chunkSamples * 2)
        var writtenSamples = 0L
        var speechStartSample = -1L
        var lastVoiceSample = 0L
        var peakDb = MIN_DB

        target.parentFile?.mkdirs()
        FileOutputStream(target, true).use { out ->
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (paused) {
                    // 写入等长静音：不保留暂停期间的音频内容，但保持 PCM 与墙钟等长
                    out.write(silence, 0, read * 2)
                    writtenSamples += read
                    speechStartSample = -1L
                    peakDb = MIN_DB
                    continue
                }

                out.write(shortsToBytes(buffer, read))
                writtenSamples += read
                val db = rmsDb(buffer, read)

                if (db > VOICE_DB) {
                    if (speechStartSample < 0) {
                        speechStartSample = writtenSamples - read
                        peakDb = db
                    }
                    lastVoiceSample = writtenSamples
                    if (db > peakDb) peakDb = db
                } else if (speechStartSample >= 0 && writtenSamples - lastVoiceSample > SILENCE_SAMPLES) {
                    emitSegment(originMs, speechStartSample, lastVoiceSample, peakDb)
                    speechStartSample = -1L
                    peakDb = MIN_DB
                }
            }
            if (speechStartSample >= 0 && lastVoiceSample > speechStartSample) {
                emitSegment(originMs, speechStartSample, lastVoiceSample, peakDb)
            }
        }

        runCatching { record.stop() }
        record.release()
        this.record = null
    }

    private fun emitSegment(originMs: Long, startSample: Long, endSample: Long, peakDb: Double) {
        val startMs = originMs + startSample * 1000 / sampleRate
        val endMs = originMs + endSample * 1000 / sampleRate
        if (endMs > startMs) onSegment(startMs, endMs, peakDb)
    }

    private fun shortsToBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun rmsDb(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        val rms = sqrt(sum / count)
        return if (rms <= 0.0) MIN_DB else (20 * log10(rms)).coerceIn(MIN_DB, 0.0)
    }

    companion object {
        private const val VOICE_DB = -45.0
        private const val SILENCE_HOLD_MS = 600L
        private const val SILENCE_SAMPLES = SILENCE_HOLD_MS * 16000 / 1000
        private const val MIN_DB = -96.0
    }
}
