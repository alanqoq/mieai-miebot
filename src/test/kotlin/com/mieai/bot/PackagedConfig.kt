package com.mieai.bot

import com.mieai.bot.config.MieAiBotConfig
import com.mieai.bot.config.MieAiConfigCodec

internal fun packagedDefaultConfig(): MieAiBotConfig =
    MieAiConfigCodec.parse(requireNotNull(object {}.javaClass.getResource("/qqbot-plugin-default.yml")).readText())
