package net.alummahnow.feature.studio

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.SupportLinks
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheet
import net.alummahnow.feature.studio.terms.CreatorTermsGate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CreatorTermsAndLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `SupportLinks resolves English and Arabic URLs`() {
        assertEquals("https://videos.alummahnow.net/en/help", SupportLinks.helpUrl("en"))
        assertEquals("https://videos.alummahnow.net/ar/help", SupportLinks.helpUrl("ar"))
        assertEquals("https://videos.alummahnow.net/en/contact", SupportLinks.contactUrl("en"))
        assertEquals("https://videos.alummahnow.net/ar/contact", SupportLinks.contactUrl("ar"))
        assertEquals("https://www.alummahnow.net/en/privacy/", SupportLinks.privacyUrl("en"))
        assertEquals("https://www.alummahnow.net/ar/privacy/", SupportLinks.privacyUrl("ar"))
        assertEquals("https://www.alummahnow.net/en/terms/", SupportLinks.termsUrl("en"))
        assertEquals("https://www.alummahnow.net/ar/terms/", SupportLinks.termsUrl("ar"))
        assertEquals("https://videos.alummahnow.net/ar/community-guidelines", SupportLinks.communityGuidelinesUrl("ar"))
    }

    @Test
    fun `CreatorTermsGate requires checkbox before agreeing and opens links`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val opened = mutableListOf<String>()
        var agreed = 0
        compose.setContent {
            AunTheme {
                CreatorTermsGate(
                    saving = false,
                    error = null,
                    onOpenUrl = { opened += it },
                    onAgree = { agreed++ }
                )
            }
        }
        compose.onNodeWithTag("creator-terms-agree").assertIsNotEnabled()
        compose.onNodeWithTag("creator-terms-link").performClick()
        compose.onNodeWithTag("creator-guidelines-link").performClick()
        assertEquals(
            listOf("https://www.alummahnow.net/en/terms/", "https://videos.alummahnow.net/en/community-guidelines"),
            opened
        )
        compose.onNodeWithTag("creator-terms-checkbox").performClick()
        compose.onNodeWithTag("creator-terms-agree").assertIsEnabled()
        compose.onNodeWithTag("creator-terms-agree").performClick()
        assertEquals(1, agreed)
    }

    @Test
    fun `AccountSheet exposes Help, Contact, Privacy and Terms links`() {
        RuntimeEnvironment.setQualifiers("ar-ldrtl")
        val opened = mutableListOf<String>()
        compose.setContent {
            AunTheme {
                AccountSheet(
                    accounts = listOf(sampleAccount),
                    activeUserId = sampleAccount.userId,
                    channel = sampleChannels[0],
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onSignOut = {},
                    onSwitchChannel = {},
                    onDismiss = {},
                    onOpenUrl = { opened += it }
                )
            }
        }
        compose.onNodeWithTag("studio-help").performScrollTo().performClick()
        compose.onNodeWithTag("studio-contact").performScrollTo().performClick()
        compose.onNodeWithTag("studio-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("studio-terms").performScrollTo().performClick()
        assertEquals(
            listOf(
                "https://videos.alummahnow.net/ar/help",
                "https://videos.alummahnow.net/ar/contact",
                "https://www.alummahnow.net/ar/privacy/",
                "https://www.alummahnow.net/ar/terms/"
            ),
            opened
        )
    }

    // --- Screenshots ---

    private val gate = @androidx.compose.runtime.Composable {
        CreatorTermsGate(saving = false, error = null, onOpenUrl = {}, onAgree = {})
    }

    @Test fun creatorTermsGateEnDark() = shoot("creator_terms_gate", Lang.EN, ThemeMode.DARK, content = gate)

    @Test fun creatorTermsGateEnLight() = shoot("creator_terms_gate", Lang.EN, ThemeMode.LIGHT, content = gate)

    @Test fun creatorTermsGateArDark() = shoot("creator_terms_gate", Lang.AR, ThemeMode.DARK, content = gate)

    @Test fun creatorTermsGateArLight() = shoot("creator_terms_gate", Lang.AR, ThemeMode.LIGHT, content = gate)

    @Test fun creatorTermsGateTabletEnDark() = shoot("creator_terms_gate", Lang.EN, ThemeMode.DARK, Device.TABLET, gate)

    @Test fun creatorTermsGateTabletArDark() = shoot("creator_terms_gate", Lang.AR, ThemeMode.DARK, Device.TABLET, gate)
}
