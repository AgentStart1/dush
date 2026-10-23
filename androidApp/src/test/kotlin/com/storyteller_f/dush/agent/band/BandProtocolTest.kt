package com.storyteller_f.dush.agent.band

import java.math.BigInteger
import kotlin.test.*
import org.junit.Test

class BandProtocolTest {
    @Test fun decodesKnownReadyPacket() {
        val packet = "03070001000200000010000300".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = BandPackets().decode(packet)
        assertEquals(0x10, decoded.payload?.endpoint)
        assertContentEquals(byteArrayOf(3, 0), decoded.payload?.data)
        assertContentEquals(byteArrayOf(4, 0, 1, 1, 0), decoded.ack)
    }
    @Test fun encryptedFragmentsSurviveReassembly() {
        val sender = BandPackets(40); val receiver = BandPackets()
        val shared = ByteArray(48) { (it * 7).toByte() }; val key = ByteArray(16) { it.toByte() }
        sender.authenticate(shared, key); receiver.authenticate(shared, key)
        val message = bandText(7, "这是一个跨越多个蓝牙数据包的模拟语音识别结果", false)
        val decoded = sender.encode(0x10, message, true).map { receiver.decode(it) }
        assertTrue(decoded.size > 1)
        assertTrue(decoded.dropLast(1).all { it.payload == null })
        assertContentEquals(message, decoded.last().payload?.data)
    }
    @Test fun rejectsCorruptedEncryptedAudio() {
        val sender = BandPackets(); val receiver = BandPackets()
        val shared = ByteArray(48); val key = ByteArray(16)
        sender.authenticate(shared, key); receiver.authenticate(shared, key)
        val corrupted = sender.encode(0x10, byteArrayOf(5, 0, 0, 0, 0, 1, 42), true).single().copyOf()
        corrupted[12] = (corrupted[12].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { receiver.decode(corrupted) }
    }
    @Test fun reassemblesOpusFrameSplitAcrossPayloads() {
        val frames = BandOpusFrames()
        assertTrue(frames.accept(byteArrayOf(2, 11)).isEmpty())
        val first = frames.accept(byteArrayOf(22, 3, 33))
        assertEquals(1, first.size); assertContentEquals(byteArrayOf(11, 22), first.single())
        assertContentEquals(byteArrayOf(33, 44, 55), frames.accept(byteArrayOf(44, 55)).single())
    }
    @Test fun rejectsOutOfOrderFragments() {
        val chunks = BandPackets(30).encode(0x10, ByteArray(100) { 7 }, false)
        val receiver = BandPackets(); receiver.decode(chunks.first())
        assertFailsWith<IllegalArgumentException> { receiver.decode(chunks[2]) }
    }
    @Test fun textLimitPreservesUtf8CharactersAndTerminator() {
        val text = bandText(7, "测试😀".repeat(200), false)
        assertTrue(text.size <= 483)
        assertEquals(0, text.last().toInt())
        assertFalse(String(text.copyOfRange(2, text.lastIndex), Charsets.UTF_8).contains('\uFFFD'))
    }
    @Test fun b163ScalarOneMatchesStandardBasePoint() {
        val expectedX = BigInteger("3f0eba16286a2d57ea0991168d4994637e8343e36", 16)
        assertEquals(expectedX, BigInteger(1, BandCurve.publicKey(BigInteger.ONE).copyOfRange(0, 24).reversedArray()))
    }
}
