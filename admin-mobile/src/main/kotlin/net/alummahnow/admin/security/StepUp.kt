package net.alummahnow.admin.security

import android.os.SystemClock

/**
 * Step-up for high-risk actions (AD5): revealing a minor item and approving an escalated item need a sign-in within
 * 15 minutes. The app reopens sign-in with `prompt=login&max_age=900`; admin-api checks `auth_time` as well, and a
 * `step_up_required` answer starts the same flow.
 */
class StepUp(private val now: () -> Long = SystemClock::elapsedRealtime) {
    @Volatile private var signedInAt: Long? = null

    /** A sign-in (or step-up) just completed. */
    fun signedIn() {
        signedInAt = now()
    }

    fun forget() {
        signedInAt = null
    }

    val isFresh: Boolean get() = signedInAt?.let { now() - it < WINDOW_MS } == true

    companion object {
        const val MAX_AGE_SECONDS = 900
        const val WINDOW_MS = MAX_AGE_SECONDS * 1000L
    }
}
