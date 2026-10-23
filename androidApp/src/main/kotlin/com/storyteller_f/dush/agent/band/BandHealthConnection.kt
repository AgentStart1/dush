package com.storyteller_f.dush.agent.band

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import android.os.SystemClock
import java.time.ZoneId
import java.util.UUID

internal enum class PeripheralHealthAction { Sync, MeasureHeart, ReadSnapshot }
internal data class PeripheralHealthRequest(val deviceId: String, val action: PeripheralHealthAction, val completion: CompletableDeferred<Unit>? = null)

/** Shares the BLE connection owner's serial event loop. Fetches never acknowledge/delete band history. */
internal class BandHealthConnection(private val gatt: BandGatt, private val hub: BandHub, private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis, private val monotonic: () -> Long = SystemClock::elapsedRealtime, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private sealed interface State {
        data object Idle : State
        data class Fetching(val transfer: BandHistoryTransfer, val round: Int, var progressAt: Long) : State
        data class Measuring(val startedAt: Long) : State
    }
    private var state: State = State.Idle
    private var historySupported = false
    private var heartSupported = false
    private var initialSince = 0L
    private suspend fun guarded(action: suspend () -> Unit) {
        try {
            if (hub.store.devices.value.none { it.id == deviceId && it.enabled }) return
            action()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            state = State.Idle
            hub.healthStatus(deviceId, HealthSyncStatus.Failed("健康数据读取失败，请稍后重试；已同步记录仍保留"))
        }
    }
    suspend fun initialize() = guarded {
        hub.health.load()
        historySupported = gatt.subscribe(CONTROL, false) && gatt.subscribe(DATA, false)
        heartSupported = gatt.subscribe(HEART, false)
        gatt.subscribe(STEPS, false)
        readSteps()
        sync()
    }
    suspend fun readSteps() {
        gatt.read(STEPS)?.let { bytes -> BandHealthProtocol.steps(bytes)?.let { hub.health.recordSteps(deviceId, it, now(), zone()) } }
    }
    suspend fun request(action: PeripheralHealthAction) = guarded {
        if (action == PeripheralHealthAction.ReadSnapshot) { readSteps(); return@guarded }
        if (state != State.Idle) return@guarded
        when (action) {
            PeripheralHealthAction.ReadSnapshot -> Unit
            PeripheralHealthAction.Sync -> { readSteps(); sync() }
            PeripheralHealthAction.MeasureHeart -> {
                check(heartSupported) { "Heart rate not supported" }
                gatt.write(HEART_CONTROL, byteArrayOf(0x15, 2, 0))
                gatt.write(HEART_CONTROL, byteArrayOf(0x15, 2, 1))
                state = State.Measuring(monotonic())
                hub.healthStatus(deviceId, HealthSyncStatus.Syncing("正在测量心率，请佩戴好手环并保持静止"))
            }
        }
    }
    private suspend fun sync() {
        if (!historySupported) { hub.healthStatus(deviceId, HealthSyncStatus.Failed("已读取可用实时数据；此外设未提供历史同步通道")); return }
        initialSince = now() - 7L * 86_400_000
        start(1, initialSince, 1)
    }
    private suspend fun start(type: Int, since: Long, round: Int) {
        val transfer = BandHistoryTransfer(type, since, zone())
        state = State.Fetching(transfer, round, monotonic())
        hub.healthStatus(deviceId, HealthSyncStatus.Syncing(if (type == 1) "正在同步步数、心率和睡眠记录…" else "正在同步手动心率记录…"))
        gatt.write(CONTROL, transfer.request())
    }
    suspend fun handle(event: BandGatt.Event.Changed): Boolean {
        if (event.uuid !in listOf(STEPS, HEART, CONTROL, DATA)) return false
        guarded {
            when (event.uuid) {
                STEPS -> BandHealthProtocol.steps(event.value)?.let { hub.health.recordSteps(deviceId, it, now(), zone()) }
                HEART -> BandHealthProtocol.heart(event.value)?.let {
                    hub.health.recordHeart(deviceId, HeartReading(now(), it, "实时测量"))
                    if (state is State.Measuring) {
                        gatt.write(HEART_CONTROL, byteArrayOf(0x15, 2, 0)); state = State.Idle
                        hub.healthStatus(deviceId, HealthSyncStatus.Complete(now()))
                    }
                }
                DATA -> (state as? State.Fetching)?.let { it.transfer.data(event.value); it.progressAt = monotonic() }
                CONTROL -> {
                    val fetching = state as? State.Fetching ?: return@guarded
                    fetching.progressAt = monotonic()
                    when (val update = fetching.transfer.metadata(event.value)) {
                        BandHistoryTransfer.Update.AwaitData -> gatt.write(CONTROL, byteArrayOf(2))
                        is BandHistoryTransfer.Update.Complete -> {
                            hub.health.mergeHistory(deviceId, update.activity, update.heart)
                            val next = update.next
                            when {
                                next != null && next < now() - 60_000 && fetching.round < 10 -> start(fetching.transfer.type, next, fetching.round + 1)
                                fetching.transfer.type == 1 -> start(2, initialSince, 1)
                                else -> {
                                    hub.health.mergeHistory(deviceId, emptyList(), emptyList(), now())
                                    state = State.Idle; hub.healthStatus(deviceId, HealthSyncStatus.Complete(now()))
                                }
                            }
                        }
                        is BandHistoryTransfer.Update.Failed -> {
                            state = State.Idle; hub.healthStatus(deviceId, HealthSyncStatus.Failed(update.message + "，已有记录仍保留"))
                        }
                        null -> Unit
                    }
                }
            }
        }
        return true
    }
    suspend fun tick() = guarded {
        when (val current = state) {
            is State.Fetching -> if (monotonic() - current.progressAt > 45_000) {
                state = State.Idle; hub.healthStatus(deviceId, HealthSyncStatus.Failed("同步超时，请重试；手环原始记录未删除"))
            }
            is State.Measuring -> if (monotonic() - current.startedAt > 45_000) {
                gatt.write(HEART_CONTROL, byteArrayOf(0x15, 2, 0)); state = State.Idle
                hub.healthStatus(deviceId, HealthSyncStatus.Failed("未取得心率，请佩戴好手环后重试"))
            }
            State.Idle -> Unit
        }
    }
    suspend fun close() {
        if (state is State.Measuring) gatt.write(HEART_CONTROL, byteArrayOf(0x15, 2, 0))
        if (state != State.Idle) hub.healthStatus(deviceId, HealthSyncStatus.Failed("连接已断开，本次读取未完成；已有记录仍保留"))
        state = State.Idle
    }
    companion object {
        private fun huami(n: Int) = UUID.fromString("%08x-0000-3512-2118-0009af100700".format(n))
        val CONTROL = huami(4)
        val DATA = huami(5)
        val STEPS = huami(7)
        val HEART = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val HEART_CONTROL = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")
    }
}
