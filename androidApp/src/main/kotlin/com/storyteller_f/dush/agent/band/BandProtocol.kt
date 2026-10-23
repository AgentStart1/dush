package com.storyteller_f.dush.agent.band

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** NIST B-163 wire points are two little-endian 24-byte coordinates. */
internal object BandCurve {
    private val one = BigInteger.ONE
    private val polynomial = one.shiftLeft(163).setBit(7).setBit(6).setBit(3).setBit(0)
    private val coefficient = BigInteger("20a601907b8c953ca1481eb10512f78744a3205fd", 16)
    private val base = Point(BigInteger("3f0eba16286a2d57ea0991168d4994637e8343e36", 16), BigInteger("0d51fbc6c71a0094fa2cdd545b11c5c0c797324f1", 16))
    internal data class Point(val x: BigInteger, val y: BigInteger)
    fun privateKey(): BigInteger = BigInteger(162, SecureRandom()).setBit(161)
    fun publicKey(private: BigInteger): ByteArray = encode(requireNotNull(scale(private, base)))
    fun shared(private: BigInteger, public: ByteArray): ByteArray {
        require(public.size == 48) { "Invalid authentication point" }
        val p = Point(integer(public.copyOfRange(0, 24)), integer(public.copyOfRange(24, 48)))
        require(p.x.bitLength() <= 163 && p.y.bitLength() <= 163 &&
            mul(p.y, p.y).xor(mul(p.x, p.y)) == mul(mul(p.x, p.x), p.x).xor(mul(p.x, p.x)).xor(coefficient)) { "Invalid authentication point" }
        return encode(requireNotNull(scale(private, p)))
    }
    private fun integer(b: ByteArray) = BigInteger(1, b.reversedArray())
    private fun encode(p: Point) = coordinate(p.x) + coordinate(p.y)
    private fun coordinate(v: BigInteger) = v.toByteArray().reversedArray().copyOf(24)
    private fun mul(a: BigInteger, b: BigInteger): BigInteger {
        var x = a; var y = b; var result = BigInteger.ZERO
        while (y.signum() != 0) {
            if (y.testBit(0)) result = result.xor(x)
            y = y.shiftRight(1); x = x.shiftLeft(1)
            if (x.testBit(163)) x = x.xor(polynomial)
        }
        return result
    }
    private fun inverse(a: BigInteger): BigInteger {
        require(a.signum() != 0)
        var u = a; var v = polynomial; var g1 = one; var g2 = BigInteger.ZERO
        while (u != one) {
            var j = u.bitLength() - v.bitLength()
            if (j < 0) { val t = u; u = v; v = t; val g = g1; g1 = g2; g2 = g; j = -j }
            u = u.xor(v.shiftLeft(j)); g1 = g1.xor(g2.shiftLeft(j))
        }
        return g1
    }
    private fun add(p: Point?, q: Point?): Point? {
        if (p == null) return q
        if (q == null) return p
        val x = p.x; val y = p.y; val u = q.x; val v = q.y
        if (x == u) {
            if (y != v || x.signum() == 0) return null
            val slope = x.xor(mul(y, inverse(x)))
            val next = mul(slope, slope).xor(slope).xor(one)
            return Point(next, mul(x, x).xor(mul(slope.xor(one), next)))
        }
        val slope = mul(y.xor(v), inverse(x.xor(u)))
        val next = mul(slope, slope).xor(slope).xor(x).xor(u).xor(one)
        return Point(next, mul(slope, x.xor(next)).xor(next).xor(y))
    }
    private fun scale(k: BigInteger, p: Point): Point? {
        var scalar = k; var point: Point? = p; var result: Point? = null
        while (scalar.signum() != 0) {
            if (scalar.testBit(0)) result = add(result, point)
            point = add(point, point); scalar = scalar.shiftRight(1)
        }
        return result
    }
}

