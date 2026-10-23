package com.storyteller_f.dush.agent.band

import org.junit.Test
import kotlin.test.*

class BandReplyStreamTest {
    @Test fun waitsUntil600MillisecondsAfterTheLatestChangedSnapshot() {
        val stream = BandReplyStream()
        stream.offer("你", false, 0)
        assertNull(stream.next(599))
        stream.offer("你好", false, 500)
        assertNull(stream.next(600))
        assertNull(stream.next(1_099))
        assertEquals("你好", stream.next(1_100))
    }
    @Test fun finalSnapshotFlushesImmediatelyWithoutWaitingForDebounce() {
        val stream = BandReplyStream()
        stream.offer("你", false, 0)
        stream.offer("你好", true, 100)
        assertEquals("你好", stream.next(100))
        assertTrue(stream.finished)
    }
    @Test fun continuousTokensAreCoalescedIntoOneLatestSnapshot() {
        val stream = BandReplyStream()
        for (i in 1..10) {
            stream.offer("甲".repeat(i), false, i * 100L)
            assertNull(stream.next(i * 100L))
        }
        assertEquals("甲".repeat(10), stream.next(1_600))
    }
    @Test fun finalizingUnchangedContentDoesNotSendItAgain() {
        val stream = BandReplyStream().apply { offer("完成", false, 0) }
        assertNotNull(stream.next(600))
        stream.offer("完成", true, 700)
        assertNull(stream.next(700))
        assertTrue(stream.finished)
    }
    @Test fun limitsLongRepliesTo300UnicodeCharactersWithoutPages() {
        val input = "中😀文".repeat(200)
        val stream = BandReplyStream().apply { offer(input, true, 0) }
        assertEquals("中😀文".repeat(100), stream.next(0))
        assertTrue(stream.finished)
        assertNull(stream.next(4_000))
        assertNull(stream.next(8_000))
    }
    @Test fun furtherGenerationPastLimitDoesNotRepeatTheSamePreview() {
        val stream = BandReplyStream().apply { offer("甲".repeat(300), false, 0) }
        assertNotNull(stream.next(600))
        stream.offer("甲".repeat(300) + "乙".repeat(200), false, 700)
        assertNull(stream.next(700))
        assertFalse(stream.finished)
        stream.offer("甲".repeat(300) + "乙".repeat(400), true, 1_200)
        assertNull(stream.next(1_200))
        assertTrue(stream.finished)
    }
    @Test fun hiddenTokensPast300CharactersDoNotResetDebounce() {
        val stream = BandReplyStream()
        stream.offer("甲".repeat(300), false, 0)
        stream.offer("甲".repeat(300) + "乙", false, 500)
        assertEquals("甲".repeat(300), stream.next(600))
    }
    @Test fun ignoresUpdatesAfterFinalSnapshot() {
        val stream = BandReplyStream().apply { offer("最终回复", true, 0) }
        stream.offer("迟到的内容", false, 0)
        assertEquals("最终回复", stream.next(0))
    }
    @Test fun removesEmbeddedTerminatorsWithoutLosingFollowingContent() {
        val stream = BandReplyStream().apply { offer("前\u0000后", true, 0) }
        assertEquals("前后", stream.next(0))
    }
    @Test fun replyPacketUsesCharacterLimitRatherThanOldByteLimit() {
        val packet = bandText(9, "中".repeat(301))
        assertEquals(902, packet.size)
        assertEquals("中".repeat(300), String(packet.copyOfRange(1, packet.lastIndex), Charsets.UTF_8))
        assertEquals(0, packet.last().toInt())
    }
}
