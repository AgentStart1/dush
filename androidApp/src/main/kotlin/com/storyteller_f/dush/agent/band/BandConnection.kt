package com.storyteller_f.dush.agent.band

import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.worker.ReplyWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.UUID
import javax.crypto.Cipher

@OptIn(ExperimentalCoroutinesApi::class)
internal class BandConnection(private val context: Context, private val hub: BandHub, private val binding: PeripheralDevice) {
    private val packets = BandPackets()
    private val gatt = BandGatt(context)
    private val health = BandHealthConnection(gatt, hub, binding.id)
    private sealed interface Phase {
        data object Idle : Phase
        data class Listening(val id: String, val speech: LocalBandSpeech, val frames: BandOpusFrames, val started: Long,
                             val wakeLock: PowerManager.WakeLock, var previousText: String = "", var lastDisplay: Long = 0) : Phase {
            fun close() { try { speech.close() } finally { if (wakeLock.isHeld) wakeLock.release() } }
        }
        data class Waiting(val id: String, val stream: BandReplyStream = BandReplyStream()) : Phase
    }
    private var phase: Phase = Phase.Idle
    private var voiceEndpoint = 0x10
    suspend fun run(): Unit = coroutineScope {
        val secret = requireNotNull(hub.store.credentials(binding.id)).key
        val healthEvents = Channel<PeripheralHealthRequest>(16)
        val healthRequests = launch { hub.healthRequests.filter { it.deviceId == binding.id }.collect { healthEvents.send(it) } }
        val replyEvents = Channel<BandReply>(16)
        val refreshEvents = Channel<Unit>(Channel.CONFLATED)
        val refreshes = launch { hub.refreshRequests.filter { it == binding.id }.collect { refreshEvents.trySend(Unit) } }
        val replies = launch { hub.replies.filter { it.bindingId == binding.id }.collect { replyEvents.send(it) } }
        try {
            gatt.connect(binding.address)
            val privateKey = BandCurve.privateKey()
            send(0x82, byteArrayOf(4, 2, 0, 2) + BandCurve.publicKey(privateKey), false)
            withTimeout(20_000) {
                var authenticated = false
                while (!authenticated) {
                    val payload = incomingPayload() ?: continue
                    if (payload.endpoint != 0x82) continue
                    val data = payload.data
                    require(data.size >= 3) { "Invalid authentication response" }
                    when {
                        data[0] == 16.toByte() && data[1] == 4.toByte() && data[2] == 1.toByte() -> {
                            require(data.size == 67)
                            val shared = BandCurve.shared(privateKey, data.copyOfRange(19, 67))
                            val session = packets.authenticate(shared, secret)
                            val random = data.copyOfRange(3, 19)
                            send(0x82, byteArrayOf(5) + aes(random, secret, Cipher.ENCRYPT_MODE) + aes(random, session, Cipher.ENCRYPT_MODE), false)
                        }
                        data[0] == 16.toByte() && data[1] == 5.toByte() -> {
                            check(data[2] == 1.toByte()) { "手环认证失败，请检查配对密钥" }; authenticated = true
                        }
                        else -> error("手环认证不支持")
                    }
                }
            }
            secret.fill(0)
            refreshInformation()
            health.initialize()
            hub.status(binding.id, BandStatus.Ready)
            while (isActive) {
                select<Unit> {
                    gatt.incoming.onReceive { event -> payload(event)?.let { handle(it) } }
                    refreshEvents.onReceive { refreshInformation(); health.request(PeripheralHealthAction.ReadSnapshot) }
                    healthEvents.onReceive { request -> try { health.request(request.action) } finally { request.completion?.complete(Unit) } }
                    replyEvents.onReceive { reply ->
                        val waiting = phase as? Phase.Waiting
                        if (waiting?.id == reply.sessionId && hub.store.isCurrent(binding)) {
                            waiting.stream.offer(reply.text, reply.final, SystemClock.elapsedRealtime())
                            displayReply(waiting)
                        }
                    }
                    onTimeout(250) {
                        val current = phase as? Phase.Listening
                        if (current != null && SystemClock.elapsedRealtime() - current.started >= 20_000) finish(current, true)
                        (phase as? Phase.Waiting)?.let { displayReply(it) }
                        health.tick()
                    }
                }
            }
        } finally {
            healthRequests.cancel(); healthEvents.close()
            withContext(NonCancellable) { try { withTimeout(1_000) { health.close() } } catch (_: Exception) { } }
            secret.fill(0); replies.cancel(); replyEvents.close(); refreshes.cancel(); refreshEvents.close()
            (phase as? Phase.Listening)?.close(); phase = Phase.Idle
            gatt.close()
        }
    }
    private suspend fun refreshInformation() {
        fun standard(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        suspend fun text(id: String) = gatt.read(standard(id))?.toString(Charsets.UTF_8)?.trim('\u0000', ' ')?.takeIf { it.isNotBlank() }
        val battery = gatt.read(UUID.fromString("00000006-0000-3512-2118-0009af100700"))
        val standardBattery = if (battery == null) gatt.read(standard("2a19")) else null
        hub.information(binding.id, PeripheralInformation(
            firmware = text("2a26") ?: text("2a28"), hardware = text("2a27"), model = text("2a24"), manufacturer = text("2a29"),
            batteryPercent = (battery?.getOrNull(1)?.toInt()?.and(255) ?: standardBattery?.firstOrNull()?.toInt()?.and(255))?.takeIf { it in 0..100 },
            charging = battery?.getOrNull(2)?.let { it.toInt() == 1 },
        ))
    }
    private suspend fun displayReply(waiting: Phase.Waiting) {
        if (!hub.store.isCurrent(binding)) return
        waiting.stream.next(SystemClock.elapsedRealtime())?.let { sendVoice(bandText(9, it)) }
        if (waiting.stream.finished) {
            phase = Phase.Idle
            hub.status(binding.id, BandStatus.Ready)
        }
    }
    private suspend fun send(endpoint: Int, bytes: ByteArray, encrypted: Boolean) {
        for (packet in packets.encode(endpoint, bytes, encrypted)) gatt.write(BandGatt.WRITE, packet)
    }
    private suspend fun sendVoice(bytes: ByteArray) = send(voiceEndpoint, bytes, true)
    private suspend fun incomingPayload(): BandPackets.Payload? = payload(gatt.incoming.receive())
    private suspend fun payload(event: BandGatt.Event): BandPackets.Payload? = when (event) {
        is BandGatt.Event.Disconnected -> error("Bluetooth disconnected")
        is BandGatt.Event.Changed -> if (health.handle(event)) null else if (event.uuid == BandGatt.READ && event.value.firstOrNull() == 3.toByte()) {
            val result = packets.decode(event.value)
            result.ack?.let { gatt.write(BandGatt.READ, it) }
            result.payload
        } else null
    }
    private suspend fun handle(payload: BandPackets.Payload) {
        if (payload.endpoint !in listOf(0x10, 0x11)) return
        val data = payload.data
        when (data[0].toInt() and 255) {
            1 -> {
                (phase as? Phase.Listening)?.close(); phase = Phase.Idle
                require(data.size >= 4 && data[1].toInt() in 1..3)
                voiceEndpoint = payload.endpoint
                if (binding.threadId == null || hub.speech.state.value != SpeechModelState.Ready) {
                    sendVoice(byteArrayOf(3, 1) + "外设已连接".toByteArray() + byteArrayOf(0))
                    sendVoice(byteArrayOf(6))
                    sendVoice(bandText(9, if (binding.threadId == null) "请先在 dush 对话设置中绑定此外设" else "请先在外设详情中下载语音识别模型"))
                    return
                }
                val speech = LocalBandSpeech(hub.speech.get(), data[3].toInt() and 255)
                val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dush:band-utterance")
                val listening = Phase.Listening(UUID.randomUUID().toString(), speech, BandOpusFrames(), SystemClock.elapsedRealtime(), wakeLock)
                phase = listening
                wakeLock.acquire(25_000)
                // This firmware does not start microphone streaming with an empty suggestions list.
                val suggestions = listOf("开始说话", "本地识别", "对话助手")
                sendVoice(byteArrayOf(3, suggestions.size.toByte()) + suggestions.flatMap { (it.toByteArray() + byteArrayOf(0)).toList() }.toByteArray())
                hub.status(binding.id, BandStatus.Listening(""))
            }
            5 -> {
                val current = phase as? Phase.Listening ?: return
                require(data.size >= 5)
                for (frame in current.frames.accept(data.copyOfRange(5, data.size))) {
                    val update = current.speech.accept(frame)
                    val now = SystemClock.elapsedRealtime()
                    if (update.text != current.previousText && update.text.isNotBlank() && now - current.lastDisplay >= 350) {
                        sendVoice(bandText(7, update.text, false)); current.previousText = update.text; current.lastDisplay = now
                        hub.status(binding.id, BandStatus.Listening(update.text))
                    }
                    if (update.endpoint && update.text.isNotBlank()) { finish(current, true); break }
                }
            }
            2 -> (phase as? Phase.Listening)?.let { finish(it, false) }
        }
    }
    private suspend fun finish(current: Phase.Listening, stop: Boolean) {
        if (phase !== current) return
        phase = Phase.Idle
        val text = try { current.speech.finish() } finally { current.close() }
        if (stop) sendVoice(byteArrayOf(6))
        if (!hub.store.isCurrent(binding)) return
        if (text.isBlank()) {
            sendVoice(bandText(9, "没有听清，请再说一次")); hub.status(binding.id, BandStatus.Ready); return
        }
        sendVoice(bandText(7, text, true))
        val threadId = binding.threadId ?: return
        val thread = requireNotNull(AppGraph.chatRepository.getThread(threadId)) { "绑定的对话不存在" }
        val agent = thread.agentId?.let { AppGraph.agentRepository.getAgent(it) } ?: AppGraph.agentRepository.defaultAgent()
        if (!hub.store.isCurrent(binding)) return
        val message = AppGraph.chatRepository.appendUserMessage(threadId, text)
        phase = Phase.Waiting(current.id)
        hub.status(binding.id, BandStatus.Thinking(text))
        ReplyWorker.enqueue(context, threadId, agent.id, message.id, binding.id, current.id)
    }
}