internal fun aes(data: ByteArray, key: ByteArray, mode: Int): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
    init(mode, SecretKeySpec(key, "AES")); doFinal(data)
}
internal fun littleInt(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

/** Per-connection codec. The connection coroutine exclusively owns its counters and reassembly. */
internal class BandPackets(private val mtu: Int = 247) {
    data class Payload(val endpoint: Int, val data: ByteArray)
    data class Decoded(val ack: ByteArray?, val payload: Payload?)
    private data class Assembly(val size: Int, val endpoint: Int, val encrypted: Boolean, var next: Int, val bytes: ByteArrayOutputStream)
    private val assemblies = mutableMapOf<Int, Assembly>()
    private var handle = 0
    private var sequence = 0
    private var sessionKey: ByteArray? = null
    fun authenticate(shared: ByteArray, secret: ByteArray): ByteArray {
        require(shared.size == 48 && secret.size == 16)
        sequence = ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN).int
        return ByteArray(16) { shared[it + 8].toInt().xor(secret[it].toInt()).toByte() }.also { sessionKey = it }
    }
    fun encode(endpoint: Int, original: ByteArray, encrypted: Boolean): List<ByteArray> {
        require(original.isNotEmpty() && original.size <= 8192)
        handle = (handle + 1) and 255
        val payload = if (encrypted) {
            val key = requireNotNull(sessionKey) { "Authentication required" }
            val content = original + littleInt(sequence++)
            val crc = CRC32().apply { update(content) }.value.toInt()
            val padded = (content + littleInt(crc)).let { it.copyOf((it.size + 15) / 16 * 16) }
            aes(padded, ByteArray(16) { (key[it].toInt() xor handle).toByte() }, Cipher.ENCRYPT_MODE)
        } else original
        val chunks = mutableListOf<ByteArray>()
        var cursor = 0; var count = 0
        while (cursor < payload.size) {
            val first = count == 0
            val headerSize = if (first) 11 else 5
            val length = minOf(mtu - 3 - headerSize, payload.size - cursor)
            require(length > 0 && count <= 255)
            val last = cursor + length == payload.size
            val flags = (if (first) 1 else 0) or (if (last) 6 else 0) or (if (encrypted) 8 else 0)
            val header = byteArrayOf(3, flags.toByte(), 0, handle.toByte(), count.toByte()) +
                if (first) littleInt(original.size) + byteArrayOf(endpoint.toByte(), (endpoint shr 8).toByte()) else byteArrayOf()
            chunks += header + payload.copyOfRange(cursor, cursor + length)
            cursor += length; count++
        }
        return chunks
    }
    fun decode(data: ByteArray): Decoded {
        require(data.size >= 5 && data[0] == 3.toByte()) { "Invalid channel packet" }
        val flags = data[1].toInt() and 255
        val handle = data[3].toInt() and 255
        val count = data[4].toInt() and 255
        val first = flags and 1 != 0
        val offset = if (first) 11 else 5
        require(data.size >= offset)
        if (first) {
            val header = ByteBuffer.wrap(data, 5, 6).order(ByteOrder.LITTLE_ENDIAN)
            val size = header.int; val endpoint = header.short.toInt() and 65535
            require(size in 1..8192 && count == 0 && assemblies.size < 16)
            assemblies[handle] = Assembly(size, endpoint, flags and 8 != 0, 0, ByteArrayOutputStream())
        }
        val assembly = requireNotNull(assemblies[handle]) { "Missing first fragment" }
        require(count == assembly.next++ && assembly.encrypted == (flags and 8 != 0)) { "Out of order fragment" }
        assembly.bytes.write(data, offset, data.size - offset)
        require(assembly.bytes.size() <= 8224)
        val ack = if (flags and 4 != 0) byteArrayOf(4, 0, handle.toByte(), 1, count.toByte()) else null
        if (flags and 2 == 0) return Decoded(ack, null)
        assemblies.remove(handle)
        val bytes = assembly.bytes.toByteArray()
        val plain = if (assembly.encrypted) {
            val key = requireNotNull(sessionKey)
            require(bytes.size == (assembly.size + 8 + 15) / 16 * 16)
            val result = aes(bytes, ByteArray(16) { (key[it].toInt() xor handle).toByte() }, Cipher.DECRYPT_MODE)
            val checksum = ByteBuffer.wrap(result, assembly.size + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(checksum == CRC32().apply { update(result, 0, assembly.size + 4) }.value.toInt()) { "Channel checksum failed" }
            result.copyOf(assembly.size)
        } else {
            require(bytes.size == assembly.size); bytes
        }
        return Decoded(ack, Payload(assembly.endpoint, plain))
    }
}

/** Keeps incomplete Opus frames between channel payloads, bounded by the one-byte length. */
internal class BandOpusFrames {
    private var pending = byteArrayOf()
    fun accept(bytes: ByteArray): List<ByteArray> {
        val input = pending + bytes; val frames = mutableListOf<ByteArray>(); var cursor = 0
        while (cursor < input.size) {
            val length = input[cursor].toInt() and 255
            require(length > 0) { "Invalid Opus frame length" }
            if (cursor + 1 + length > input.size) break
            frames += input.copyOfRange(cursor + 1, cursor + 1 + length); cursor += length + 1
        }
        pending = input.copyOfRange(cursor, input.size)
        return frames
    }
}

internal fun bandText(command: Int, text: String, final: Boolean? = null): ByteArray {
    // Replies allow 300 Unicode characters; ASR retains its existing 480-byte limit.
    val clean = text.replace("\u0000", "").codePoints().toArray()
    val out = ByteArrayOutputStream()
    for ((index, codePoint) in clean.withIndex()) {
        val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        if (command == 9) {
            if (index >= 300) break
        } else if (out.size() + bytes.size > 480) break
        out.write(bytes)
    }
    return byteArrayOf(command.toByte()) + (final?.let { byteArrayOf(if (it) 1 else 0) } ?: byteArrayOf()) + out.toByteArray() + byteArrayOf(0)
}
