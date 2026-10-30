package com.storyteller_f.dush.agent.ui

import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import com.storyteller_f.dush.agent.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConnectionState(val items: List<ConnectionEntity> = emptyList(), val draft: ConnectionEntity,
    val credential: String? = null, val busy: Boolean = false, val ready: Boolean = false, val message: String? = null)

/** Screen-owned state; credentials never enter saved UI state or metadata. */
class ConnectionHost(private val repository: ConnectionRepository, private val runtime: InferenceRuntime,
    id: String?, dispatcher: CoroutineDispatcher, parent: Job? = null) {
    private val scope = CoroutineScope(SupervisorJob(parent) + dispatcher)
    private val mutable = MutableStateFlow(ConnectionState(draft = repository.draft()))
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init {
        scope.launch { repository.observeConnections().collect { items -> mutable.update { it.copy(items = items) } } }
        scope.launch {
            val draft = id?.takeIf { it.isNotEmpty() }?.let { repository.getConnection(it) }
            mutable.update { it.copy(draft = draft ?: it.draft, ready = true, message = if (!id.isNullOrEmpty() && draft == null) "连接已不存在" else null) }
        }
    }
    fun edit(transform: (ConnectionEntity) -> ConnectionEntity) = scope.launch {
        if (!mutable.value.busy) mutable.update { it.copy(draft = transform(it.draft), message = null) }
    }
    fun credential(value: String) = scope.launch { if (!mutable.value.busy) mutable.update { it.copy(credential = value.takeIf(String::isNotEmpty)) } }
    fun clearCredential() = scope.launch { if (!mutable.value.busy) mutable.update { it.copy(credential = "") } }
    private fun perform(block: suspend () -> String) = scope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, message = null) }
        operation = launch {
            try { val message = block(); ensureActive(); mutable.update { it.copy(message = message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(message = if (failure is IllegalArgumentException || failure is InferenceException) failure.message else "操作失败，请检查连接配置。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun save() = perform { val snapshot = mutable.value; repository.save(snapshot.draft, snapshot.credential); mutable.update { it.copy(credential = null) }; "连接已保存" }
    fun delete() = perform { repository.delete(mutable.value.draft.id); "连接已删除" }
    fun selectDefault() = perform { repository.selectDefault(mutable.value.draft.id); "已设为默认连接" }
    /** Sends only a synthetic prompt; never sends conversation or peripheral data. */
    fun test() = perform {
        val snapshot = mutable.value
        val config = snapshot.draft.validated()
        val secret = snapshot.credential ?: repository.credential(config.id)
        var text = ""
        runtime.generate(config, secret, "Reply briefly.", listOf(ConversationMessage(MessageRole.User, "Say OK.")), 0.0, 32).collect { text += it }
        require(text.isNotBlank()) { "服务没有返回文字" }
        "连接测试成功"
    }
    fun cancel() = scope.launch { operation?.cancelAndJoin(); mutable.update { it.copy(busy = false, message = "已取消") } }
    fun close() = scope.cancel()
}
