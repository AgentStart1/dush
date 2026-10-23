package com.storyteller_f.dush.agent.band

import java.time.*

data class ActivityMinute(val at: Long, val kind: Int, val steps: Int, val heartRate: Int?)
data class HeartReading(val at: Long, val bpm: Int, val source: String)
data class StepReading(val at: Long, val date: String, val steps: Int)
data class PeripheralHealthSnapshot(
    val steps: StepReading? = null,
    val heartRates: List<HeartReading> = emptyList(),
    val activity: List<ActivityMinute> = emptyList(),
    val historySyncedAt: Long? = null,
)
data class HealthDay(val date: String, val recordedSteps: Int, val recordedMinutes: Int, val sleepMinutes: Int, val averageHeartRate: Int?)
data class PeripheralHealthSummary(
    val steps: StepReading?,
    val latestHeart: HeartReading?,
    val sleepMinutes: Int?,
    val sleepStart: Long?,
    val sleepEnd: Long?,
    val historySyncedAt: Long?,
    val days: List<HealthDay>,
)
sealed interface HealthSyncStatus {
    data class Syncing(val description: String) : HealthSyncStatus
    data class Complete(val at: Long) : HealthSyncStatus
    data class Failed(val message: String) : HealthSyncStatus
}

/** Sleep totals use the band's sleep category only, without inventing deep/REM stages. */
fun PeripheralHealthSnapshot.summarize(now: Long, zone: ZoneId): PeripheralHealthSummary {
    fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = date(now)
    val recent = activity.filter { it.at <= now && date(it.at) >= today.minusDays(6) }
    val days = recent.groupBy { date(it.at) }.toSortedMap(reverseOrder()).map { (day, minutes) ->
        val heart = minutes.mapNotNull { it.heartRate }
        HealthDay(day.toString(), minutes.sumOf { it.steps }, minutes.size, minutes.count { it.kind == 120 }, heart.takeIf { it.isNotEmpty() }?.average()?.toInt())
    }
    val nightStart = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val nightEnd = minOf(now, today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
    val night = activity.filter { it.at in nightStart until nightEnd }
    val sleep = night.filter { it.kind == 120 }
    val heart = (heartRates + activity.mapNotNull { minute -> minute.heartRate?.let { HeartReading(minute.at, it, "分钟历史记录") } })
        .filter { it.at <= now }.maxByOrNull { it.at }
    return PeripheralHealthSummary(steps?.takeIf { it.date == today.toString() }, heart,
        sleep.takeIf { it.isNotEmpty() }?.size, sleep.minOfOrNull { it.at }, sleep.maxOfOrNull { it.at }?.plus(60_000), historySyncedAt, days)
}

internal object BandHealthProtocol {
    fun steps(bytes: ByteArray): Int? = if (bytes.size == 13) (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) else null
    fun heart(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val bpm = if (bytes[0].toInt() and 1 == 0) bytes[1].toInt() and 255
            else if (bytes.size >= 3) (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) else return null
        return bpm.takeIf { it in 1..254 }
    }
    fun historyRequest(type: Int, since: Long, zone: ZoneId): ByteArray {
        val time = Instant.ofEpochMilli(since).atZone(zone)
        return byteArrayOf(1, type.toByte(), time.year.toByte(), (time.year shr 8).toByte(), time.monthValue.toByte(),
            time.dayOfMonth.toByte(), time.hour.toByte(), time.minute.toByte(), 0, (time.offset.totalSeconds / 900).toByte())
    }
    fun historyTime(bytes: ByteArray): Long {
        require(bytes.size >= 8)
        val year = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
        require(year in 2000..2100) { "设备时间异常" }
        return LocalDateTime.of(year, bytes[2].toInt() and 255, bytes[3].toInt() and 255, bytes[4].toInt() and 255,
            bytes[5].toInt() and 255, bytes[6].toInt() and 255).toInstant(ZoneOffset.ofTotalSeconds(bytes[7].toInt() * 900)).toEpochMilli()
    }
    fun activity(bytes: ByteArray, start: Long): List<ActivityMinute> {
        require(bytes.size % 8 == 0) { "活动记录不完整" }
        return bytes.indices.step(8).map { i -> ActivityMinute(start + i / 8 * 60_000L, bytes[i].toInt() and 255,
            bytes[i + 2].toInt() and 255, (bytes[i + 3].toInt() and 255).takeIf { it in 1..254 }) }
    }
    fun manualHeart(bytes: ByteArray): List<HeartReading> {
        require(bytes.size % 6 == 0) { "心率记录不完整" }
        return bytes.indices.step(6).mapNotNull { i ->
            val seconds = (0..3).fold(0L) { n, j -> n or ((bytes[i + j].toLong() and 255) shl (j * 8)) }
            (bytes[i + 5].toInt() and 255).takeIf { it in 1..254 }?.let { HeartReading(seconds * 1000, it, "手动测量历史") }
        }
    }
}
