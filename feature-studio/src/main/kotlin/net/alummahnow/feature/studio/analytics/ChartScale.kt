package net.alummahnow.feature.studio.analytics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Ticks for a 0-based count axis on round steps (1, 2, 5 × 10ⁿ), ending at or above [max].
 * The same scale as the web's Studio charts (shared-types chart-scale.ts), whole numbers only.
 */
fun countTicks(max: Long, target: Int = 4): List<Long> {
    if (max <= 0) return listOf(0, 1)
    val raw = max.toDouble() / target
    val magnitude = 10.0.pow(floor(log10(raw)))
    val nice = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
    val step = ceil(nice).toLong().coerceAtLeast(1)
    val count = ceil(max.toDouble() / step).toLong().coerceAtLeast(1)
    return (0..count).map { it * step }
}

/** Indices to label on the time axis: the first, the middle and the last (fewer when there are fewer). */
fun dateLabelIndices(size: Int): List<Int> = when {
    size <= 0 -> emptyList()
    size <= 2 -> (0 until size).toList()
    else -> listOf(0, (size - 1) / 2, size - 1)
}
