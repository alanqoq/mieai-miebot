package com.mieai.bot.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mieai.bot.history.StoredImage
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import com.mieai.qqbot.plugin.testkit.FakePluginHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiChatProviderTest {
    private val json = jacksonObjectMapper()

    @Test
    fun usesChatCompletionsAndBearerForOpenAiOld() {
        val http = FakePluginHttpClient {
            PluginHttpResponse(200, emptyMap(), """{"choices":[{"message":{"content":"old answer"}}]}""".toByteArray())
        }
        val answer = OpenAiChatProvider(http, json).complete(request(protocol = "openai-old"))

        assertEquals("old answer", answer)
        val sent = http.requests().single()
        assertEquals("https://example.test/v1/chat/completions", sent.uri.toString())
        assertEquals("Bearer secret", sent.headers["Authorization"])
        val body = json.readTree(sent.body)
        assertEquals("gpt-test", body.path("model").asText())
        assertEquals("system", body.path("messages").path(0).path("role").asText())
    }

    @Test
    fun usesResponsesWithStoreDisabled() {
        val http = FakePluginHttpClient {
            PluginHttpResponse(
                200,
                emptyMap(),
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"new answer"}]}]}"""
                    .toByteArray(),
            )
        }
        val answer = OpenAiChatProvider(http, json).complete(request(protocol = "openai-new"))

        assertEquals("new answer", answer)
        val sent = http.requests().single()
        assertEquals("https://example.test/v1/responses", sent.uri.toString())
        val body = json.readTree(sent.body)
        assertFalse(body.path("store").asBoolean(true))
        assertTrue(body.path("input").isArray)
    }

    @Test
    fun everyMimoV25PrefixUsesOpenAiBearerChatCompletionsAndMultipleBase64Images() {
        val http = FakePluginHttpClient {
            PluginHttpResponse(200, emptyMap(), """{"choices":[{"message":{"content":"seen"}}]}""".toByteArray())
        }
        val request = request(protocol = "openai-new", model = "  MiMo-V2.5-pro-preview  ").copy(
            turns = listOf(
                ChatTurn(
                    ChatRole.USER,
                    "look",
                    "member-openid",
                    listOf(
                        StoredImage(0, "image/png", "YWJj"),
                        StoredImage(1, "image/jpeg", "ZGVm"),
                    ),
                ),
            ),
            imageUnderstandingEnabled = true,
        )
        assertEquals("seen", OpenAiChatProvider(http, json).complete(request))

        val sent = http.requests().single()
        assertEquals("https://example.test/v1/chat/completions", sent.uri.toString())
        assertEquals("Bearer secret", sent.headers["Authorization"])
        assertEquals(null, sent.headers["api-key"])
        val content = json.readTree(sent.body).path("messages").path(1).path("content")
        assertEquals(3, content.size())
        assertEquals("data:image/png;base64,YWJj", content.path(1).path("image_url").path("url").asText())
        assertEquals("data:image/jpeg;base64,ZGVm", content.path(2).path("image_url").path("url").asText())
    }

    private fun request(protocol: String, model: String = "gpt-test") = ChatCompletionRequest(
        baseUrl = "https://example.test",
        apiKey = "secret",
        protocol = protocol,
        model = model,
        timeoutSeconds = 10,
        systemPrompt = "system prompt",
        turns = listOf(ChatTurn(ChatRole.USER, "hello", "member-openid")),
        imageUnderstandingEnabled = false,
    )
}
