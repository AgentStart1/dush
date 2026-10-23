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
import java.util.UUID

data class PeripheralDevice(
    val id: String,
    val threadId: String?,
    val address: String,
    val name: String = "小米手环 6 NFC",
    val enabled: Boolean = true,
) {
    internal fun sameConnection(other: PeripheralDevice): Boolean = id == other.id && threadId == other.threadId && address == other.address && enabled == other.enabled
}
internal data class BandCredentials(val binding: PeripheralDevice, val key: ByteArray)

/** Owns the peripheral registry and optional thread associations. Stored outside Android backup; keys never enter UI state. */
class PeripheralStore(context: Context) {
    private val file = EncryptedJsonFile(context, "band-connections.enc", "dush.band.bindings")
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<PeripheralDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private var loaded = false
    private var records = JSONArray()
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }
    private fun loadLocked() {
        if (loaded) return
        file.read()?.let { records = JSONArray(it) }
        loaded = true; publish()
    }
    private fun publish() {
        _devices.value = (0 until records.length()).map { records.getJSONObject(it).binding() }
    }
    private fun JSONObject.binding() = PeripheralDevice(getString("id"), optString("thread").takeIf { it.isNotBlank() }, getString("address"), optString("name", "小米手环 6 NFC"), optBoolean("enabled", true))
    private fun persist(next: JSONArray) {
        file.write(next.toString())
        records = next; publish()
    }
    suspend fun add(name: String, address: String, secret: String, enabled: Boolean = true): String = withContext(Dispatchers.IO) {
        val normalized = address.trim().uppercase()
        require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(normalized)) { "请输入有效的外设蓝牙地址" }
        val hex = secret.trim().removePrefix("0x")
        require(Regex("[0-9a-fA-F]{32}").matches(hex)) { "配对密钥应为 32 位十六进制字符" }
        mutex.withLock {
            loadLocked()
            require((0 until records.length()).none { records.getJSONObject(it).getString("address") == normalized }) { "此外设已添加，请在外设详情中管理" }
            val id = UUID.randomUUID().toString()
            val next = JSONArray(records.toString())
            next.put(JSONObject().put("id", id).put("thread", "").put("address", normalized).put("key", hex)
                .put("name", name.trim().ifBlank { "小米手环 6 NFC" }).put("enabled", enabled))
            persist(next)
            id
        }
    }
    private suspend fun edit(id: String, change: (JSONObject) -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val next = JSONArray(records.toString())
        val record = (0 until next.length()).map { next.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        requireNotNull(record) { "外设已被移除" }
        change(record); persist(next)
    } }
    suspend fun bindToThread(id: String, threadId: String) = edit(id) {
        require(threadId.isNotBlank())
        require(it.optString("thread").isBlank() || it.optString("thread") == threadId) { "外设已绑定其他对话，请先解除绑定" }
        it.put("thread", threadId).put("enabled", true)
    }
    suspend fun unbindFromThread(id: String, expectedThreadId: String? = null) = edit(id) {
        require(expectedThreadId == null || it.optString("thread") == expectedThreadId) { "外设绑定已变化，请刷新后重试" }
        it.put("thread", "")
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = edit(id) { it.put("enabled", enabled) }
    suspend fun rename(id: String, name: String) = edit(id) {
        require(name.trim().isNotBlank()) { "请输入外设名称" }
        it.put("name", name.trim())
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked(); val next = JSONArray()
        (0 until records.length()).map { records.getJSONObject(it) }.filter { it.getString("id") != id }.forEach(next::put)
        persist(next)
    } }
    internal suspend fun credentials(id: String): BandCredentials? = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        (0 until records.length()).map { records.getJSONObject(it) }.firstOrNull { it.getString("id") == id }?.let {
            BandCredentials(it.binding(), it.getString("key").chunked(2).map { part -> part.toInt(16).toByte() }.toByteArray())
        }
    } }
    fun isCurrent(device: PeripheralDevice): Boolean = devices.value.any { it.sameConnection(device) && it.enabled }
}
