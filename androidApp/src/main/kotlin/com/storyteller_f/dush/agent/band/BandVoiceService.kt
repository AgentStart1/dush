package com.storyteller_f.dush.agent.band

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.storyteller_f.dush.MainActivity
import com.storyteller_f.dush.agent.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import co.touchlab.kermit.Logger

/** Owns independent connections; thread association changes cancel the affected voice session. */
class BandVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var owner: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); AppGraph.initialize(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("band-voice", "手环语音", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(4107, Notification.Builder(this, "band-voice")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("外设连接已启用")
            .setContentText("在外设页面管理连接与对话绑定").setContentIntent(open).setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (owner?.isActive == true) return START_STICKY
        owner = scope.launch {
            val hub = AppGraph.bands
            try {
                hub.store.load(); hub.speech.refresh()
                coroutineScope {
                    val connections = mutableMapOf<String, Pair<PeripheralDevice, Job>>()
                    hub.store.devices.collect { devices ->
                        val enabled = devices.filter { it.enabled }.associateBy { it.id }
                        val obsolete = connections.filter { (id, running) -> enabled[id]?.sameConnection(running.first) != true }.keys.toList()
                        for (id in obsolete) connections.remove(id)?.second?.cancelAndJoin()
                        if (enabled.isEmpty()) { stopSelf(); return@collect }
                        for ((id, device) in enabled) if (id !in connections) {
                            connections[id] = device to launch {
                                try {
                                    while (isActive && hub.store.isCurrent(device)) {
                                        try {
                                            hub.status(id, BandStatus.Connecting)
                                            BandConnection(applicationContext, hub, device).run()
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            Logger.w("Peripheral connection failed: ${failure::class.simpleName}")
                                            hub.status(id, BandStatus.Failed("连接中断，正在重连；请确认外设在附近且其他 App 已停止连接"))
                                            delay(5_000)
                                        }
                                    }
                                } finally { hub.forget(id) }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Logger.e("Band service failed: ${failure::class.simpleName}"); stopSelf() }
        }
        return START_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
