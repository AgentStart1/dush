package com.storyteller_f.dush.agent.repository

import android.content.Context
import com.storyteller_f.dush.agent.band.EncryptedJsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidConnectionSecrets(context: Context) : ConnectionSecrets {
    private val file = EncryptedJsonFile(context, "inference-credentials.json", "dush-inference-credentials")
    private val mutex = Mutex()
    override suspend fun read(id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { JSONObject(file.read() ?: "{}").optString(id) }
    }
    override suspend fun write(id: String, secret: String) = withContext(Dispatchers.IO) {
        mutex.withLock { file.write(JSONObject(file.read() ?: "{}").put(id, secret).toString()) }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { val data = JSONObject(file.read() ?: "{}"); data.remove(id); file.write(data.toString()) }
    }
}
