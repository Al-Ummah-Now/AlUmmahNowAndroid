package net.alummahnow.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.SupportLinks
import net.alummahnow.core.bridge.studio.CreatorAccess
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

    private val prodHost = "videos.alummahnow.net"
    private val qaHost = "videos.qa.alummahnow.net"

    @Test
    fun `SupportLinks resolves English, Arabic, and QA host URLs`() {
        assertEquals("https://videos.alummahnow.net/en/help", SupportLinks.helpUrl("en", prodHost))
        assertEquals("https://videos.alummahnow.net/ar/help", SupportLinks.helpUrl("ar", prodHost))
        assertEquals("https://videos.alummahnow.net/en/contact", SupportLinks.contactUrl("en", prodHost))
        assertEquals("https://videos.alummahnow.net/ar/contact", SupportLinks.contactUrl("ar", prodHost))
        assertEquals("https://www.alummahnow.net/en/privacy/", SupportLinks.privacyUrl("en", prodHost))
        assertEquals("https://www.alummahnow.net/ar/privacy/", SupportLinks.privacyUrl("ar", prodHost))
        assertEquals("https://www.alummahnow.net/en/terms/", SupportLinks.termsUrl("en", prodHost))
        assertEquals("https://www.alummahnow.net/ar/terms/", SupportLinks.termsUrl("ar", prodHost))
        assertEquals("https://videos.alummahnow.net/ar/community-guidelines", SupportLinks.communityGuidelinesUrl("ar", prodHost))
        assertEquals("https://videos.qa.alummahnow.net/en/help", SupportLinks.helpUrl("en", qaHost))
        assertEquals("https://videos.qa.alummahnow.net/ar/contact", SupportLinks.contactUrl("ar", qaHost))
        assertEquals("https://videos.qa.alummahnow.net/en/community-guidelines", SupportLinks.communityGuidelinesUrl("en", qaHost))
        assertEquals("https://www.qa.alummahnow.net/ar/privacy/", SupportLinks.privacyUrl("ar", qaHost))
        assertEquals("https://www.qa.alummahnow.net/en/terms/", SupportLinks.termsUrl("en", qaHost))
    }

    @Test
    fun `CreatorTermsViewModel handles error, null compatibility, resume refresh, and POST`() = runTest {
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

            // Explicit false gates Upload
            repo.creatorAccessError = null
            repo.termsAccepted = false
            vm.load()
            advanceUntilIdle()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // ON_RESUME refresh while Required does not flash Loading
            vm.refresh()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)
            advanceUntilIdle()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // Failed POST (503 and 404) keeps Required with errorRes
            repo.acceptTermsError = CoreError.Http(503)
            vm.accept()
            advanceUntilIdle()
            val afterFail = vm.state.value as CreatorTermsUiState.Required
            assertEquals(R.string.studio_terms_error, afterFail.errorRes)
            assertEquals(false, afterFail.saving)

            repo.acceptTermsError = CoreError.NotFound("Not Found")
            vm.accept()
            advanceUntilIdle()
            val after404 = vm.state.value as CreatorTermsUiState.Required
            assertEquals(R.string.studio_terms_error, after404.errorRes)
            assertEquals(false, after404.saving)

            // Successful POST transitions to Accepted
            repo.acceptTermsError = null
            vm.accept()
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, vm.state.value)
            assertEquals(3, repo.acceptTermsCalls)

            // Mid-session server terms bump: Accepted -> Required on refresh()
            repo.termsAccepted = false
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // Server 403 terms_required on upload reopens gate via requireTerms()
            repo.termsAccepted = true
            vm.refresh()
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, vm.state.value)
            vm.requireTerms()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // Older server omitting termsAccepted (null) falls through to Accepted
            val compatRepo = FakeRepository().apply { termsAccepted = null }
            val compatVm = CreatorTermsViewModel(compatRepo)
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, compatVm.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `CreatorTermsViewModel ignores refresh while saving and ignores stale load after accept`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val staleLoad = CompletableDeferred<CreatorAccess>()
            var loadCount = 0
            val baseRepo = FakeRepository()
            val repo = object : net.alummahnow.core.bridge.studio.StudioRepository by baseRepo {
                override suspend fun creatorAccess(): CreatorAccess {
                    loadCount++
                    return if (loadCount == 2) {
                        staleLoad.await()
                    } else {
                        CreatorAccess(canUpload = true, canCreateChannel = true, tierName = "Creator", termsAccepted = false)
                    }
                }
            }
            val vm = CreatorTermsViewModel(repo)
            advanceUntilIdle()
            assertTrue(vm.state.value is CreatorTermsUiState.Required)

            // Start a background refresh that will resolve late with false
            vm.refresh()
            assertEquals(1, loadCount) // queued on dispatcher
            // Immediately accept; refresh() while saving is a no-op
            vm.accept()
            val savingState = vm.state.value as CreatorTermsUiState.Required
            assertTrue(savingState.saving)
            vm.refresh() // ignored while saving
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, vm.state.value)

            // Stale load from before accept completes with termsAccepted = false -> must NOT revert Accepted
            staleLoad.complete(CreatorAccess(canUpload = true, canCreateChannel = true, tierName = "Creator", termsAccepted = false))
            advanceUntilIdle()
            assertEquals(CreatorTermsUiState.Accepted, vm.state.value)
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
                    onAgree = { agreed++ },
                    webHost = prodHost
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
                    onOpenUrl = { opened += it },
                    webHost = prodHost
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
        CreatorTermsGate(saving = false, error = null, onOpenUrl = {}, onAgree = {}, webHost = prodHost)
    }

    private val gateFont200 = @androidx.compose.runtime.Composable {
        val current = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale = 2f)) {
            CreatorTermsGate(saving = false, error = null, onOpenUrl = {}, onAgree = {}, webHost = prodHost)
        }
    }

    private val gateChecked = @androidx.compose.runtime.Composable {
        CreatorTermsGate(saving = false, error = null, onOpenUrl = {}, onAgree = {}, webHost = prodHost, initialChecked = true)
    }

    private val gateSaving = @androidx.compose.runtime.Composable {
        CreatorTermsGate(saving = true, error = null, onOpenUrl = {}, onAgree = {}, webHost = prodHost, initialChecked = true)
    }

    private val gateError = @androidx.compose.runtime.Composable {
        CreatorTermsGate(
            saving = false,
            error = androidx.compose.ui.res.stringResource(R.string.studio_terms_error),
            onOpenUrl = {},
            onAgree = {},
            webHost = prodHost,
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
                    onOpenUrl = {},
                    webHost = prodHost
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

    @Test fun creatorTermsGateFailedEnLight() = shoot("creator_terms_gate_failed", Lang.EN, ThemeMode.LIGHT, content = gateFailed)

    @Test fun creatorTermsGateFailedArDark() = shoot("creator_terms_gate_failed", Lang.AR, ThemeMode.DARK, content = gateFailed)

    @Test fun creatorTermsGateFailedArLight() = shoot("creator_terms_gate_failed", Lang.AR, ThemeMode.LIGHT, content = gateFailed)

    @Test fun creatorTermsGateFont200EnDark() = shoot("creator_terms_gate_font200", Lang.EN, ThemeMode.DARK, content = gateFont200)

    @Test fun creatorTermsGateFont200ArLight() = shoot("creator_terms_gate_font200", Lang.AR, ThemeMode.LIGHT, content = gateFont200)

    @Test fun accountSheetLinksEnDark() = shoot("account_sheet_links", Lang.EN, ThemeMode.DARK, content = accountSheetWithLinks)

    @Test fun accountSheetLinksArLight() = shoot("account_sheet_links", Lang.AR, ThemeMode.LIGHT, content = accountSheetWithLinks)
}
