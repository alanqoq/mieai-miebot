package com.mieai.bot.event

import com.mieai.bot.config.ChatConfig

object TriggerDecider {
    fun shouldTrigger(
        eventType: String,
        groupId: String,
        text: String?,
        hasImages: Boolean,
        config: ChatConfig,
        isBotMentioned: Boolean,
        randomPercent: () -> Int,
    ): Boolean {
        if (!config.isChatEnabled(groupId)) return false
        if (eventType.equals(GROUP_AT_MESSAGE_CREATE, ignoreCase = true)) return true
        if (!eventType.equals(GROUP_MESSAGE_CREATE, ignoreCase = true)) return false
        if (isBotMentioned) return true
        val keyword = config.keywordFor(groupId)
        if (!text.isNullOrEmpty() && text.contains(keyword, ignoreCase = true)) return true
        if (text.isNullOrBlank() && !hasImages) return false
        return randomPercent() <= config.probabilityFor(groupId)
    }

    private const val GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"
    private const val GROUP_MESSAGE_CREATE = "GROUP_MESSAGE_CREATE"
}
