package net.alummahnow.player

/** Media time increases from left to right in every language. */
object SeekGestures {
    const val SKIP_MS = 10_000L

    /** Returns the seek delta for a double tap at [x] in a view of [width], or 0 for the centre third. */
    fun doubleTapDelta(x: Float, width: Float): Long {
        if (width <= 0f) return 0L
        val third = width / 3f
        return when {
            x < third -> -SKIP_MS
            x > width - third -> SKIP_MS
            else -> 0L
        }
    }

    fun clampedTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (positionMs + deltaMs).coerceIn(0L, upper)
    }
}
