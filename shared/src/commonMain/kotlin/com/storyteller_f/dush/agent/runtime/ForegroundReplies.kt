package com.storyteller_f.dush.agent.runtime

import com.storyteller_f.dush.agent.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/** Application-owned replies. Commands serialize replacement through cancellation cleanup.
 * Navigation does not cancel replies; shutdown joins every reply before the database closes.
 * A new instance must be created only after the previous application owner has stopped.
 */
class ForegroundReplies(private val chats: ChatRepository, private val agents: AgentRepository,
    private val runner: AgentRunner, dispatcher: CoroutineDispatcher) {
    private sealed interface Command {
        data class Send(val threadId: String, val content: String, val result: CompletableDeferred<Unit>) : Command
        data class Stop(val threadId: String, val result: CompletableDeferred<Unit>) : Command
        data class Finished(val threadId: String, val owner: String) : Command
    }
    private data class Reply(val owner: String, val job: Job)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow<Set<String>>(emptySet())
    val active = mutable.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val processor = scope.launch {
        val replies = mutableMapOf<String, Reply>()
        try {
            try {
                chats.recoverInterruptedReplies()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Native database loading can throw an Error rather than an Exception.
                // Startup must still resolve readiness so the graph can release its resources.
                ready.completeExceptionally(InferenceException("无法恢复会话，请检查数据目录。"))
                return@launch
            }
            ready.complete(Unit)
            for (command in commands) {
                val result = when (command) {
                    is Command.Send -> command.result
                    is Command.Stop -> command.result
                    is Command.Finished -> null
                }
                try {
                    when (command) {
                        is Command.Send -> {
                            require(command.content.isNotBlank())
                            val thread = requireNotNull(chats.getThread(command.threadId))
                            val agent = thread.agentId?.let { agents.getAgent(it) } ?: agents.defaultAgent()
                            replies[command.threadId]?.job?.cancelAndJoin()
                            replies.remove(command.threadId)
                            chats.appendUserMessage(command.threadId, command.content)
                            val owner = Uuid.random().toString()
                            val job = scope.launch(start = CoroutineStart.LAZY) {
                                try { runner.run(command.threadId, agent.id, owner) }
                                finally { commands.trySend(Command.Finished(command.threadId, owner)) }
                            }
                            replies[command.threadId] = Reply(owner, job)
                            job.start()
                        }
                        is Command.Stop -> {
                            replies[command.threadId]?.job?.cancelAndJoin()
                            replies.remove(command.threadId)
                        }
                        is Command.Finished -> if (replies[command.threadId]?.owner == command.owner) replies.remove(command.threadId)
                    }
                    result?.complete(Unit)
                } catch (cancelled: CancellationException) {
                    result?.cancel(cancelled)
                    throw cancelled
                } catch (_: Exception) {
                    result?.completeExceptionally(InferenceException("操作失败，请检查会话和 Agent 配置。"))
                } finally { mutable.value = replies.keys.toSet() }
            }
        } catch (cancelled: CancellationException) {
            ready.cancel(cancelled)
            throw cancelled
        } catch (_: Exception) {
            ready.completeExceptionally(InferenceException("无法恢复会话，请检查数据目录。"))
        } finally {
            commands.close()
            withContext(NonCancellable) { replies.values.forEach { it.job.cancel() }; replies.values.forEach { it.job.join() } }
            mutable.value = emptySet()
            while (true) {
                when (val pending = commands.tryReceive().getOrNull() ?: break) {
                    is Command.Send -> pending.result.cancel()
                    is Command.Stop -> pending.result.cancel()
                    is Command.Finished -> Unit
                }
            }
        }
    }
    suspend fun awaitReady() = ready.await()
    suspend fun send(threadId: String, content: String) {
        ready.await()
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Send(threadId, content, result))
        result.await()
    }
    suspend fun stop(threadId: String) {
        ready.await()
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Stop(threadId, result))
        result.await()
    }
    suspend fun close() { processor.cancelAndJoin(); scope.cancel() }
}
