package com.storyteller_f.dush.agent.band

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

sealed interface SpeechModelState {
    data object Missing : SpeechModelState
    data class Downloading(val bytes: Long, val total: Long) : SpeechModelState
    data object Ready : SpeechModelState
    data class Failed(val message: String) : SpeechModelState
}

class BandSpeechModels(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.noBackupFilesDir, "band-speech")
    private val directory = File(root, "vosk-model-small-cn-0.22")
    private val _state = MutableStateFlow<SpeechModelState>(SpeechModelState.Missing)
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var downloadJob: Job? = null
    private var model: Model? = null
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile) _state.value = SpeechModelState.Ready
    }
    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                _state.value = SpeechModelState.Downloading(0, 0)
                root.mkdirs()
                val archive = File(root, "model.download")
                val connection = URL(MODEL_URL).openConnection().apply { connectTimeout = 20_000; readTimeout = 30_000 }
                try {
                    connection.getInputStream().use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= 100 * 1024 * 1024) { "识别模型文件过大" }
                            output.write(buffer, 0, count)
                            _state.value = SpeechModelState.Downloading(total, connection.contentLengthLong)
                        }
                    } }
                    val staging = File(root, "staging").apply { deleteRecursively(); mkdirs() }
                    var expanded = 0L
                    ZipInputStream(archive.inputStream().buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            currentCoroutineContext().ensureActive()
                            val target = File(staging, entry.name).canonicalFile
                            require(target.path.startsWith(staging.canonicalPath + File.separator)) { "识别模型压缩包路径错误" }
                            if (entry.isDirectory) target.mkdirs() else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = zip.read(buffer); if (count < 0) break
                                        expanded += count; require(expanded <= 256 * 1024 * 1024)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    }
                    val extracted = File(staging, directory.name)
                    require(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) { "识别模型文件不完整" }
                    mutex.withLock {
                        check(model == null) { "模型正在使用" }
                        directory.deleteRecursively()
                        check(extracted.renameTo(directory)) { "无法保存识别模型" }
                    }
                    staging.deleteRecursively()
                    _state.value = SpeechModelState.Ready
                } finally { archive.delete() }
            } catch (cancelled: CancellationException) { _state.value = SpeechModelState.Missing; throw cancelled }
            catch (failure: Exception) { _state.value = SpeechModelState.Failed("模型下载失败，请检查网络后重试") }
        }
    }
    internal suspend fun get(): Model = withContext(Dispatchers.IO) { mutex.withLock {
        check(_state.value == SpeechModelState.Ready) { "请先下载本地中文识别模型" }
        model ?: Model(directory.absolutePath).also { model = it }
    } }
    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    }
}

internal data class SpeechUpdate(val text: String, val endpoint: Boolean)
internal fun normalizeBandSpeech(text: String): String = text.trim().replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"), "")

/** One utterance; invoked only by its connection coroutine, away from Android's UI thread. */
internal data class BandPcm(val bytes: ByteArray, val rate: Int)

internal class BandOpusDecoder(codec: Int) : Closeable {
    private val decoder = MediaCodec.createDecoderByType("audio/opus")
    private var presentation = 0L
    private var rate = 48000
    init {
        require(codec == 1 || codec == 2) { "手环音频编码不支持" }
        try {
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray()).put(1.toByte()).put(1.toByte()).putShort(0)
                .putInt(if (codec == 1) 8000 else 16000).putShort(0).put(0.toByte()).array()
            val format = MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(head))
                setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(80_000_000).apply { flip() })
            }
            decoder.configure(format, null, null, 0); decoder.start()
        } catch (failure: Throwable) { decoder.release(); throw failure }
    }
    fun accept(frame: ByteArray): List<BandPcm> {
        val pcm = drain().toMutableList()
        val input = decoder.dequeueInputBuffer(10_000)
        check(input >= 0) { "音频解码缓冲区繁忙" }
        requireNotNull(decoder.getInputBuffer(input)).apply { clear(); put(frame) }
        decoder.queueInputBuffer(input, 0, frame.size, presentation, 0); presentation += 20_000
        pcm += drain()
        return pcm
    }
    private fun drain(timeoutUs: Long = 0): List<BandPcm> {
        val result = mutableListOf<BandPcm>()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val output = decoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                output >= 0 -> {
                    try {
                        val buffer = requireNotNull(decoder.getOutputBuffer(output))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size); buffer.get(pcm)
                        if (pcm.isNotEmpty()) result += BandPcm(pcm, rate)
                    } finally { decoder.releaseOutputBuffer(output, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return result
                }
                else -> return result
            }
        }
    }
    fun finish(): List<BandPcm> {
        val input = decoder.dequeueInputBuffer(10_000)
        check(input >= 0)
        decoder.queueInputBuffer(input, 0, 0, presentation, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return drain(10_000)
    }
    override fun close() {
        try { decoder.stop() } finally { decoder.release() }
    }
}

internal class LocalBandSpeech(private val model: Model, codec: Int) : Closeable {
    private val decoder = BandOpusDecoder(codec)
    private var recognizer: Recognizer? = null
    private var completed = ""
    private var lastPartial = ""
    private fun consume(pcm: List<BandPcm>): Boolean {
        var endpoint = false
        for (block in pcm) {
            val r = recognizer ?: Recognizer(model, block.rate.toFloat()).also { recognizer = it }
            if (r.acceptWaveForm(block.bytes, block.bytes.size)) {
                val text = normalizeBandSpeech(JSONObject(r.result).optString("text"))
                completed += text; endpoint = endpoint || text.isNotBlank()
            }
        }
        return endpoint
    }
    fun accept(frame: ByteArray): SpeechUpdate {
        val endpoint = consume(decoder.accept(frame))
        val partial = recognizer?.let { normalizeBandSpeech(JSONObject(it.partialResult).optString("partial")) }.orEmpty()
        lastPartial = completed + partial
        return SpeechUpdate(lastPartial, endpoint)
    }
    fun finish(): String {
        consume(decoder.finish())
        val final = recognizer?.let { normalizeBandSpeech(JSONObject(it.finalResult).optString("text")) }.orEmpty()
        return (completed + final).ifBlank { lastPartial }
    }
    override fun close() { try { recognizer?.close() } finally { decoder.close() } }
}
