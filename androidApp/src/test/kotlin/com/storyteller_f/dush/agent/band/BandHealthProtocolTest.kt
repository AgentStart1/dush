package com.storyteller_f.dush.agent.band

import org.junit.Test
import kotlin.test.*
import java.time.*

class BandHealthProtocolTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun readsUnsignedStepsAndRejectsUnknownPackets() {
        assertEquals(65535, BandHealthProtocol.steps(ByteArray(13).apply { this[1] = -1; this[2] = -1 }))
        assertNull(BandHealthProtocol.steps(byteArrayOf(1, 2)))
    }
    @Test fun parsesBothHeartRateWidthsAndMissingValueSentinels() {
        assertEquals(72, BandHealthProtocol.heart(byteArrayOf(0, 72)))
        assertEquals(160, BandHealthProtocol.heart(byteArrayOf(1, -96, 0)))
        assertNull(BandHealthProtocol.heart(byteArrayOf(0, -1)))
        assertNull(BandHealthProtocol.heart(byteArrayOf(1, 72)))
    }
    @Test fun historyRequestUsesLocalClockAndQuarterHourOffset() {
        assertContentEquals(byteArrayOf(1, 1, -22, 7, 10, 19, 8, 0, 0, 32), BandHealthProtocol.historyRequest(1, at("2026-10-19T00:00:00Z"), zone))
        assertEquals(at("2026-10-19T00:00:00Z"), BandHealthProtocol.historyTime(byteArrayOf(-22,7,10,19,8,0,0,32)))
    }
    @Test fun activityIsMinuteAlignedAndDoesNotInventMissingHeartRate() {
        val start = at("2026-10-18T16:00:00Z")
        val records = BandHealthProtocol.activity(byteArrayOf(120, 0, 0, -1, 0, 1, 90, 90, 64, 0, 12, 70, 0, 0, 0, 0), start)
        assertEquals(ActivityMinute(start, 120, 0, null), records[0])
        assertEquals(ActivityMinute(start + 60_000, 64, 12, 70), records[1])
        assertFailsWith<IllegalArgumentException> { BandHealthProtocol.activity(ByteArray(7), start) }
    }
    @Test fun manualHeartHistoryKeepsAbsoluteTimestampAndExcludes255() {
        val seconds = at("2026-10-19T00:00:00Z") / 1000
        val stamp = ByteArray(4) { (seconds shr (it*8)).toByte() }
        val samples = BandHealthProtocol.manualHeart(stamp + byteArrayOf(32,72) + stamp + byteArrayOf(32,-1))
        assertEquals(1,samples.size)
        assertEquals(seconds*1000,samples.single().at)
        assertEquals(72,samples.single().bpm)
    }
    @Test fun missingSleepMarkersDoNotBecomeAClaimOfZeroSleep() {
        val now = at("2026-10-19T12:00:00Z")
        val snapshot = PeripheralHealthSnapshot(activity=listOf(ActivityMinute(at("2026-10-18T16:00:00Z"),115,0,null)))
        assertNull(snapshot.summarize(now,zone).sleepMinutes)
    }
    @Test fun sleepTotalsOnlyUseReportedSleepMinutesAcrossMidnight() {
        val now = at("2026-10-19T12:00:00Z")
        val start = at("2026-10-18T15:59:00Z")
        val snapshot = PeripheralHealthSnapshot(activity = listOf(ActivityMinute(start,120,0,60), ActivityMinute(start+60_000,120,0,62), ActivityMinute(start+120_000,115,0,null)))
        val summary = snapshot.summarize(now, zone)
        assertEquals(2, summary.sleepMinutes)
        assertEquals(start, summary.sleepStart)
        assertEquals(start+120_000, summary.sleepEnd)
        assertEquals(62, summary.latestHeart?.bpm)
    }
    @Test fun yesterdayStepSnapshotIsNotPresentedAsToday() {
        val now = at("2026-10-19T12:00:00Z")
        val summary = PeripheralHealthSnapshot(steps = StepReading(now-86_400_000,"2026-10-18",1234)).summarize(now, zone)
        assertNull(summary.steps)
        assertNull(summary.sleepMinutes)
        assertNull(summary.latestHeart)
    }
    @Test fun repeatedLocalClockHourDoesNotMergeDifferentSleepMinutes() {
        val first = at("2026-11-01T05:30:00Z")
        val second = at("2026-11-01T06:30:00Z")
        val summary = PeripheralHealthSnapshot(activity = listOf(ActivityMinute(first,120,0,null), ActivityMinute(second,120,0,null)))
            .summarize(at("2026-11-01T18:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(2, summary.sleepMinutes)
    }
}
