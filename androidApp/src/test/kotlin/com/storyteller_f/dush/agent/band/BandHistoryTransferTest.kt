package com.storyteller_f.dush.agent.band

import org.junit.Test
import kotlin.test.*
import java.time.*

class BandHistoryTransferTest {
    private val start = Instant.parse("2026-10-18T16:00:00Z").toEpochMilli()
    private fun header(count: Int) = byteArrayOf(16,1,1,count.toByte(),(count shr 8).toByte(),0,0,-22,7,10,19,0,0,0,32)
    private fun transfer() = BandHistoryTransfer(1,start,ZoneId.of("Asia/Singapore"))
    @Test fun collectsCounterWrappedChunksIntoDistinctMinuteRecords() {
        val transfer = transfer()
        assertEquals(BandHistoryTransfer.Update.AwaitData,transfer.metadata(header(257*8)))
        repeat(257) { transfer.data(byteArrayOf(it.toByte(),120,0,1,70,0,0,0,0)) }
        val result = transfer.metadata(byteArrayOf(16,2,1)) as BandHistoryTransfer.Update.Complete
        assertEquals(257,result.activity.size)
        assertEquals(start + 256*60_000,result.activity.last().at)
        assertNull(transfer.metadata(byteArrayOf(16,2,1)))
    }
    @Test fun rejectsMissingOrOutOfOrderPackets() {
        val transfer = transfer();transfer.metadata(header(16))
        assertFailsWith<IllegalArgumentException> { transfer.data(byteArrayOf(1,1,2,3)) }
    }
    @Test fun rejectsPartialFinalMinute() {
        val transfer = transfer();transfer.metadata(header(7));transfer.data(byteArrayOf(0,1,2,3,4,5,6,7))
        assertFailsWith<IllegalArgumentException> { transfer.metadata(byteArrayOf(16,2,1)) }
    }
    @Test fun emptyHistoryCompletesWithoutADeleteAcknowledgement() {
        val result = transfer().metadata(header(0)) as BandHistoryTransfer.Update.Complete
        assertTrue(result.activity.isEmpty()); assertTrue(result.heart.isEmpty()); assertNull(result.next)
    }
}
