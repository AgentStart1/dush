package com.storyteller_f.dush.agent.band

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface BandStatus {
    data object Connecting : BandStatus
    data object Ready : BandStatus
    data class Listening(val text: String) : BandStatus
    data class Thinking(val text: String) : BandStatus
    data class Failed(val message: String) : BandStatus
}
data class PeripheralInformation(
    val firmware: String? = null,
    val hardware: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
)
internal data class BandReply(val bindingId: String, val sessionId: String, val text: String, val final: Boolean)

class BandHub(context: Context) {
    val coordination = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + coordination)
    val store = PeripheralStore(context)
    val health = PeripheralHealthStore(context)
    val healthTool = PeripheralHealthTool(devices = { store.load(); store.devices.value }, snapshots = { health.load(); health.snapshots.value }, refresh = { devices ->
        coroutineScope {
            devices.filter { it.enabled && (statuses.value[it.id] == BandStatus.Ready || statuses.value[it.id] is BandStatus.Thinking) }.map { device -> async {
                withTimeoutOrNull(3_000) {
                    val done = CompletableDeferred<Unit>()
                    healthRequests.emit(PeripheralHealthRequest(device.id, PeripheralHealthAction.ReadSnapshot, done))
                    done.await()
                }
            } }.awaitAll()
        }
    })
    private val mutableHealthStatuses = MutableStateFlow<Map<String, HealthSyncStatus>>(emptyMap())
    val healthStatuses = mutableHealthStatuses.asStateFlow()
    internal val healthRequests = MutableSharedFlow<PeripheralHealthRequest>(extraBufferCapacity = 16)
    suspend fun syncHealth(id: String) { healthRequests.emit(PeripheralHealthRequest(id, PeripheralHealthAction.Sync)) }
    suspend fun measureHeart(id: String) { healthRequests.emit(PeripheralHealthRequest(id, PeripheralHealthAction.MeasureHeart)) }
    internal fun healthStatus(id: String, status: HealthSyncStatus) { mutableHealthStatuses.update { it + (id to status) } }
    val speech = BandSpeechModels(context, scope)
    private val _statuses = MutableStateFlow<Map<String, BandStatus>>(emptyMap())
    val statuses = _statuses.asStateFlow()
    private val _information = MutableStateFlow<Map<String, PeripheralInformation>>(emptyMap())
    val information = _information.asStateFlow()
    internal val refreshRequests = MutableSharedFlow<String>(extraBufferCapacity = 16)
    suspend fun refreshInformation(id: String) { refreshRequests.emit(id) }
    internal fun information(id: String, value: PeripheralInformation) { _information.update { it + (id to value) } }
    internal val replies = MutableSharedFlow<BandReply>(extraBufferCapacity = 16)
    internal fun status(id: String, value: BandStatus) { _statuses.update { it + (id to value) } }
    internal fun forget(id: String) {
        _statuses.update { it - id }
        if (store.devices.value.none { it.id == id }) {
            _information.update { it - id }; mutableHealthStatuses.update { it - id }
        }
    }
    suspend fun publishReply(bindingId: String, sessionId: String, text: String, final: Boolean = true) {
        replies.emit(BandReply(bindingId, sessionId, text, final))
    }
    fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, BandVoiceService::class.java)) }
}
