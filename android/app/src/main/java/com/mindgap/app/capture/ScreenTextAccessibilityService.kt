package com.mindgap.app.capture

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍取文本：比 OCR 快、省电、准确，是屏幕文本的首选通道；OCR 只在它取不到时兜底。
 * 这里只读取、不上传；未开始采集时直接丢弃事件。
 */
class ScreenTextAccessibilityService : AccessibilityService() {

    private var lastTextHash: Int = 0
    private var lastEmitAt: Long = 0L
    private var lastEraseAt: Long = 0L
    private var lastSearchAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        // 未在采集时不做任何节点遍历，避免后台读取屏幕内容
        if (CaptureSession.state.value.phase != CapturePhase.RUNNING) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                CaptureSession.recordAction(RawEventType.APP_SWITCH, pkg)
                // 切窗后立即抓一次文本，避免 M3 因等待下一次内容变化而丢失当前屏幕
                captureText(pkg, force = true)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                CaptureSession.recordAction(RawEventType.SCROLL, pkg)

            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                CaptureSession.recordAction(RawEventType.CLICK, pkg)

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                handleTextChanged(e, pkg)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                captureText(pkg)
        }
    }

    private fun captureText(pkg: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEmitAt < MIN_INTERVAL_MS) return

        val root = rootInActiveWindow ?: return
        val builder = StringBuilder()
        collect(root, builder, 0)

        val text = builder.toString().trim()
        if (text.isEmpty()) return

        val hash = text.hashCode()
        lastEmitAt = now
        if (hash == lastTextHash) return
        lastTextHash = hash

        CaptureSession.recordScreenText(
            text = text,
            hash = Integer.toHexString(hash),
            appPackage = pkg
        )
    }

    /**
     * 只记录「搜索输入 / 删除改写」这两类动作，不保存输入内容。
     * 密码框直接跳过；同类动作做节流，避免每个字符都写一条事件。
     */
    private fun handleTextChanged(event: AccessibilityEvent, pkg: String) {
        val node = event.source ?: return
        if (node.isPassword || event.isPassword) return

        val before = event.beforeText?.toString().orEmpty()
        val after = event.text?.toString().orEmpty()
        val now = SystemClock.elapsedRealtime()

        if (before.length - after.length >= MIN_ERASE_CHARS) {
            if (now - lastEraseAt >= ERASE_INTERVAL_MS) {
                lastEraseAt = now
                CaptureSession.recordAction(RawEventType.ERASE, pkg)
            }
            return
        }

        if (after.length > before.length &&
            isSearchField(node) &&
            now - lastSearchAt >= SEARCH_INTERVAL_MS
        ) {
            lastSearchAt = now
            CaptureSession.recordAction(RawEventType.SEARCH, pkg)
        }
    }

    private fun isSearchField(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName.orEmpty()
        if (viewId.contains("search", ignoreCase = true)) return true
        val hint = node.hintText?.toString().orEmpty()
        return hint.contains("搜索") || hint.contains("search", ignoreCase = true)
    }

    private fun collect(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || builder.length > MAX_CHARS) return
        node.text?.toString()?.let { appendLine(builder, it) }
        node.contentDescription?.toString()?.let { appendLine(builder, it) }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), builder, depth + 1)
        }
    }

    private fun appendLine(builder: StringBuilder, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        if (builder.endsWith(trimmed)) return
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(trimmed)
    }

    override fun onInterrupt() {
        // 无需处理：采集暂停由 CaptureSession 统一控制
    }

    companion object {
        private const val MIN_INTERVAL_MS = 800L
        private const val ERASE_INTERVAL_MS = 1000L
        private const val SEARCH_INTERVAL_MS = 1500L
        private const val MIN_ERASE_CHARS = 2
        private const val MAX_DEPTH = 40
        private const val MAX_CHARS = 6000
    }
}
