package com.storyteller_f.dush.agent.repository

import com.storyteller_f.dush.agent.data.ConnectionDao
import com.storyteller_f.dush.agent.data.ConnectionEntity
import com.storyteller_f.dush.agent.data.AgentDatabase
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.uuid.Uuid

enum class InferenceProvider { OpenAI, OpenRouter, OpenAICompatible, LlmdHttp, LlmdAndroid }

/** Platform-owned encrypted credential persistence. Never include credentials in entities or diagnostics. */
interface ConnectionSecrets {
    suspend fun read(id: String): String
    suspend fun write(id: String, secret: String)
    suspend fun delete(id: String)
}

fun ConnectionEntity.validated(): ConnectionEntity {
    require(name.isNotBlank()) { "Enter a connection name" }
    require(model.isNotBlank()) { "Enter the exact service model ID" }
    val type = InferenceProvider.valueOf(provider)
    val address = endpoint.trim().trimEnd('/')
    if (type == InferenceProvider.LlmdAndroid) {
        require(address in setOf("com.storytellerf.llmd", "com.storytellerf.llmd.debug", "com.storytellerf.llmd.alpha", "com.storytellerf.llmd.e2e")) { "Select an installed llmd package" }
    } else {
        val url = try { Url(address) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid API base URL") }
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isBlank()) { "Use an HTTP(S) base URL without credentials, query or fragment" }
        require(url.protocol == URLProtocol.HTTPS || type in listOf(InferenceProvider.LlmdHttp, InferenceProvider.OpenAICompatible)) { "This provider requires HTTPS" }
    }
    return copy(name = name.trim(), endpoint = address, model = model.trim())
}

class ConnectionRepository(private val dao: ConnectionDao, private val secrets: ConnectionSecrets) {
    fun observeConnections() = dao.observeAll()
    suspend fun getConnection(id: String) = dao.get(id)
    suspend fun defaultConnection() = dao.defaultConnection()
    suspend fun credential(id: String) = secrets.read(id)
    /** null preserves the credential; an empty string explicitly clears it. */
    suspend fun save(connection: ConnectionEntity, credential: String? = null): String {
        val validated = connection.validated()
        if (credential != null) secrets.write(validated.id, credential.trim())
        require(InferenceProvider.valueOf(validated.provider) !in listOf(InferenceProvider.OpenAI, InferenceProvider.OpenRouter) || secrets.read(validated.id).isNotBlank()) { "Enter an API key" }
        dao.saveConnection(validated)
        return validated.id
    }
    suspend fun selectDefault(id: String) = dao.makeDefault(id)
    suspend fun delete(id: String) {
        dao.removeConnection(id)
        secrets.delete(id)
    }
    fun draft() = ConnectionEntity(Uuid.random().toString(), "", InferenceProvider.OpenRouter.name, "https://openrouter.ai/api/v1", "", now(), false)
}
