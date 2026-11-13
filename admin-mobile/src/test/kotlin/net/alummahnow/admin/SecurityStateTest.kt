package net.alummahnow.admin

import net.alummahnow.admin.security.AppLock
import net.alummahnow.admin.security.StepUp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateTest {
    private var clock = 0L

    @Test fun `locked on open, unlocked after the prompt, locked again after 5 minutes in the background`() {
        val lock = AppLock(now = { clock })
        assertTrue(lock.locked.value)
        lock.unlocked()
        assertFalse(lock.locked.value)

        lock.onBackground()
        clock += AppLock.BACKGROUND_TIMEOUT_MS - 1
        lock.onForeground()
        assertFalse("under 5 minutes keeps it open", lock.locked.value)

        lock.onBackground()
        clock += AppLock.BACKGROUND_TIMEOUT_MS
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun `step-up is fresh for 15 minutes after a sign-in`() {
        val stepUp = StepUp(now = { clock })
        assertFalse(stepUp.isFresh)
        stepUp.signedIn()
        clock += StepUp.WINDOW_MS - 1
        assertTrue(stepUp.isFresh)
        clock += 1
        assertFalse(stepUp.isFresh)
        stepUp.signedIn()
        stepUp.forget()
        assertFalse(stepUp.isFresh)
    }
}
