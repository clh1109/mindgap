package com.mindgap.app.align

import com.mindgap.app.capture.RawEvent
import com.mindgap.app.capture.RawEventType
import com.mindgap.app.data.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineAlignerTest {
    @Test
    fun `搜索与删除会进入时间轴动作通道`() {
        val session = TimelineAligner(
            sessionId = "7a3f2c18-55d4-4d21-9c6e-0b6f4a1d9e32",
            startedAtWallClock = 0L,
            events = listOf(
                RawEvent(
                    t = 0,
                    type = RawEventType.SCREEN_TEXT,
                    text = "这是一段足够长的屏幕原文，用于让对齐器建立文档段落。",
                    textHash = "hash-p0",
                    appPackage = "com.example.reader"
                ),
                RawEvent(t = 500, type = RawEventType.SCROLL, appPackage = "com.example.reader"),
                RawEvent(t = 1000, type = RawEventType.SEARCH, appPackage = "com.example.reader"),
                RawEvent(t = 1500, type = RawEventType.ERASE, appPackage = "com.example.reader"),
                RawEvent(t = 2000, type = RawEventType.APP_SWITCH, appPackage = "com.example.search"),
                RawEvent(t = 2500, type = RawEventType.MARKER, note = "session_end")
            ),
            perception = null,
            meta = AlignmentMeta(
                deviceModel = "test",
                androidApi = 34,
                locale = "zh-CN",
                screenWidth = 1080,
                screenHeight = 1920,
                appVersion = "0.3.0",
                asrModel = null,
                ocrModel = null
            )
        ).align()

        assertEquals(5, session.frames.size)
        assertEquals(ActionType.NONE, session.frames[0].action.type)
        assertEquals(ActionType.SCROLL, session.frames[1].action.type)
        assertEquals(ActionType.SEARCH, session.frames[2].action.type)
        assertEquals(ActionType.ERASE, session.frames[3].action.type)
        assertEquals(ActionType.SWITCH_APP, session.frames[4].action.type)
        // 搜索只记动作，不落查询内容
        assertNull(session.frames[2].action.query)
    }

    @Test
    fun `暂停会切断停留与文本去重上下文`() {
        val session = TimelineAligner(
            sessionId = "pause-reset-test",
            startedAtWallClock = 0L,
            events = listOf(
                RawEvent(
                    t = 0,
                    type = RawEventType.SCREEN_TEXT,
                    text = "这是一段足够长的屏幕原文，用于让对齐器建立文档段落。",
                    textHash = "hash-p0",
                    appPackage = "com.example.reader"
                ),
                RawEvent(t = 2000, type = RawEventType.MARKER, note = "pause"),
                RawEvent(t = 4000, type = RawEventType.MARKER, note = "resume"),
                RawEvent(t = 6000, type = RawEventType.MARKER, note = "session_end")
            ),
            perception = null,
            meta = AlignmentMeta(
                deviceModel = "test",
                androidApi = 34,
                locale = "zh-CN",
                screenWidth = 1080,
                screenHeight = 1920,
                appVersion = "0.3.0",
                asrModel = null,
                ocrModel = null
            )
        ).align()

        assertEquals(12, session.frames.size)
        // 暂停区间输出空帧
        assertNull(session.frames[4].screen)
        assertEquals(0L, session.frames[4].features?.dwellMs)
        // 恢复后：停留重新累计、文本重新输出、记一次新进入（与评测合成器一致）
        val resumed = session.frames[8]
        assertEquals(0L, resumed.features?.dwellMs)
        assertNotNull(resumed.screen?.text)
        assertEquals(1, resumed.features?.revisitCount)
        assertEquals(500L, session.frames[9].features?.dwellMs)
    }
}
