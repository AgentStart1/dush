package com.storyteller_f.dush.agent.band

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class PeripheralStoreDeviceTest {
    private val secret = "00112233445566778899aabbccddeeff"
    private val address = "02:00:00:00:00:01"
    private suspend fun isolated(test: suspend (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "peripheral-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir }
        try { test(context, dir) } finally { dir.deleteRecursively() }
    }
    @Test fun savesAnEncryptedPeripheralWithoutRequiringAThread() = runBlocking { isolated { context, dir ->
        val id = PeripheralStore(context).add("测试外设", address, secret)
        val restored = PeripheralStore(context).apply { load() }
        assertEquals(id, restored.devices.value.single().id)
        assertNull(restored.devices.value.single().threadId)
        assertFalse(File(dir, "band-connections.enc").readBytes().toString(Charsets.ISO_8859_1).contains(secret))
    } }
    @Test fun unbindingKeepsDeviceAndCredentialsForAnotherThread() = runBlocking { isolated { context, _ ->
        val store = PeripheralStore(context)
        val id = store.add("测试外设", address, secret)
        store.bindToThread(id, "first-thread")
        val key = requireNotNull(store.credentials(id)).key
        try { store.bindToThread(id, "other-thread"); fail("Must reject a conflicting owner") } catch (expected: IllegalArgumentException) { }
        store.unbindFromThread(id)
        val restored = PeripheralStore(context).apply { load() }
        assertNull(restored.devices.value.single().threadId)
        assertArrayEquals(key, requireNotNull(restored.credentials(id)).key)
        restored.bindToThread(id, "other-thread")
        assertEquals("other-thread", restored.devices.value.single().threadId)
        assertEquals(id, restored.devices.value.single().id)
    } }
    @Test fun readsLegacyBindingWithoutChangingItsIdKeyOrThread() = runBlocking { isolated { context, dir ->
        // Seed the shared Keystore key, then write the pre-registry encrypted schema.
        PeripheralStore(context).add("seed", address, secret)
        val legacy = JSONArray().put(JSONObject().put("id", "legacy-id").put("thread", "legacy-thread").put("address", address).put("key", secret))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("dush.band.bindings", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        File(dir, "band-connections.enc").writeBytes(cipher.iv + cipher.doFinal(legacy.toString().toByteArray()))
        val store = PeripheralStore(context).apply { load() }
        val device = store.devices.value.single()
        assertEquals("legacy-id", device.id)
        assertEquals("legacy-thread", device.threadId)
        assertTrue(device.enabled)
        assertEquals("小米手环 6 NFC", device.name)
        assertArrayEquals(secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), requireNotNull(store.credentials(device.id)).key)
        store.rename(device.id, "新名称")
        val reopened = PeripheralStore(context).apply { load() }
        assertEquals("legacy-thread", reopened.devices.value.single().threadId)
        assertEquals("新名称", reopened.devices.value.single().name)
    } }
    @Test fun rejectsDuplicateAddressWithoutReplacingCredentials() = runBlocking { isolated { context, _ ->
        val store = PeripheralStore(context)
        val id = store.add("原外设", address, secret)
        try { store.add("重复", address, "ffeeddccbbaa99887766554433221100"); fail("Must reject duplicate address") } catch (expected: IllegalArgumentException) { }
        assertEquals(1, store.devices.value.size)
        assertEquals(id, store.devices.value.single().id)
        assertEquals("原外设", store.devices.value.single().name)
    } }
    @Test fun disconnectingRetainsRegistrationAndRemovingDeletesIt() = runBlocking { isolated { context, _ ->
        val store = PeripheralStore(context)
        val id = store.add("测试外设", address, secret)
        store.setEnabled(id, false)
        val restored = PeripheralStore(context).apply { load() }
        assertFalse(restored.devices.value.single().enabled)
        assertNotNull(restored.credentials(id))
        restored.remove(id)
        val reopened = PeripheralStore(context).apply { load() }
        assertTrue(reopened.devices.value.isEmpty())
        assertNull(reopened.credentials(id))
    } }
}
