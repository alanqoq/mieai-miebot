package com.mieai.bot

import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.spi.BotPlugin
import java.util.concurrent.atomic.AtomicBoolean

class MieAiBotPlugin(
    private val context: PluginRuntimeContext,
) : BotPlugin {
    private val started = AtomicBoolean(false)
    @Volatile
    private var engine: MieAiEngine? = null

    override fun start() {
        check(started.compareAndSet(false, true)) { "MieAI Bot is already started" }
        var candidate: MieAiEngine? = null
        try {
            candidate = MieAiEngine(context)
            candidate.start()
            engine = candidate
        } catch (failure: Throwable) {
            runCatching { candidate?.close() }.onFailure(failure::addSuppressed)
            started.set(false)
            throw failure
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        val current = engine
        engine = null
        current?.close()
    }
}
