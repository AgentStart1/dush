package com.storyteller_f.dush.agent.band

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PeripheralHealthStoreDeviceTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
    private suspend fun isolated(test: suspend (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir,"health-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir }
        try { test(context,dir) } finally { dir.deleteRecursively() }
    }
    @Test fun deduplicatesRepeatedSyncAndRestoresEncryptedHistory() = runBlocking { isolated { context, dir ->
        val store = PeripheralHealthStore(context) { now }
        store.mergeHistory("device",listOf(ActivityMinute(now-60_000,120,0,60)),emptyList())
        store.mergeHistory("device",listOf(ActivityMinute(now-60_000,120,1,62)),listOf(HeartReading(now,72,"PRIVATE-FIXTURE-MEASUREMENT")),now)
        val restored = PeripheralHealthStore(context) { now };restored.load()
        val snapshot = requireNotNull(restored.snapshots.value["device"])
        assertEquals(1,snapshot.activity.size)
        assertEquals(1,snapshot.activity.single().steps)
        assertEquals(72,snapshot.heartRates.single().bpm)
        assertEquals(now,snapshot.historySyncedAt)
        assertFalse(File(dir,"peripheral-health.enc").readBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE-FIXTURE-MEASUREMENT"))
    } }
    @Test fun dropsSamplesOutsideThe31DayRetentionWindow() = runBlocking { isolated { context, _ ->
        val store = PeripheralHealthStore(context) { now }
        store.mergeHistory("device",listOf(ActivityMinute(now-32L*86_400_000,120,0,60),ActivityMinute(now-60_000,120,0,62),ActivityMinute(now+86_400_000,120,0,64)),emptyList())
        assertEquals(1,store.snapshots.value.getValue("device").activity.size)
    } }
    @Test fun removingPreventsLateConnectionWritesFromRestoringDeletedData() = runBlocking { isolated { context, _ ->
        val store = PeripheralHealthStore(context) { now }
        store.recordHeart("device",HeartReading(now,72,"实时测量"));store.remove("device")
        store.recordHeart("device",HeartReading(now+1,73,"实时测量"))
        val restored = PeripheralHealthStore(context) { now };restored.load()
        assertTrue(restored.snapshots.value.isEmpty())
    } }
    @Test fun threadContextContainsOnlyBoundDataAndNoBluetoothAddress() = runBlocking { isolated { context, _ ->
        val store = PeripheralHealthStore(context) { now }
        store.recordSteps("mine",1234,now,ZoneId.of("Asia/Singapore"))
        store.recordSteps("other",9876,now,ZoneId.of("Asia/Singapore"))
        val devices = listOf(PeripheralDevice("mine","thread","02:00:00:00:00:01","我的手环"),PeripheralDevice("other","another","02:00:00:00:00:02","另一只"))
        val tool = PeripheralHealthTool(devices={devices},snapshots={store.snapshots.value},now={now},zone={ZoneId.of("Asia/Singapore")})
        val text = requireNotNull(tool.contextFor("thread"))
        val data = JSONArray(text.substringAfter('\n'))
        assertEquals(1,data.length())
        assertEquals(1234,data.getJSONObject(0).getInt("today_steps"))
        assertFalse(text.contains("9876"));assertFalse(text.contains("02:00:"));assertFalse(text.contains("auth_key"))
        assertNull(tool.contextFor("unbound"))
    } }
}
