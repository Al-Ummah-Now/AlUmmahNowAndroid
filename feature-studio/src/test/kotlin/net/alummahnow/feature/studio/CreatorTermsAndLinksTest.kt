package net.alummahnow.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.SupportLinks
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheet
import net.alummahnow.feature.studio.channels.AccountSheetContent
import net.alummahnow.feature.studio.terms.CreatorTermsError
import net.alummahnow.feature.studio.terms.CreatorTermsGate
import net.alummahnow.feature.studio.terms.CreatorTermsUiState
import net.alummahnow.feature.studio.terms.CreatorTermsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `CreatorTermsViewModel fails closed on loading, error, and null termsAccepted, and unlocks only on POST success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = FakeRepository().apply {
                creatorAccessError = CoreError.Network(IOException("offline"))
            }
            val vm = CreatorTermsViewModel(repo)
            assertEquals(CreatorTermsUiState.Loading, vm.state.value)
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Failed, vm.state.value)

            // Null termsAccepted also fails closed to Required
            repo.creatorAccessError = null
            repo.termsAccepted = null
            vm.load()
            advanceUntilIdle()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // Failed POST keeps Required with errorRes
            repo.termsAccepted = false
            repo.acceptTermsError = CoreError.Http(503)
            vm.load()
            advanceUntilIdle()
            vm.accept()
            advanceUntilIdle()
            val afterFail = vm.state.value as CreatorTermsUiState.Required
            assertEquals(R.string.studio_terms_error, afterFail.errorRes)
            assertEquals(false, afterFail.saving)

            // Successful POST transitions to Accepted
            repo.acceptTermsError = null
            vm.accept()
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, vm.state.value)
            assertEquals(2, repo.acceptTermsCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `CreatorTermsGate exposes toggleable checkbox semantics and requires check before agreeing`() {
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
        compose.onNodeWithTag("creator-terms-checkbox").assertIsOff()
        compose.onNodeWithTag("creator-terms-agree").assertIsNotEnabled()
        compose.onNodeWithTag("creator-terms-link").performClick()
        compose.onNodeWithTag("creator-guidelines-link").performClick()
        assertEquals(
            listOf("https://www.alummahnow.net/en/terms/", "https://videos.alummahnow.net/en/community-guidelines"),
            opened
        )
        compose.onNodeWithTag("creator-terms-checkbox").performClick()
        compose.onNodeWithTag("creator-terms-checkbox").assertIsOn()
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

    private val gateChecked = @androidx.compose.runtime.Composable {
        CreatorTermsGate(saving = false, error = null, onOpenUrl = {}, onAgree = {}, initialChecked = true)
    }

    private val gateSaving = @androidx.compose.runtime.Composable {
        CreatorTermsGate(saving = true, error = null, onOpenUrl = {}, onAgree = {}, initialChecked = true)
    }

    private val gateError = @androidx.compose.runtime.Composable {
        CreatorTermsGate(
            saving = false,
            error = androidx.compose.ui.res.stringResource(R.string.studio_terms_error),
            onOpenUrl = {},
            onAgree = {},
            initialChecked = true
        )
    }

    private val gateFailed = @androidx.compose.runtime.Composable {
        CreatorTermsError(onRetry = {})
    }

    private val accountSheetWithLinks = @androidx.compose.runtime.Composable {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                AccountSheetContent(
                    accounts = listOf(sampleAccount),
                    activeUserId = sampleAccount.userId,
                    channel = sampleChannels[0],
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onRequestSignOut = {},
                    onSwitchChannel = {},
                    onDismiss = {},
                    onOpenUrl = {}
                )
            }
        }
    }

    @Test fun creatorTermsGateEnDark() = shoot("creator_terms_gate", Lang.EN, ThemeMode.DARK, content = gate)

    @Test fun creatorTermsGateEnLight() = shoot("creator_terms_gate", Lang.EN, ThemeMode.LIGHT, content = gate)

    @Test fun creatorTermsGateArDark() = shoot("creator_terms_gate", Lang.AR, ThemeMode.DARK, content = gate)

    @Test fun creatorTermsGateArLight() = shoot("creator_terms_gate", Lang.AR, ThemeMode.LIGHT, content = gate)

    @Test fun creatorTermsGateTabletEnDark() = shoot("creator_terms_gate", Lang.EN, ThemeMode.DARK, Device.TABLET, gate)

    @Test fun creatorTermsGateTabletEnLight() = shoot("creator_terms_gate", Lang.EN, ThemeMode.LIGHT, Device.TABLET, gate)

    @Test fun creatorTermsGateTabletArDark() = shoot("creator_terms_gate", Lang.AR, ThemeMode.DARK, Device.TABLET, gate)

    @Test fun creatorTermsGateTabletArLight() = shoot("creator_terms_gate", Lang.AR, ThemeMode.LIGHT, Device.TABLET, gate)

    @Test fun creatorTermsGateCheckedEnDark() = shoot("creator_terms_gate_checked", Lang.EN, ThemeMode.DARK, content = gateChecked)

    @Test fun creatorTermsGateCheckedEnLight() = shoot("creator_terms_gate_checked", Lang.EN, ThemeMode.LIGHT, content = gateChecked)

    @Test fun creatorTermsGateSavingEnDark() = shoot("creator_terms_gate_saving", Lang.EN, ThemeMode.DARK, content = gateSaving)

    @Test fun creatorTermsGateErrorEnDark() = shoot("creator_terms_gate_error", Lang.EN, ThemeMode.DARK, content = gateError)

    @Test fun creatorTermsGateErrorArLight() = shoot("creator_terms_gate_error", Lang.AR, ThemeMode.LIGHT, content = gateError)

    @Test fun creatorTermsGateFailedEnDark() = shoot("creator_terms_gate_failed", Lang.EN, ThemeMode.DARK, content = gateFailed)

    @Test fun accountSheetLinksEnDark() = shoot("account_sheet_links", Lang.EN, ThemeMode.DARK, content = accountSheetWithLinks)

    @Test fun accountSheetLinksArLight() = shoot("account_sheet_links", Lang.AR, ThemeMode.LIGHT, content = accountSheetWithLinks)
}
