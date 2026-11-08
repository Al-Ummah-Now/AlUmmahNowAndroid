package net.alummahnow.core.bridge.media

/**
 * Minimal edit-decision list (EDL) mirroring the `aun-media` project format (Epic 30.5) closely enough
 * that the Android editor spike can be driven by it. The Rust core will own versioning and sync.
 */
data class EditProject(
    val version: Int = 1,
    val clips: List<Clip>,
    val overlays: List<TextOverlay> = emptyList()
)

data class Clip(val sourceUri: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Burned-in text. [isRtl] is decided by the text itself (first strong character), not the UI locale. */
data class TextOverlay(val text: String, val startMs: Long, val endMs: Long, val position: OverlayPosition = OverlayPosition.LOWER_THIRD)

enum class OverlayPosition { TOP, CENTER, LOWER_THIRD }

sealed interface EditValidation {
    data object Ok : EditValidation
    data class Invalid(val problems: List<String>) : EditValidation
}

/** Facade for `aun-media` render planning. */
interface EditPlanner {
    fun validate(project: EditProject): EditValidation
    fun totalDurationMs(project: EditProject): Long
}

/** Kotlin stand-in for `aun-media` validation until the core ships. */
class TemporaryEditPlanner : EditPlanner {
    override fun validate(project: EditProject): EditValidation {
        val problems = buildList {
            if (project.clips.isEmpty()) add("no clips")
            project.clips.forEachIndexed { i, c ->
                if (c.startMs < 0) add("clip $i starts before 0")
                if (c.endMs <= c.startMs) add("clip $i is empty or reversed")
            }
            val total = totalDurationMs(project)
            project.overlays.forEachIndexed { i, o ->
                if (o.text.isBlank()) add("overlay $i has no text")
                if (o.endMs <= o.startMs) add("overlay $i is empty or reversed")
                if (o.startMs >= total) add("overlay $i starts after the end")
            }
        }
        return if (problems.isEmpty()) EditValidation.Ok else EditValidation.Invalid(problems)
    }

    override fun totalDurationMs(project: EditProject): Long = project.clips.sumOf { it.durationMs.coerceAtLeast(0) }
}
