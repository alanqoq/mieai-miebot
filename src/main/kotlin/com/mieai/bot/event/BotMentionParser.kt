package com.mieai.bot.event

import com.fasterxml.jackson.databind.ObjectMapper

/** Reads the QQ Gateway marker that identifies the current bot in a mention list. */
class BotMentionParser(private val json: ObjectMapper) {
    fun isBotMentioned(rawPayload: String): Boolean = runCatching {
        val mentions = json.readTree(rawPayload).path("d").path("mentions")
        mentions.isArray && mentions.any { mention ->
            mention.path("is_you").isBoolean && mention.path("is_you").booleanValue()
        }
    }.getOrDefault(false)
}
