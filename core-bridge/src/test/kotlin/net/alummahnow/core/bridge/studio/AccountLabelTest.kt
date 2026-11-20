package net.alummahnow.core.bridge.studio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The account name follows the platform rule (display name → first + last → email, never an @handle). aun-core
 * (≥ 0.4.0-rc.1, AlUmmahNowCore 34ee2f8) resolves display name then first + last into `displayName` and never uses a
 * handle; the app adds the email last.
 */
class AccountLabelTest {
    private fun account(name: String?, email: String?) = StudioAccount("u1", name, email, null, null, needsReauth = false)

    @Test fun `the resolved name comes first`() = assertEquals("Amina Yusuf", account("Amina Yusuf", "a@example.com").label)

    @Test fun `no name falls back to the email`() {
        assertEquals("a@example.com", account(null, "a@example.com").label)
        assertEquals("a@example.com", account("", "a@example.com").label)
        assertEquals("a@example.com", account("   ", "a@example.com").label)
    }
}
