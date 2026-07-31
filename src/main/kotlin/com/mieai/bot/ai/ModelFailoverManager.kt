package com.mieai.bot.ai

import java.util.concurrent.CancellationException

class ModelFailoverManager(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val warning: (String, Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile
    private var window: FallbackWindow? = null
    private var routeKey: RouteKey? = null
    private var primaryEpoch = 0L

    fun <T> execute(
        primaryModel: String,
        fallbackModel: String,
        fallbackDurationMinutes: Long,
        request: (String) -> T,
    ): T {
        val primary = primaryModel.trim()
        val fallback = fallbackModel.trim()
        require(primary.isNotEmpty()) { "Primary model is not configured" }
        val selected = select(primary, fallback)
        if (selected.usingFallback) return request(selected.model)

        return try {
            request(primary)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ChatApiException) {
            if (!canUseFallback(primary, fallback)) throw failure
            val activated = activate(primary, fallback, fallbackDurationMinutes, selected.primaryEpoch)
            warning(
                if (activated) "Primary model failed; fallback window activated" else
                    "A stale primary request failed; this request will use the fallback model",
                failure,
            )
            try {
                request(fallback)
            } catch (fallbackFailure: CancellationException) {
                throw fallbackFailure
            } catch (fallbackFailure: Throwable) {
                fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }

    fun currentSelection(primaryModel: String, fallbackModel: String): ModelSelection =
        select(primaryModel.trim(), fallbackModel.trim())

    private fun select(primary: String, fallback: String): ModelSelection = synchronized(this) {
        val requestedRoute = RouteKey(primary, fallback)
        if (routeKey != requestedRoute) {
            routeKey = requestedRoute
            window = null
            advanceEpoch()
        }
        if (!canUseFallback(primary, fallback)) {
            window = null
            return@synchronized ModelSelection(primary, false, null, primaryEpoch)
        }
        val active = window
        val now = clockMillis()
        if (active != null && active.route == requestedRoute && now < active.untilMillis) {
            return@synchronized ModelSelection(fallback, true, active.untilMillis, active.primaryEpoch)
        }
        if (active != null) {
            window = null
            advanceEpoch()
        }
        ModelSelection(primary, false, null, primaryEpoch)
    }

    private fun activate(primary: String, fallback: String, minutes: Long, requestEpoch: Long): Boolean =
        synchronized(this) {
            val route = RouteKey(primary, fallback)
            if (routeKey != route) return@synchronized false
            val now = clockMillis()
            val active = window
            if (active != null && active.route == route && now < active.untilMillis) return@synchronized true
            if (active != null) {
                window = null
                advanceEpoch()
            }
            if (requestEpoch != primaryEpoch) return@synchronized false
            val duration = minutes.coerceAtLeast(1).coerceAtMost(Long.MAX_VALUE / 60_000L) * 60_000L
            val until = runCatching { Math.addExact(now, duration) }.getOrDefault(Long.MAX_VALUE)
            window = FallbackWindow(route, until, primaryEpoch)
            true
        }

    private fun canUseFallback(primary: String, fallback: String): Boolean =
        fallback.isNotEmpty() && !fallback.equals(primary, ignoreCase = true)

    private fun advanceEpoch() {
        primaryEpoch = if (primaryEpoch == Long.MAX_VALUE) 0 else primaryEpoch + 1
    }

    private data class RouteKey(val primary: String, val fallback: String)
    private data class FallbackWindow(val route: RouteKey, val untilMillis: Long, val primaryEpoch: Long)
}

data class ModelSelection(
    val model: String,
    val usingFallback: Boolean,
    val fallbackUntilMillis: Long?,
    val primaryEpoch: Long,
)
