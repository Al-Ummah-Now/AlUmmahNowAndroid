package net.alummahnow.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks

/** A selectable video quality. [height] null means Auto (adaptive). */
data class QualityOption(val height: Int?, val groupIndex: Int = -1, val trackIndex: Int = -1) {
    val isAuto: Boolean get() = height == null
}

/** A subtitle choice. [language] null means Off. */
data class SubtitleOption(val language: String?, val label: String?)

/** Playback speeds offered in the UI (spec 13.2: 0.5–2×, pitch-corrected by ExoPlayer). */
val PlaybackSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

object TrackOptions {
    /** Auto first, then distinct heights, highest first. */
    fun qualities(tracks: Tracks): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (t in 0 until group.length) {
                val height = group.getTrackFormat(t).height
                if (height > 0 && group.isTrackSupported(t)) options += QualityOption(height, g, t)
            }
        }
        return listOf(QualityOption(null)) + options.distinctBy { it.height }.sortedByDescending { it.height }
    }

    /** The quality currently forced by an override, or Auto. */
    fun selectedQuality(params: TrackSelectionParameters, tracks: Tracks): QualityOption {
        val height = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .firstNotNullOfOrNull { group ->
                params.overrides[group.mediaTrackGroup]?.trackIndices?.singleOrNull()?.let { group.getTrackFormat(it).height }
            }
        return QualityOption(height)
    }

    fun applyQuality(params: TrackSelectionParameters, tracks: Tracks, option: QualityOption): TrackSelectionParameters {
        val builder = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (!option.isAuto) {
            val group = tracks.groups.getOrNull(option.groupIndex) ?: return builder.build()
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        return builder.build()
    }

    /** Off first, then one entry per subtitle language. */
    fun subtitles(tracks: Tracks): List<SubtitleOption> {
        val languages = tracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            .mapNotNull { f -> f.language?.let { SubtitleOption(it, f.label) } }
            .distinctBy { it.language }
        return listOf(SubtitleOption(null, null)) + languages
    }

    fun selectedSubtitle(params: TrackSelectionParameters, tracks: Tracks): SubtitleOption {
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) return SubtitleOption(null, null)
        val selected = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected } ?: return SubtitleOption(null, null)
        val format = (0 until selected.length).firstOrNull { selected.isTrackSelected(it) }?.let { selected.getTrackFormat(it) }
        return SubtitleOption(format?.language, format?.label)
    }

    fun applySubtitle(params: TrackSelectionParameters, option: SubtitleOption): TrackSelectionParameters =
        if (option.language == null) {
            params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            params.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(option.language)
                .build()
        }
}
