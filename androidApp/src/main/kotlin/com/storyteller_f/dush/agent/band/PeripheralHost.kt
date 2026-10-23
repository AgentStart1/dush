package com.storyteller_f.dush.agent.band

import com.storyteller_f.dush.agent.repository.ChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.ZoneId

data class PeripheralThread(val id: String, val title: String)
data class PeripheralState(
    val devices: List<PeripheralDevice> = emptyList(),
    val statuses: Map<String, BandStatus> = emptyMap(),
    val information: Map<String, PeripheralInformation> = emptyMap(),
    val threads: List<PeripheralThread> = emptyList(),
    val model: SpeechModelState = SpeechModelState.Missing,
    val health: Map<String, PeripheralHealthSummary> = emptyMap(),
    val healthStatuses: Map<String, HealthSyncStatus> = emptyMap(),
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
)
sealed interface PeripheralEffect {
    data object StartService : PeripheralEffect
    data class Added(val id: String) : PeripheralEffect
    data object Removed : PeripheralEffect
    data class FillConfiguration(val address: String, val key: String) : PeripheralEffect
}

/** Screen lifetime owner; Bluetooth permission launchers and navigation remain in the UI. */
class PeripheralHost(private val hub: BandHub, private val chats: ChatRepository, dispatcher: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(PeripheralState())
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<PeripheralEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    init {
        scope.launch {
            try { hub.store.load(); hub.speech.refresh(); hub.health.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = "无法读取外设配置，请重试") } }
            finally { mutableState.update { it.copy(loading = false) } }
        }
        scope.launch {
            combine(hub.store.devices, hub.statuses, hub.information, chats.observeThreads(), hub.speech.state) { devices, statuses, information, threads, model ->
                PeripheralState(devices, statuses, information, threads.map { PeripheralThread(it.id, it.title) }, model)
            }.combine(hub.health.snapshots) { data, snapshots ->
                data.copy(health = snapshots.mapValues { it.value.summarize(now(), zone()) })
            }.combine(hub.healthStatuses) { data, statuses -> data.copy(healthStatuses = statuses) }.collect { data -> mutableState.update { data.copy(loading = it.loading, working = it.working, error = it.error) } }
        }
    }
    private fun perform(action: suspend () -> Unit) = scope.launch {
        if (mutableState.value.working) return@launch
        mutableState.update { it.copy(working = true, error = null) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalArgumentException) { mutableState.update { it.copy(error = failure.message) } }
        catch (failure: Exception) { mutableState.update { it.copy(error = "操作失败，请重试") } }
        finally { mutableState.update { it.copy(working = false) } }
    }
    fun add(name: String, address: String, key: String, enabled: Boolean) = perform {
        val id = hub.store.add(name, address, key, enabled)
        if (enabled) effectChannel.send(PeripheralEffect.StartService)
        effectChannel.send(PeripheralEffect.Added(id))
    }
    fun connect(id: String) = perform {
        hub.store.setEnabled(id, true)
        effectChannel.send(PeripheralEffect.StartService)
    }
    fun disconnect(id: String) = perform { hub.store.setEnabled(id, false) }
    fun refresh(id: String) = perform { hub.refreshInformation(id) }
    fun syncHealth(id: String) = perform { hub.syncHealth(id) }
    fun measureHeart(id: String) = perform { hub.measureHeart(id) }
    fun rename(id: String, name: String) = perform { hub.store.rename(id, name) }
    fun bind(id: String, threadId: String) = perform {
        requireNotNull(chats.getThread(threadId)) { "对话不存在" }
        hub.store.bindToThread(id, threadId)
        effectChannel.send(PeripheralEffect.StartService)
    }
    fun unbind(id: String, expectedThreadId: String? = null) = perform { hub.store.unbindFromThread(id, expectedThreadId) }
    fun remove(id: String) = perform { hub.store.remove(id); hub.health.remove(id); hub.forget(id); effectChannel.send(PeripheralEffect.Removed) }
    fun download() = scope.launch { hub.speech.download() }
    fun importConfiguration(read: suspend () -> String) = perform {
        val values = withContext(Dispatchers.IO) {
            val raw = read(); require(raw.toByteArray().size <= 4096) { "配置文件过大" }
            val json = JSONObject(raw); json.getString("address") to json.getString("auth_key")
        }
        effectChannel.send(PeripheralEffect.FillConfiguration(values.first, values.second))
    }
    fun startFailed() = scope.launch { mutableState.update { it.copy(error = "无法连接，请检查蓝牙权限和蓝牙开关") } }
    fun close() { scope.cancel(); effectChannel.close() }
}
