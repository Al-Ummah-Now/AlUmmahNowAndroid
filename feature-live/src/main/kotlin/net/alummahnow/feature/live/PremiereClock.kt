package net.alummahnow.feature.live

import java.time.Instant
import kotlin.math.abs

/** Where a premiere is (wiki E17 §5.4): everyone sees the same moment, by the server's clock. */
internal data class PremiereClock(val startedAt: Instant, val offsetMs: Long, val durationMs: Long?) {
    /** Milliseconds into the video at [now], between 0 and its length. */
    fun position(now: Instant): Long {
        val at = (now.toEpochMilli() + offsetMs - startedAt.toEpochMilli()).coerceAtLeast(0)
        return if (durationMs == null) at else minOf(at, durationMs)
    }

    fun isOver(now: Instant): Boolean = durationMs != null && position(now) >= durationMs
}

/**
 * What the player is doing at one tick. [canKeepUp]: enough is loaded, so another seek would not only start it over.
 * [behindLiveMs]: how far a live stream's newest moment is ahead; null for a video.
 */
data class PlayerReading(
    val positionMs: Long,
    val isPlaying: Boolean,
    val canKeepUp: Boolean,
    val speed: Float,
    val behindLiveMs: Long? = null
)

sealed interface FollowAction {
    data class Seek(val positionMs: Long) : FollowAction

    data object NormalSpeed : FollowAction
}

/**
 * Keeps a player at the premiere's moment. A viewer may pause or go back: they are then behind, until they catch up or
 * return. Going ahead is not possible, and neither is playing faster than everyone else while at the moment.
 */
internal class PremiereFollower {
    var isFollowing = true
        private set
    var isBehind = false
        private set
    private var hasPlayed = false
    private var lastMs: Long? = null

    fun returned() {
        isFollowing = true
        isBehind = false
        lastMs = null
    }

    fun tick(momentMs: Long, reading: PlayerReading): List<FollowAction> {
        val actions = decide(momentMs, reading)
        isBehind = !isFollowing && momentMs - reading.positionMs > RESYNC_MS
        return actions
    }

    private fun decide(momentMs: Long, reading: PlayerReading): List<FollowAction> {
        val drift = momentMs - reading.positionMs
        // Back further than playing explains: the viewer went there
        val wentBack = hasPlayed && lastMs?.let { reading.positionMs < it - AHEAD_MS } == true
        if (wentBack && drift > RESYNC_MS) isFollowing = false
        lastMs = reading.positionMs
        // Ahead, also when they caught up and passed it between two ticks
        if (drift < -AHEAD_MS) {
            isFollowing = true
            lastMs = momentMs
            return listOfNotNull(FollowAction.NormalSpeed.takeIf { reading.speed != 1f }, FollowAction.Seek(momentMs))
        }
        if (!reading.isPlaying) {
            if (hasPlayed) isFollowing = false
            return emptyList()
        }
        hasPlayed = true
        if (!isFollowing && abs(drift) <= AHEAD_MS) isFollowing = true
        if (!isFollowing) return emptyList()
        val resync = reading.canKeepUp && drift > RESYNC_MS
        if (resync) lastMs = momentMs
        return listOfNotNull(
            FollowAction.NormalSpeed.takeIf { reading.speed != 1f },
            FollowAction.Seek(momentMs).takeIf { resync }
        )
    }

    companion object {
        /** Further from the moment than this, playback is brought back to it. */
        const val RESYNC_MS = 3_000L

        /** Nobody gets ahead of it by more than the jitter of a seek. */
        const val AHEAD_MS = 1_000L
    }
}
