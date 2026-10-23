package com.storyteller_f.dush.agent.band

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Encrypted local history, deduplicated by minute/timestamp and retained for 31 days. */
class PeripheralHealthStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val file = EncryptedJsonFile(context, "peripheral-health.enc", "dush.peripheral.health")
    private val mutex = Mutex()
    private var loaded = false
    private val retiredIds = mutableSetOf<String>()
    private val mutableSnapshots = MutableStateFlow<Map<String, PeripheralHealthSnapshot>>(emptyMap())
    val snapshots = mutableSnapshots.asStateFlow()
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }
    private fun loadLocked() {
        if (loaded) return
        val json = file.read()?.let(::JSONObject)
        if (json != null) mutableSnapshots.value = json.keys().asSequence().associateWith { id ->
            val record = json.getJSONObject(id)
            val steps = record.optJSONObject("steps")?.let { StepReading(it.getLong("at"), it.getString("date"), it.getInt("value")) }
            val heart = record.optJSONArray("heart") ?: JSONArray()
            val activity = record.optJSONArray("activity") ?: JSONArray()
            retain(PeripheralHealthSnapshot(steps,
                (0 until heart.length()).map { heart.getJSONArray(it).let { a -> HeartReading(a.getLong(0), a.getInt(1), a.getString(2)) } },
                (0 until activity.length()).map { activity.getJSONArray(it).let { a -> ActivityMinute(a.getLong(0), a.getInt(1), a.getInt(2), a.optInt(3).takeIf { bpm -> bpm in 1..254 }) } },
                record.optLong("synced").takeIf { it > 0 }))
        }
        loaded = true
    }
    private fun retain(snapshot: PeripheralHealthSnapshot): PeripheralHealthSnapshot {
        val cutoff = now() - 31L * 86_400_000
        return snapshot.copy(heartRates = snapshot.heartRates.filter { it.at in cutoff..(now() + 300_000) }, activity = snapshot.activity.filter { it.at in cutoff..(now() + 300_000) })
    }
    private fun persist(next: Map<String, PeripheralHealthSnapshot>) {
        val json = JSONObject()
        for ((id, snapshot) in next) {
            val record = JSONObject()
            snapshot.steps?.let { record.put("steps", JSONObject().put("at", it.at).put("date", it.date).put("value", it.steps)) }
            record.put("heart", JSONArray().apply { snapshot.heartRates.forEach { put(JSONArray().put(it.at).put(it.bpm).put(it.source)) } })
            record.put("activity", JSONArray().apply { snapshot.activity.forEach { put(JSONArray().put(it.at).put(it.kind).put(it.steps).put(it.heartRate ?: 0)) } })
            snapshot.historySyncedAt?.let { record.put("synced", it) }
            json.put(id, record)
        }
        file.write(json.toString()); mutableSnapshots.value = next
    }
    private suspend fun update(id: String, change: (PeripheralHealthSnapshot) -> PeripheralHealthSnapshot) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        if (id in retiredIds) return@withLock
        val updated = retain(change(mutableSnapshots.value[id] ?: PeripheralHealthSnapshot()))
        persist(mutableSnapshots.value + (id to updated))
    } }
    suspend fun recordSteps(id: String, steps: Int, at: Long, zone: ZoneId) = update(id) {
        it.copy(steps = StepReading(at, Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString(), steps))
    }
    suspend fun recordHeart(id: String, heart: HeartReading) = update(id) {
        it.copy(heartRates = (it.heartRates + heart).associateBy { sample -> sample.at }.values.sortedBy { sample -> sample.at })
    }
    suspend fun mergeHistory(id: String, activity: List<ActivityMinute>, heart: List<HeartReading>, syncedAt: Long? = null) = update(id) {
        it.copy(activity = (it.activity + activity).associateBy { sample -> sample.at }.values.sortedBy { sample -> sample.at },
            heartRates = (it.heartRates + heart).associateBy { sample -> sample.at }.values.sortedBy { sample -> sample.at },
            historySyncedAt = syncedAt ?: it.historySyncedAt)
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { mutex.withLock { loadLocked(); retiredIds += id; persist(mutableSnapshots.value - id) } }
}
