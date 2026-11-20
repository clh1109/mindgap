package com.mindgap.app.perception

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.InputStream

/**
 * 离线转写：Silero VAD 先切出语音段，再逐段用 Paraformer-small int8 识别。
 *
 * 时间基准：VAD 返回的 start 是「喂入采样流」的绝对采样下标，
 * 加上 PCM 起点在会话时间轴上的 origin，就能得到与屏幕帧同基准的毫秒时间。
 * 全程单线程顺序解码，不占用麦克风，也不联网。
 */
class OfflineTranscriber(
    private val modelFile: File,
    private val tokensFile: File,
    private val vadFile: File
) {
    fun transcribe(
        pcmFile: File,
        pcmOriginMs: Long,
        onProgress: (Float) -> Unit
    ): List<SpeechTranscript> {
        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = modelFile.absolutePath),
                    tokens = tokensFile.absolutePath,
                    modelType = "paraformer",
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )
        )
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.3f,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = 20.0f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu"
            )
        )

        val results = mutableListOf<SpeechTranscript>()
        try {
            val totalBytes = pcmFile.length().coerceAtLeast(1L)
            var processedBytes = 0L
            val window = FloatArray(WINDOW_SIZE)
            val bytes = ByteArray(WINDOW_SIZE * 2)

            pcmFile.inputStream().buffered(64 * 1024).use { input ->
                while (true) {
                    val read = readChunk(input, bytes)
                    if (read <= 0) break
                    val count = read / 2
                    for (i in 0 until count) {
                        val low = bytes[i * 2].toInt() and 0xFF
                        val high = bytes[i * 2 + 1].toInt()
                        window[i] = ((high shl 8) or low).toShort() / 32768.0f
                    }
                    vad.acceptWaveform(if (count == WINDOW_SIZE) window else window.copyOf(count))
                    drain(vad, recognizer, pcmOriginMs, results)

                    processedBytes += read
                    onProgress((processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
            }

            vad.flush()
            drain(vad, recognizer, pcmOriginMs, results)
        } finally {
            runCatching { vad.release() }
            runCatching { recognizer.release() }
        }
        onProgress(1f)
        return results
    }

    private fun drain(
        vad: Vad,
        recognizer: OfflineRecognizer,
        originMs: Long,
        out: MutableList<SpeechTranscript>
    ) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (segment.samples.size < SAMPLE_RATE / 4) continue

            val text = runCatching {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }.getOrElse { "" }
            if (text.isEmpty()) continue

            val startMs = originMs + segment.start * 1000 / SAMPLE_RATE
            val endMs = startMs + segment.samples.size * 1000L / SAMPLE_RATE
            out += SpeechTranscript(
                segmentId = "s${out.size + 1}",
                startMs = startMs,
                endMs = endMs,
                text = text
            )
        }
    }

    /** 按固定长度读满一块（文件尾允许不足），保证 VAD 每次收到 512 个采样。 */
    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512
    }
}
