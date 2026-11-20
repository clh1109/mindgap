package com.mindgap.app.perception

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

enum class ModelPhase { MISSING, DOWNLOADING, EXTRACTING, READY, FAILED }

data class ModelSnapshot(
    val phase: ModelPhase = ModelPhase.MISSING,
    val progress: Float = 0f,
    val message: String = ""
)

/**
 * 离线模型按需下载：Paraformer-small int8（约 74 MB）+ Silero VAD（约 0.6 MB）。
 * 模型只下载一次，之后转写与 OCR 全部在端侧完成，不依赖网络。
 *
 * 网络受限时可把下面两个 URL 换成可达镜像；解压逻辑只依赖 tar.bz2 内的
 * model.int8.onnx 与 tokens.txt 两个文件。
 */
object ModelManager {
    private const val ASR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
    private const val VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    private const val ASR_MODEL_NAME = "model.int8.onnx"
    private const val TOKENS_NAME = "tokens.txt"
    private const val VAD_NAME = "silero_vad.onnx"

    // 用最小体积识别截断文件，避免半包被当成可用模型
    private const val MIN_MODEL_BYTES = 50L * 1024 * 1024
    private const val MIN_TOKENS_BYTES = 10L * 1024
    private const val MIN_VAD_BYTES = 300L * 1024

    private val _state = MutableStateFlow(ModelSnapshot())
    val state: StateFlow<ModelSnapshot> = _state.asStateFlow()

    private val downloading = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 从 UI 触发下载：用管理器自己的作用域，切页离开采集页也不会中断。 */
    fun downloadAsync(context: Context) {
        scope.launch { download(context.applicationContext) }
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun asrDir(context: Context): File = File(modelsDir(context), "asr").apply { mkdirs() }

    fun vadDir(context: Context): File = File(modelsDir(context), "vad").apply { mkdirs() }

    fun asrModelFile(context: Context): File = File(asrDir(context), ASR_MODEL_NAME)

    fun asrTokensFile(context: Context): File = File(asrDir(context), TOKENS_NAME)

    fun vadModelFile(context: Context): File = File(vadDir(context), VAD_NAME)

    fun isReady(context: Context): Boolean =
        asrModelFile(context).length() >= MIN_MODEL_BYTES &&
            asrTokensFile(context).length() >= MIN_TOKENS_BYTES &&
            vadModelFile(context).length() >= MIN_VAD_BYTES

    /** UI 进入页面时同步一次状态；下载中不覆盖进度。 */
    fun refresh(context: Context) {
        if (downloading.get()) return
        when {
            isReady(context) ->
                _state.value = ModelSnapshot(ModelPhase.READY, 1f, "离线模型已就绪")
            _state.value.phase != ModelPhase.FAILED ->
                _state.value = ModelSnapshot(ModelPhase.MISSING, 0f, "首次使用需下载约 75 MB 模型")
        }
    }

    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isReady(context)) {
            _state.value = ModelSnapshot(ModelPhase.READY, 1f, "离线模型已就绪")
            return@withContext true
        }
        if (!downloading.compareAndSet(false, true)) return@withContext false

        _state.value = ModelSnapshot(ModelPhase.DOWNLOADING, 0f, "准备下载模型…")
        val app = context.applicationContext
        val cacheDir = File(app.cacheDir, "model-download").apply { mkdirs() }
        val archive = File(cacheDir, "asr.tar.bz2")
        val vadTemp = File(cacheDir, VAD_NAME)
        try {
            val needAsr = asrModelFile(app).length() < MIN_MODEL_BYTES ||
                asrTokensFile(app).length() < MIN_TOKENS_BYTES
            val needVad = vadModelFile(app).length() < MIN_VAD_BYTES

            val tasks = mutableListOf<Triple<String, File, String>>()
            if (needAsr) tasks += Triple(ASR_URL, archive, "语音模型")
            if (needVad) tasks += Triple(VAD_URL, vadTemp, "VAD 模型")
            tasks.forEachIndexed { index, task ->
                val (url, target, label) = task
                downloadFile(url, target) { copied, total ->
                    val local = if (total > 0) copied.toFloat() / total else 0f
                    val overall = (index + local) / tasks.size
                    _state.value = ModelSnapshot(
                        ModelPhase.DOWNLOADING,
                        overall * 0.97f,
                        "下载$label ${(local * 100).toInt()}%"
                    )
                }
            }

            if (needAsr) {
                _state.value = ModelSnapshot(ModelPhase.EXTRACTING, 0.98f, "解压语音模型")
                val staging = File(modelsDir(app), "asr-staging")
                staging.deleteRecursively()
                staging.mkdirs()
                try {
                    extractAsr(archive, staging)
                    val target = asrDir(app)
                    target.deleteRecursively()
                    if (!staging.renameTo(target)) {
                        throw IllegalStateException("无法安装语音模型")
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
            if (needVad) {
                _state.value = ModelSnapshot(ModelPhase.EXTRACTING, 0.99f, "安装 VAD 模型")
                val part = File(vadDir(app), "$VAD_NAME.part")
                vadTemp.copyTo(part, overwrite = true)
                if (!part.renameTo(vadModelFile(app))) {
                    part.delete()
                    throw IllegalStateException("无法安装 VAD 模型")
                }
            }

            if (!isReady(app)) {
                throw IllegalStateException("模型文件不完整")
            }
            _state.value = ModelSnapshot(ModelPhase.READY, 1f, "离线模型已就绪")
            true
        } catch (e: Exception) {
            _state.value = ModelSnapshot(
                ModelPhase.FAILED,
                0f,
                "模型下载失败：${e.message ?: e.javaClass.simpleName}"
            )
            false
        } finally {
            archive.delete()
            vadTemp.delete()
            cacheDir.deleteRecursively()
            downloading.set(false)
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onBytes: (copied: Long, total: Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "MindGap/0.2")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var copied = 0L
            var lastReported = 0L
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= 512 * 1024) {
                            lastReported = copied
                            onBytes(copied, total)
                        }
                    }
                }
            }
            onBytes(copied, if (total > 0) total else copied)
        } finally {
            connection.disconnect()
        }
    }

    /** 只取压缩包顶层的 model.int8.onnx 与 tokens.txt，跳过测试音频等无关文件。 */
    private fun extractAsr(archive: File, targetDir: File) {
        val wanted = setOf(ASR_MODEL_NAME, TOKENS_NAME)
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    val relative = name.substringAfter('/', name)
                    if (relative in wanted) {
                        val out = File(targetDir, relative)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }
}
