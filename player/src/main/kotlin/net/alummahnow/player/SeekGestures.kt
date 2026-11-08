package net.alummahnow.player

/** Spec 13.2: skip ±10 s; double-tap on the start side goes back. Start is the right side in RTL. */
object SeekGestures {
    const val SKIP_MS = 10_000L

    /** Returns the seek delta for a double tap at [x] in a view of [width], or 0 for the centre third. */
    fun doubleTapDelta(x: Float, width: Float, isRtl: Boolean): Long {
        if (width <= 0f) return 0L
        val third = width / 3f
        val onLeft = x < third
        val onRight = x > width - third
        return when {
            !onLeft && !onRight -> 0L
            onLeft != isRtl -> -SKIP_MS // left side in LTR, right side in RTL = start = back
            else -> SKIP_MS
        }
    }

    fun clampedTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (positionMs + deltaMs).coerceIn(0L, upper)
    }
}
