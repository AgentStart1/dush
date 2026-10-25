package com.storyteller_f.dush.agent.repository

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionSecretsDeviceTest {
    @Test fun credentialsReopenEncryptedAndCanBeRemoved() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "connection-secrets-test").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        try {
            AndroidConnectionSecrets(context).write("test", "synthetic-test-credential")
            assertEquals("synthetic-test-credential", AndroidConnectionSecrets(context).read("test"))
            val bytes = File(directory, "inference-credentials.json").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("synthetic-test-credential"))
            AndroidConnectionSecrets(context).delete("test")
            assertEquals("", AndroidConnectionSecrets(context).read("test"))
        } finally { directory.deleteRecursively() }
    }
}
