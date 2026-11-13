package net.alummahnow.admin.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device unlock on open and after [timeoutMs] in the background (AUN-ADMIN-NATIVE: 5 minutes). Process-wide, so a
 * rotation or a trip to the sign-in browser does not ask again; a cold start always does.
 */
class AppLock(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = BACKGROUND_TIMEOUT_MS
) {
    private val mutableLocked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()
    private var backgroundedAt: Long? = null

    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = now()
    }

    fun onForeground() {
        val at = backgroundedAt ?: return
        backgroundedAt = null
        if (now() - at >= timeoutMs) mutableLocked.value = true
    }

    fun unlocked() {
        mutableLocked.value = false
    }

    fun lockNow() {
        mutableLocked.value = true
    }

    companion object {
        const val BACKGROUND_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
