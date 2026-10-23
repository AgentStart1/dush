package com.storyteller_f.dush.agent.band

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import java.io.File

@RunWith(AndroidJUnit4::class)
class BandSpeechDeviceTest {
    @Test fun recognizesCapturedBandOpusLocally() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "band-speech/vosk-model-small-cn-0.22")
        val fixture = File(context.cacheDir, "band-test-opus.bin")
        // Private recordings are supplied locally, never committed as test fixtures.
        assumeTrue("Provision the local model and an authorized recording before this device test", directory.isDirectory && fixture.isFile)
        val input = fixture.readBytes()
        BandOpusDecoder(1).use { decoder ->
            val blocks = BandOpusFrames().accept(input).flatMap(decoder::accept) + decoder.finish()
            File(context.cacheDir, "band-decoder-test.pcm").outputStream().use { output -> blocks.forEach { output.write(it.bytes) } }
            File(context.cacheDir, "band-decoder-test-rate.txt").writeText(blocks.firstOrNull()?.rate.toString())
        }
        var partialSeen = false
        Model(directory.absolutePath).use { model ->
            LocalBandSpeech(model, 1).use { speech ->
                val frames = BandOpusFrames().accept(input)
                assertTrue("Recording must contain complete Opus frames", frames.isNotEmpty())
                frames.forEach { frame -> if (speech.accept(frame).text.isNotBlank()) partialSeen = true }
                val final = speech.finish()
                assertTrue("Streaming recognition should publish intermediate text", partialSeen)
                assertTrue("Offline recognition should produce a final utterance", final.isNotBlank())
            }
        }
    }
}
