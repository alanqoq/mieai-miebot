package com.mieai.bot.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OpenAiChatProvider(
    private val httpClient: PluginHttpClient,
    private val json: ObjectMapper,
) : ChatProvider {
    override fun complete(request: ChatCompletionRequest): String {
        return try {
            completeInternal(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ChatApiException) {
            throw failure
        } catch (failure: Exception) {
            // Failover is intentionally driven by ChatApiException. Convert client,
            // serialization, and transport-specific failures here so a provider
            // implementation detail cannot bypass the configured fallback model.
            throw ChatApiException("AI request failed", failure)
        }
    }

    private fun completeInternal(request: ChatCompletionRequest): String {
        require(request.apiKey.isNotBlank()) { "API key is not configured" }
        val model = request.model.trim()
        require(model.isNotEmpty()) { "AI model is not configured" }
        val mimo = MimoModelCapabilities.supportsBase64Images(model)
        val protocol = if (mimo) OPENAI_OLD else request.protocol.trim().lowercase()
        val path = when (protocol) {
            OPENAI_OLD -> "/v1/chat/completions"
            OPENAI_NEW -> "/v1/responses"
            else -> throw ChatApiException("Unsupported AI protocol")
        }
        val payload = if (protocol == OPENAI_OLD) oldPayload(request, mimo) else responsesPayload(request)
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        if (mimo) headers["api-key"] = request.apiKey else headers["Authorization"] = "Bearer ${request.apiKey}"

        val timeout = Duration.ofSeconds(request.timeoutSeconds.coerceAtLeast(1))
        val outbound = PluginHttpRequest(
            method = "POST",
            uri = endpoint(request.baseUrl, path),
            headers = headers,
            body = json.writeValueAsBytes(payload),
            timeout = timeout,
        )
        val future = httpClient.send(outbound).toCompletableFuture()
        val response = try {
            future.get(timeout.seconds + 5, TimeUnit.SECONDS)
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("AI request interrupted")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: TimeoutException) {
            future.cancel(true)
            throw ChatApiException("AI request timed out", failure)
        } catch (failure: ExecutionException) {
            throw ChatApiException("AI request failed", failure.cause ?: failure)
        }
        val responseText = response.bodyAsUtf8()
        if (response.statusCode !in 200..299) {
            throw ChatApiException("AI endpoint returned HTTP ${response.statusCode}")
        }
        val root = try {
            json.readTree(responseText)
        } catch (failure: Exception) {
            throw ChatApiException("AI endpoint returned invalid JSON", failure)
        }
        val answer = if (protocol == OPENAI_OLD) extractOld(root) else extractResponse(root)
        return answer.trim().ifEmpty { throw ChatApiException("AI endpoint returned an empty reply") }
    }

    private fun oldPayload(request: ChatCompletionRequest, mimo: Boolean): ObjectNode = json.createObjectNode().apply {
        put("model", request.model.trim())
        set<ArrayNode>("messages", json.createArrayNode().apply {
            add(json.createObjectNode().put("role", "system").put("content", request.systemPrompt))
            request.turns.forEach { turn -> add(oldTurn(turn, request.imageUnderstandingEnabled && mimo)) }
        })
    }

    private fun oldTurn(turn: ChatTurn, includeImages: Boolean): ObjectNode = json.createObjectNode().apply {
        put("role", if (turn.role == ChatRole.ASSISTANT) "assistant" else "user")
        val text = displayText(turn)
        if (includeImages && turn.role == ChatRole.USER && turn.images.isNotEmpty()) {
            set<ArrayNode>("content", json.createArrayNode().apply {
                if (text.isNotBlank()) add(json.createObjectNode().put("type", "text").put("text", text))
                turn.images.sortedBy { it.sequence }.forEach { image ->
                    add(
                        json.createObjectNode().put("type", "image_url").set<ObjectNode>(
                            "image_url",
                            json.createObjectNode().put("url", "data:${image.mimeType};base64,${image.base64}"),
                        ),
                    )
                }
            })
        } else {
            put("content", text)
        }
    }

    private fun responsesPayload(request: ChatCompletionRequest): ObjectNode = json.createObjectNode().apply {
        put("model", request.model.trim())
        put("instructions", request.systemPrompt)
        put("store", false)
        set<ArrayNode>("input", json.createArrayNode().apply {
            request.turns.forEach { turn ->
                add(
                    json.createObjectNode()
                        .put("role", if (turn.role == ChatRole.ASSISTANT) "assistant" else "user")
                        .put("content", displayText(turn)),
                )
            }
        })
    }

    private fun displayText(turn: ChatTurn): String {
        val text = turn.text.orEmpty()
        return if (turn.role == ChatRole.USER && !turn.authorId.isNullOrBlank()) {
            "[user:${turn.authorId}] $text".trim()
        } else {
            text
        }
    }

    private fun extractOld(root: JsonNode): String {
        val content = root.path("choices").path(0).path("message").path("content")
        return extractTextNode(content)
    }

    private fun extractResponse(root: JsonNode): String {
        val direct = root.path("output_text")
        if (direct.isTextual) return direct.asText()
        val parts = ArrayList<String>()
        root.path("output").takeIf(JsonNode::isArray)?.forEach { item ->
            item.path("content").takeIf(JsonNode::isArray)?.forEach { content ->
                if (content.path("type").asText() == "output_text" && content.path("text").isTextual) {
                    parts += content.path("text").asText()
                }
            }
        }
        return parts.joinToString("\n")
    }

    private fun extractTextNode(content: JsonNode): String = when {
        content.isTextual -> content.asText()
        content.isArray -> content.mapNotNull { part -> part.path("text").takeIf(JsonNode::isTextual)?.asText() }
            .joinToString("\n")
        else -> ""
    }

    private fun endpoint(baseUrl: String, path: String): URI {
        val base = baseUrl.trim().removeSuffix("/")
        return try {
            URI.create(base + path)
        } catch (failure: IllegalArgumentException) {
            throw ChatApiException("AI base URL is invalid", failure)
        }
    }

    companion object {
        const val OPENAI_OLD = "openai-old"
        const val OPENAI_NEW = "openai-new"
    }
}
