package com.mieai.bot.ai

import com.mieai.bot.history.StoredImage

data class ChatTurn(
    val role: ChatRole,
    val text: String?,
    val authorId: String? = null,
    val images: List<StoredImage> = emptyList(),
)

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatCompletionRequest(
    val baseUrl: String,
    val apiKey: String,
    val protocol: String,
    val model: String,
    val timeoutSeconds: Long,
    val systemPrompt: String,
    val turns: List<ChatTurn>,
    val imageUnderstandingEnabled: Boolean,
)

fun interface ChatProvider {
    fun complete(request: ChatCompletionRequest): String
}

class ChatApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object MimoModelCapabilities {
    fun supportsBase64Images(model: String): Boolean =
        model.trim().lowercase().startsWith("mimo-v2.5")
}
