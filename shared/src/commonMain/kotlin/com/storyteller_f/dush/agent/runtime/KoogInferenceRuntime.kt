package com.storyteller_f.dush.agent.runtime

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow

class KoogInferenceRuntime(private val transportFactory: () -> HttpClient) : InferenceRuntime {
    override fun generate(connection: ConnectionEntity, credential: String, systemPrompt: String,
        history: List<ConversationMessage>, temperature: Double, maxTokens: Int) = flow {
        val config = connection.validated()
        val provider = InferenceProvider.valueOf(config.provider)
        require(provider != InferenceProvider.LlmdAndroid)
        val transport = transportFactory()
        try {
            val factory = KtorKoogHttpClient.Factory(transport)
            val timeout = ConnectionTimeoutConfig(requestTimeoutMillis = 120_000, connectTimeoutMillis = 15_000, socketTimeoutMillis = 120_000)
            val client: LLMClient = if (provider == InferenceProvider.OpenRouter) OpenRouterLLMClient(
                apiKey = credential, settings = OpenRouterClientSettings(baseUrl = "${config.endpoint}/", chatCompletionsPath = "chat/completions", timeoutConfig = timeout), httpClientFactory = factory,
            ) else OpenAILLMClient(
                apiKey = credential, settings = OpenAIClientSettings(baseUrl = "${config.endpoint}/", chatCompletionsPath = "chat/completions", timeoutConfig = timeout), httpClientFactory = factory,
            )
            try {
                val model = LLModel(provider = if (provider == InferenceProvider.OpenRouter) LLMProvider.OpenRouter else LLMProvider.OpenAI,
                    id = config.model, capabilities = listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Completions))
                val request = prompt("chat", params = LLMParams(temperature = temperature, maxTokens = maxTokens)) {
                    system(systemPrompt)
                    history.forEach { message -> when (message.role) {
                        MessageRole.User -> user(message.content)
                        MessageRole.Assistant -> assistant(message.content)
                    } }
                }
                var received = false
                var ended = false
                client.executeStreaming(request, model).collect { frame ->
                    when (frame) {
                        is StreamFrame.TextDelta -> { if (frame.text.isNotEmpty()) received = true; emit(frame.text) }
                        is StreamFrame.End -> ended = true
                        else -> Unit
                    }
                }
                if (!received || !ended) throw InferenceException("推理服务未返回完整文字回复，请检查模型或重试。")
            } finally { client.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: InferenceException) { throw failure }
        catch (failure: Exception) {
            val status = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull()?.statusCode
            throw InferenceException(when (status) {
                401, 403 -> "推理服务授权失败，请检查 API key 或 llmd 授权。"
                402 -> "推理服务余额不足，请检查账户。"
                429 -> "推理服务请求过于频繁，请稍后重试。"
                else -> "推理服务请求失败，请检查地址、模型、授权和网络。"
            })
        } finally { transport.close() }
    }
}
