package net.alummahnow.studio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.studio.terms.CreatorTermsGate
import net.alummahnow.feature.studio.terms.CreatorTermsLoading
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Creator Terms gate on a real device: the tick, the button and where its links go. Calls no server. */
@RunWith(AndroidJUnit4::class)
class CreatorTermsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun gateNeedsTheTickAndOpensThisBuildsPages() {
        val opened = mutableListOf<String>()
        var agreed = 0
        compose.setContent {
            AunTheme {
                CreatorTermsGate(
                    saving = false,
                    error = null,
                    onOpenUrl = { opened += it },
                    onAgree = { agreed++ },
                    webHost = BuildConfig.WEB_HOST
                )
            }
        }
        compose.onNodeWithTag("creator-terms-agree").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("creator-terms-link").performScrollTo().performClick()
        compose.onNodeWithTag("creator-guidelines-link").performScrollTo().performClick()
        val language = if (java.util.Locale.getDefault().language == "ar") "ar" else "en"
        val www = BuildConfig.WEB_HOST.replaceFirst("videos.", "www.")
        assertEquals(
            listOf("https://$www/$language/terms/", "https://${BuildConfig.WEB_HOST}/$language/community-guidelines"),
            opened
        )
        compose.onNodeWithTag("creator-terms-checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("creator-terms-agree").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, agreed)
    }

    @Test
    fun loadingIsShown() {
        compose.setContent { AunTheme { CreatorTermsLoading() } }
        compose.onNodeWithTag("creator-terms-loading").assertIsDisplayed()
    }
}
