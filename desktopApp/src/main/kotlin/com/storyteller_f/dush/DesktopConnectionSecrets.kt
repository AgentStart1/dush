package com.storyteller_f.dush

import com.github.javakeyring.Keyring
import com.storyteller_f.dush.agent.repository.ConnectionSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Native store failures must never be mistaken for an empty API key.
 * Non-secret markers distinguish a never-configured key without relying on localized
 * backend exceptions. A crash before marker publication can leave an unused native item.
 */
class DesktopConnectionSecrets(private val directory: Path) : ConnectionSecrets {
    private val mutex = Mutex()
    private val service = "com.storyteller_f.dush." + digest(directory.toAbsolutePath().normalize().toString())
    private fun marker(id: String) = directory.resolve(digest(id))
    override suspend fun read(id: String): String = access {
        if (!Files.exists(marker(id))) "" else Keyring.create().use { keyring ->
            val stored = keyring.getPassword(service, id)
            check(stored.startsWith("v1:"))
            stored.removePrefix("v1:")
        }
    }
    override suspend fun write(id: String, secret: String) = access {
        if (secret.isEmpty()) remove(id) else {
            Files.createDirectories(directory)
            // A nonempty envelope also avoids native backends rejecting zero-length blobs.
            Keyring.create().use { it.setPassword(service, id, "v1:$secret") }
            Files.write(marker(id), byteArrayOf(1))
        }
        Unit
    }
    override suspend fun delete(id: String) = access { remove(id) }
    private fun remove(id: String) {
        if (Files.exists(marker(id))) {
            Keyring.create().use {
                // Recreate an empty envelope before deletion: a prior attempt may have
                // deleted the native item but failed to remove its marker. Backend
                // missing-item errors cannot safely be distinguished from access errors.
                removeMarkedCredential(marker(id),
                    empty = { it.setPassword(service, id, "v1:") },
                    delete = { it.deletePassword(service, id) })
            }
        }
    }
    private suspend fun <T> access(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try { block() }
            catch (_: Exception) { throw IllegalStateException("系统凭据存储不可用，请解锁系统钥匙串后重试。") }
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun removeMarkedCredential(marker: Path, empty: () -> Unit, delete: () -> Unit) {
    empty()
    delete()
    Files.delete(marker)
}
