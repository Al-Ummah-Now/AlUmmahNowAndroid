package net.alummahnow.core.bridge.media

import java.util.UUID

/** One clip on the timeline: a range of a source video. Times in milliseconds. */
data class EditClip(
    val sourceUri: String,
    val durationMs: Long,
    val sourceStartMs: Long = 0,
    val id: String = UUID.randomUUID().toString()
)

/**
 * The editor's edit model, the same as Apple's `EditDecisionList` (AUNCoreBridge/Media.swift), which both Apple editors
 * use today: one video track, clips back to back. Every edit returns a new list, so undo keeps whole snapshots.
 * Core's versioned EDL (`aun-media`) replaces it on both platforms together.
 */
data class EditDecisionList(val clips: List<EditClip> = emptyList()) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    fun append(clip: EditClip): EditDecisionList = copy(clips = clips + clip)

    /** Where each clip starts on the timeline. */
    fun startOf(index: Int): Long = clips.take(index).sumOf { it.durationMs }

    /** The clip under a timeline position and the position within it, or null past the end. */
    fun locate(timeMs: Long): Pair<Int, Long>? {
        var start = 0L
        clips.forEachIndexed { index, clip ->
            if (timeMs >= start && timeMs < start + clip.durationMs) return index to (timeMs - start)
            start += clip.durationMs
        }
        return null
    }

    /** Splits at a timeline position. Ignored when either side would be shorter than [MIN_CLIP_MS]. */
    fun split(atMs: Long): EditDecisionList {
        val (index, offset) = locate(atMs) ?: return this
        val clip = clips[index]
        if (offset < MIN_CLIP_MS || clip.durationMs - offset < MIN_CLIP_MS) return this
        val head = clip.copy(durationMs = offset)
        val tail = EditClip(clip.sourceUri, clip.durationMs - offset, clip.sourceStartMs + offset)
        return copy(
            clips = clips.toMutableList().apply {
                set(index, head)
                add(index + 1, tail)
            }
        )
    }

    /** Removes a clip and closes the gap. */
    fun rippleDelete(id: String): EditDecisionList = copy(clips = clips.filterNot { it.id == id })

    /** Moves a clip earlier (negative) or later (positive), clamped to the ends. */
    fun move(id: String, by: Int): EditDecisionList {
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return this
        val target = (index + by).coerceIn(0, clips.size - 1)
        val list = clips.toMutableList()
        list.add(target, list.removeAt(index))
        return copy(clips = list)
    }

    /** Trims a clip's ends within its current range (`startMs`..`endMs` from its start), keeping [MIN_CLIP_MS]. */
    fun trim(id: String, startMs: Long, endMs: Long): EditDecisionList {
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return this
        val clip = clips[index]
        val safeStart = startMs.coerceIn(0, clip.durationMs - MIN_CLIP_MS)
        val safeEnd = endMs.coerceIn(safeStart + MIN_CLIP_MS, clip.durationMs)
        val trimmed = clip.copy(sourceStartMs = clip.sourceStartMs + safeStart, durationMs = safeEnd - safeStart)
        return copy(clips = clips.toMutableList().apply { set(index, trimmed) })
    }

    companion object {
        /** The shortest clip an edit may leave (Apple: 0.1 s). */
        const val MIN_CLIP_MS = 100L
    }
}
