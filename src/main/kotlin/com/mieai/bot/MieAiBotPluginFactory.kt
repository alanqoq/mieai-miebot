package com.mieai.bot

import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory

class MieAiBotPluginFactory : BotPluginFactory {
    override val pluginId: String = "mieai-bot"

    override fun create(context: PluginRuntimeContext): BotPlugin = MieAiBotPlugin(context)
}
