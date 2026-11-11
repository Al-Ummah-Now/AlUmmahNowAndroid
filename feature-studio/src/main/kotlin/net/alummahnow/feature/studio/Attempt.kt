package net.alummahnow.feature.studio

import kotlin.coroutines.cancellation.CancellationException
import net.alummahnow.core.bridge.AunLog

/**
 * Runs a bridge call for a screen: any failure becomes a [Result] the screen turns into a message
 * ([studioMessage]); coroutine cancellation is never swallowed.
 */
suspend fun <T> attempt(area: String, what: String, block: suspend () -> T): Result<T> {
    val result = runCatching { block() }
    result.exceptionOrNull()?.let { e ->
        if (e is CancellationException) throw e
        AunLog.w(area, "$what failed", "type" to e.javaClass.simpleName)
    }
    return result
}
