package net.alummahnow.feature.live

import java.time.Duration

/**
 * When the server wants to be asked again (`pollAfterMs`, `Retry-After`; wiki E17 §5.2), so it can slow installed apps
 * down. Between 5 seconds and 10 minutes whatever it says: a bad value neither stops the app nor makes it hammer.
 */
internal fun pollAfter(ms: Long?): Duration? = ms?.let { Duration.ofMillis(it.coerceIn(MIN_MS, MAX_MS)) }

private const val MIN_MS = 5_000L
private const val MAX_MS = 600_000L
