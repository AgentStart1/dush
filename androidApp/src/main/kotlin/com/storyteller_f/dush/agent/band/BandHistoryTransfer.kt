package com.storyteller_f.dush.agent.band

import java.io.ByteArrayOutputStream
import java.time.ZoneId

/** A single bounded, counter-checked history transfer. Never emits command 03 (delete/acknowledge). */
internal class BandHistoryTransfer(val type: Int, val since: Long, private val zone: ZoneId) {
    sealed interface Update {
        data object AwaitData : Update
        data class Complete(val activity: List<ActivityMinute>, val heart: List<HeartReading>, val next: Long?) : Update
        data class Failed(val message: String) : Update
    }
    private sealed interface State {
        data object Starting : State
        data class Receiving(val start: Long, val buffer: ByteArrayOutputStream = ByteArrayOutputStream(), var counter: Int = 0) : State
        data object Finished : State
    }
    private var state: State = State.Starting
    fun request() = BandHealthProtocol.historyRequest(type, since, zone)
    fun data(bytes: ByteArray) {
        val receiving = state as? State.Receiving ?: return
        require(bytes.isNotEmpty() && bytes[0].toInt() and 255 == receiving.counter) { "历史记录数据包顺序异常" }
        receiving.counter = (receiving.counter + 1) and 255
        require(receiving.buffer.size() + bytes.size - 1 <= 1_048_576) { "历史记录超出单次同步限制" }
        receiving.buffer.write(bytes, 1, bytes.size - 1)
    }
    fun metadata(bytes: ByteArray): Update? {
        if (bytes.size < 3 || bytes[0] != 16.toByte()) return null
        val command = bytes[1].toInt() and 255
        if (command !in 1..2) return null
        if (bytes[2] != 1.toByte()) { state = State.Finished; return Update.Failed("手环暂不支持此类记录或本次读取失败") }
        if (command == 1 && state == State.Starting) {
            require(bytes.size >= 15) { "历史记录头不完整" }
            val count = (0..3).fold(0L) { n, i -> n or ((bytes[3 + i].toLong() and 255) shl (i * 8)) }
            if (count == 0L) { state = State.Finished; return Update.Complete(emptyList(), emptyList(), null) }
            require(count <= 1_048_576) { "历史记录超出单次同步限制" }
            state = State.Receiving(BandHealthProtocol.historyTime(bytes.copyOfRange(7, 15)))
            return Update.AwaitData
        }
        if (command == 2) {
            val receiving = state as? State.Receiving ?: return null
            require(bytes.size == 3 || bytes.size == 7) { "历史记录结束标记异常" }
            val buffer = receiving.buffer.toByteArray()
            val activity = if (type == 1) BandHealthProtocol.activity(buffer, receiving.start) else emptyList()
            val heart = if (type == 2) BandHealthProtocol.manualHeart(buffer) else emptyList()
            state = State.Finished
            val last = activity.maxOfOrNull { it.at } ?: heart.maxOfOrNull { it.at }
            return Update.Complete(activity, heart, last?.plus(60_000)?.takeIf { it > since })
        }
        return null
    }
}
