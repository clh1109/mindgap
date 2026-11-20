package com.mindgap.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mindgap.app.MainActivity
import com.mindgap.app.R
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * M1 前台采集服务：屏幕指纹（1fps）+ 麦克风 PCM + 会话状态维护。
 * 不做 OCR / ASR —— 那些在 M2 读取本服务产出的数据。
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.mindgap.app.action.START"
        const val ACTION_PAUSE = "com.mindgap.app.action.PAUSE"
        const val ACTION_RESUME = "com.mindgap.app.action.RESUME"
        const val ACTION_STOP = "com.mindgap.app.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val SCREEN_INTERVAL_MS = 1000L
        private const val GRID = 8
        private const val PICKS = 2

        /** 感知哈希汉明距离阈值：小于它视为同一画面，不重复落盘。 */
        private const val KEYFRAME_HAMMING = 6
        private const val KEYFRAME_MIN_INTERVAL_MS = 1500L
        private const val KEYFRAME_MAX_SIDE = 1920
        private const val KEYFRAME_QUALITY = 65

        /** 单会话关键帧上限，避免长时间录屏把磁盘写满（600 张约 100 MB）。 */
        private const val KEYFRAME_MAX_COUNT = 600
    }

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionThread: HandlerThread? = null
    private var audioCapture: AudioCapture? = null
    private var running = false

    private var keyframeExecutor: ExecutorService? = null
    private var keyframeSessionId: String? = null
    private var lastKeyframeBits = -1L
    private var lastKeyframeAt = 0L
    private var keyframeSaved = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) startCapture(intent)
            ACTION_PAUSE -> if (running) {
                CaptureSession.pause()
                audioCapture?.paused = true
                updateNotification()
            }
            ACTION_RESUME -> if (running) {
                CaptureSession.resume()
                audioCapture?.paused = false
                updateNotification()
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return
        }

        val includeAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        running = true
        val sessionId = CaptureSession.start(this, includeAudio)
        keyframeSessionId = sessionId
        keyframeExecutor = Executors.newSingleThreadExecutor()
        lastKeyframeBits = -1L
        lastKeyframeAt = 0L
        keyframeSaved = 0

        // API 34 要求先进入前台（带类型），才能申请 MediaProjection
        startForegroundCompat(includeAudio)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching {
            manager.getMediaProjection(resultCode, resultData)
        }.getOrNull()

        if (mediaProjection == null) {
            finishAndStop("屏幕录制授权失败")
            return
        }
        projection = mediaProjection

        projectionThread = HandlerThread("mindgap-screen").apply { start() }
        val threadHandler = Handler(projectionThread!!.looper)

        // API 34 起必须先注册回调，再创建 VirtualDisplay
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 回调在投影线程，统一回主线程收尾，避免跨线程操作 View/Surface
                handler.post {
                    if (running) stopCapture("系统已停止屏幕共享")
                }
            }
        }, threadHandler)

        val bounds = currentBounds()
        val densityDpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(
            bounds.width(),
            bounds.height(),
            PixelFormat.RGBA_8888,
            PICKS
        )
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "mindgap-capture",
            bounds.width(),
            bounds.height(),
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            threadHandler
        )

        handler.postDelayed(screenTick, SCREEN_INTERVAL_MS)
        handler.postDelayed(elapsedTick, 1000L)

        if (includeAudio) {
            val audioFile = EventStore.audioFile(this, sessionId)
            audioCapture = AudioCapture(
                target = audioFile,
                originProvider = {
                    CaptureSession.nowMs().also { CaptureSession.recordAudioStart(it) }
                },
                onSegment = { startMs, endMs, rmsDb ->
                    CaptureSession.recordAudioSegment(startMs, endMs, rmsDb)
                }
            ).also { it.start() }
        }

        updateNotification()
    }

    private fun currentBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WindowManager::class.java)
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics: DisplayMetrics = resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private val screenTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (CaptureSession.state.value.phase == CapturePhase.RUNNING) {
                sampleScreenOnce()
            }
            handler.postDelayed(this, SCREEN_INTERVAL_MS)
        }
    }

    private val elapsedTick = object : Runnable {
        override fun run() {
            if (!running) return
            CaptureSession.onTick(CaptureSession.nowMs())
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * 1fps 采样：始终计算 8x8 亮度指纹；只有画面明显变化（汉明距离超阈值）时才落盘关键帧。
     * 关键帧供 M2 的 ML Kit 中文 OCR 使用，普通帧只留指纹，控制磁盘与隐私成本。
     */
    private fun sampleScreenOnce() {
        val reader = imageReader ?: return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            val cells = DoubleArray(GRID * GRID)
            var sum = 0.0

            for (gy in 0 until GRID) {
                for (gx in 0 until GRID) {
                    val x = (image.width * (gx + 0.5) / GRID).toInt()
                    val y = (image.height * (gy + 0.5) / GRID).toInt()
                    val index = y * rowStride + x * pixelStride
                    var luminance = 0.0
                    if (index >= 0 && index + 2 < limit) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF
                        luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    }
                    cells[gy * GRID + gx] = luminance
                    sum += luminance
                }
            }

            val average = sum / cells.size
            var bits = 0L
            for (i in cells.indices) {
                if (cells[i] >= average) bits = bits or (1L shl i)
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            val isFirst = lastKeyframeBits < 0
            val distance = if (isFirst) GRID * GRID else java.lang.Long.bitCount(bits xor lastKeyframeBits)
            val spaced = nowElapsed - lastKeyframeAt >= KEYFRAME_MIN_INTERVAL_MS
            var imagePath: String? = null
            val canSave = keyframeSaved < KEYFRAME_MAX_COUNT
            if ((isFirst || distance >= KEYFRAME_HAMMING) && spaced && canSave) {
                imagePath = saveKeyframe(image, bits)
                if (imagePath != null) {
                    lastKeyframeBits = bits
                    lastKeyframeAt = nowElapsed
                    keyframeSaved++
                }
            }

            CaptureSession.recordScreenFrame(java.lang.Long.toHexString(bits), average, imagePath)
        } finally {
            image.close()
        }
    }

    /**
     * 把当前 ImageReader 缓冲复制成 JPEG。像素复制必须在 image.close() 之前完成，
     * 压缩与缩放放到单线程池，避免 1fps 采样阻塞主线程。
     */
    private fun saveKeyframe(image: Image, bits: Long): String? {
        val sessionId = keyframeSessionId ?: return null
        val executor = keyframeExecutor ?: return null
        val plane = image.planes[0]
        if (plane.pixelStride != 4) return null

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val rowBytes = width * 4
        val pixels = ByteArray(width * height * 4)
        val row = ByteArray(rowBytes)

        for (y in 0 until height) {
            val base = y * rowStride
            val available = buffer.limit() - base
            if (available <= 0) break
            val count = minOf(rowBytes, available)
            buffer.position(base)
            buffer.get(row, 0, count)
            if (count < rowBytes) java.util.Arrays.fill(row, count, rowBytes, 0.toByte())
            System.arraycopy(row, 0, pixels, y * rowBytes, rowBytes)
        }

        val file = File(EventStore.framesDir(this, sessionId), "${CaptureSession.nowMs()}-$bits.jpg")
        executor.execute {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                val longSide = maxOf(width, height)
                val scaled = if (longSide > KEYFRAME_MAX_SIDE) {
                    val ratio = KEYFRAME_MAX_SIDE.toFloat() / longSide
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (width * ratio).toInt().coerceAtLeast(1),
                        (height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                try {
                    FileOutputStream(file).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, KEYFRAME_QUALITY, out)
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } catch (_: Exception) {
                file.delete()
            } finally {
                bitmap.recycle()
            }
        }
        return file.absolutePath
    }

    private fun startForegroundCompat(includeAudio: Boolean) {
        val type = if (includeAudio) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun stopCapture(message: String? = null) {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)

        audioCapture?.stop()
        audioCapture = null
        keyframeExecutor?.shutdown()
        keyframeExecutor = null
        keyframeSessionId = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        projectionThread?.quitSafely()
        projectionThread = null

        CaptureSession.finish(message ?: "采集结束，原始事件已落盘")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishAndStop(message: String) {
        if (!running) return
        CaptureSession.recordMarker(message)
        stopCapture("采集失败：$message")
    }

    override fun onDestroy() {
        if (running) stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "学习采集",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "采集期间常驻提示，可随时暂停或结束"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val paused = CaptureSession.state.value.phase == CapturePhase.PAUSED
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(
                if (paused) ACTION_RESUME else ACTION_PAUSE
            ),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(if (paused) "MindGap 已暂停" else "MindGap 正在采集")
            .setContentText(if (paused) "事件已停止写入" else "屏幕指纹 · 无障碍文本 · 麦克风")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, if (paused) "继续" else "暂停", toggleIntent)
            .addAction(0, "结束", stopIntent)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }
}
