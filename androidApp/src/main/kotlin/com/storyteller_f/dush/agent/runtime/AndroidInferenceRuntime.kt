package com.storyteller_f.dush.agent.runtime

import android.content.*
import android.os.IBinder
import com.storytellerf.llmd.ipc.ILlmdService
import com.storytellerf.llmd.ipc.ILlmdChatCallback
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import org.json.JSONArray
import org.json.JSONObject

/** llmd Android's published AIDL has no streaming/cancel RPC. Unbind and ignore late replies on cancel. */
class AndroidInferenceRuntime(private val context: Context, private val http: InferenceRuntime) : InferenceRuntime {
    override fun generate(connection: ConnectionEntity, credential: String, systemPrompt: String,
        history: List<ConversationMessage>, temperature: Double, maxTokens: Int) = flow {
        if (connection.provider != InferenceProvider.LlmdAndroid.name) {
            emitAll(http.generate(connection, credential, systemPrompt, history, temperature, maxTokens))
            return@flow
        }
        connection.validated()
        val text = withContext(Dispatchers.IO) {
            try { withTimeoutOrNull(120_000) {
                val connected = CompletableDeferred<ILlmdService>()
                val response = CompletableDeferred<String>()
                val health = CompletableDeferred<String>()
                val binding = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(ILlmdService.Stub.asInterface(binder)) }
                    override fun onServiceDisconnected(name: ComponentName) { fail() }
                    override fun onBindingDied(name: ComponentName) { fail() }
                    override fun onNullBinding(name: ComponentName) { fail() }
                    private fun fail() {
                        connected.completeExceptionally(InferenceException("llmd 服务已断开。"))
                        response.completeExceptionally(InferenceException("llmd 服务已断开。"))
                        health.completeExceptionally(InferenceException("llmd 服务已断开。"))
                    }
                }
                val bound = context.bindService(Intent("com.storytellerf.llmd.action.BIND_IPC").setPackage(connection.endpoint), binding, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                if (!bound) throw InferenceException("未找到 llmd，请检查所选安装版本。")
                try {
                    val service = connected.await()
                    service.healthAsync(callback(health))
                    val state = JSONObject(health.await())
                    checkError(state)
                    if (!state.optBoolean("engineReady") || state.optString("activeModel") != connection.model) {
                        throw InferenceException("请在 llmd 中启用所选模型服务，并等待 Ready。")
                    }
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
                    history.forEach { messages.put(JSONObject().put("role", if (it.role == MessageRole.User) "user" else "assistant").put("content", it.content)) }
                    val request = JSONObject().put("model", connection.model).put("messages", messages)
                        .put("temperature", temperature).put("max_tokens", maxTokens).put("stream", false)
                    require(request.toString().toByteArray().size < 500_000) { "IPC request too large" }
                    service.chatCompletionAsync(request.toString(), callback(response))
                    val result = JSONObject(response.await())
                    checkError(result)
                    result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        .also { if (it.isBlank()) throw InferenceException("llmd 没有返回文字回复。") }
                } finally { context.unbindService(binding) }
            } ?: throw InferenceException("llmd 请求超时，请检查服务状态。") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: InferenceException) { throw failure }
            catch (_: Exception) { throw InferenceException("llmd 请求失败，请检查授权、服务状态和上下文长度。") }
        }
        emit(text)
    }
    private fun callback(result: CompletableDeferred<String>) = object : ILlmdChatCallback.Stub() {
        override fun onComplete(responseJson: String) { result.complete(responseJson) }
    }
    private fun checkError(json: JSONObject) {
        val error = json.optJSONObject("error") ?: return
        throw InferenceException(if (error.optString("type") == "authorization_required") "请点击授权 llmd，允许 dush 使用服务。" else "llmd 无法完成请求，请检查模型服务状态。")
    }
}
