package net.alummahnow.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.links.AppLink
import net.alummahnow.core.bridge.links.StudioSection
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.components.FanousHeaderBar
import net.alummahnow.design.components.FanousHeaderTitle
import net.alummahnow.design.format.currentLocale
import net.alummahnow.feature.passkey.PasskeyHost
import net.alummahnow.feature.passkey.PasskeyUiState
import net.alummahnow.feature.passkey.PasskeyViewModel
import net.alummahnow.feature.passkey.rememberPasskeyAuthenticator
import net.alummahnow.feature.studio.analytics.AnalyticsScreen
import net.alummahnow.feature.studio.analytics.AnalyticsViewModel
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.channels.AccountSheet
import net.alummahnow.feature.studio.channels.ChannelAvatar
import net.alummahnow.feature.studio.channels.ChannelPickerScreen
import net.alummahnow.feature.studio.channels.ChannelsUiState
import net.alummahnow.feature.studio.channels.ChannelsViewModel
import net.alummahnow.feature.studio.content.ContentRoute
import net.alummahnow.feature.studio.content.ContentViewModel
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardViewModel
import net.alummahnow.feature.studio.edit.VideoEditScreen
import net.alummahnow.feature.studio.edit.VideoEditViewModel
import net.alummahnow.feature.studio.signin.SignInScreen
import net.alummahnow.feature.studio.signin.SignInViewModel
import net.alummahnow.feature.studio.studioMessage
import net.alummahnow.feature.studio.studioText
import net.alummahnow.feature.upload.UploadRoute
import net.alummahnow.feature.upload.UploadViewModel

/** Studio's top level: sign-in, then the channel picker, then the channel's Studio. */
@Composable
fun StudioApp(
    container: AppContainer,
    redirects: Flow<String>,
    openInBrowser: (String) -> Boolean,
    links: Flow<AppLink.Studio> = emptyFlow()
) {
    val session = container.session
    val active by session.active.collectAsStateWithLifecycle()
    val accounts by session.accounts.collectAsStateWithLifecycle()
    val signIn: SignInViewModel = viewModel { SignInViewModel(session) }
    val signInState by signIn.state.collectAsStateWithLifecycle()

    val passkeys: PasskeyViewModel = viewModel { PasskeyViewModel(session) }
    val passkeyState by passkeys.state.collectAsStateWithLifecycle()
    val authenticator = rememberPasskeyAuthenticator()

    // A redirect may also be the re-sign-in "Add a passkey" asked for; the passkey model retries the add if so.
    LaunchedEffect(Unit) { redirects.collect { signIn.complete(it) { passkeys.signedIn(session.active.value) } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { signIn.browserClosed() }
    // An App Link waits here until the person is signed in and the link's channel is loaded.
    var pendingLink by remember { mutableStateOf<AppLink.Studio?>(null) }
    LaunchedEffect(Unit) { links.collect { pendingLink = it } }

    fun startSignIn(mode: SignInMode) {
        signIn.start(mode)?.let { url -> if (!openInBrowser(url)) signIn.noBrowser() }
    }

    PasskeyHost(
        passkeys,
        onOtherWay = { startSignIn(SignInMode.DEFAULT) },
        onSignInAgain = {
            // A fresh sign-in of the same person (prompt=login, max_age=900): the server checks auth_time.
            runCatching { session.beginStepUp(STEP_UP_MAX_AGE_S) }
                .onSuccess { url -> if (!openInBrowser(url)) signIn.noBrowser() }
                .onFailure { e -> AunLog.e("auth", "step-up could not start", e) }
        }
    )

    val current = active
    if (current == null) {
        SignInScreen(
            state = signInState,
            onSignIn = { startSignIn(SignInMode.DEFAULT) },
            onAddAccount = null,
            onBack = null,
            passkeySupported = authenticator.isSupported,
            passkeyWorking = passkeyState == PasskeyUiState.Working(adding = false),
            onPasskey = { passkeys.signIn(authenticator) }
        )
        return
    }

    val channels: ChannelsViewModel = viewModel { ChannelsViewModel(session, container.repository) }
    val channelsState by channels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var sheetOpen by remember { mutableStateOf(false) }
    val selected = (channelsState as? ChannelsUiState.Content)?.selected
    LaunchedEffect(pendingLink, channelsState) {
        val link = pendingLink ?: return@LaunchedEffect
        val loaded = channelsState as? ChannelsUiState.Content ?: return@LaunchedEffect
        val wanted = link.channelId ?: return@LaunchedEffect
        when {
            loaded.channels.none { it.id == wanted } -> pendingLink = null // not this account's channel
            loaded.selectedId != wanted -> channels.select(wanted)
        }
    }

    var tab by rememberSaveable(selected?.id) { mutableStateOf(StudioTab.DASHBOARD) }
    val wide = LocalConfiguration.current.screenWidthDp.dp >= RAIL_BREAKPOINT
    Scaffold(
        topBar = {
            StudioTopBar(
                title = if (selected == null) stringResource(R.string.app_name) else stringResource(tab.label),
                // Phones: identity at the end of the header. Large screens: in the rail's header instead.
                identity = if (wide && selected != null) null else selected,
                railAligned = wide && selected != null,
                onAccount = { sheetOpen = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        if (selected == null) {
            ChannelPickerScreen(channelsState, onSelect = channels::select, onRetry = channels::load, modifier = body)
        } else {
            var editing by rememberSaveable(selected.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(pendingLink, selected.id) {
                val link = pendingLink ?: return@LaunchedEffect
                if (link.channelId != null && link.channelId != selected.id) return@LaunchedEffect
                link.section?.let { tab = it.toTab() }
                link.videoId?.let { editing = it }
                pendingLink = null
            }
            val key = "${current.userId}-${selected.id}"
            val content: ContentViewModel = viewModel(key = "content-$key") { ContentViewModel(container.repository, selected.id) }
            val openVideo = editing
            if (openVideo != null) {
                val edit: VideoEditViewModel = viewModel(key = "edit-$key-$openVideo") {
                    VideoEditViewModel(container.repository, selected.id, openVideo)
                }
                val editState by edit.state.collectAsStateWithLifecycle()
                VideoEditScreen(editState, edit::update, edit::save, edit::load, onClose = {
                    editing = null
                    content.load()
                }, modifier = body)
            } else {
                StudioHome(
                    StudioTab.entries,
                    tab,
                    onSelect = { tab = it },
                    modifier = body,
                    railHeader = { IdentityButton(selected, onClick = { sheetOpen = true }) }
                ) { t ->
                    when (t) {
                        StudioTab.CONTENT -> ContentRoute(content, onEdit = { editing = it })
                        StudioTab.UPLOAD -> {
                            val upload: UploadViewModel = viewModel(key = "upload-$key") {
                                UploadViewModel(container.uploads, container.repository, selected.id, selected.name)
                            }
                            UploadRoute(upload)
                        }
                        StudioTab.ANALYTICS -> {
                            val analytics: AnalyticsViewModel =
                                viewModel(key = "analytics-$key") { AnalyticsViewModel(container.repository, selected.id) }
                            val analyticsState by analytics.state.collectAsStateWithLifecycle()
                            AnalyticsScreen(analyticsState, analytics::setRange, analytics::load)
                        }
                        StudioTab.DASHBOARD -> {
                            val dashboard: DashboardViewModel = viewModel(key = "dashboard-$key") {
                                DashboardViewModel(container.repository, selected.id)
                            }
                            val dashboardState by dashboard.state.collectAsStateWithLifecycle()
                            DashboardScreen(dashboardState, onRetry = dashboard::load)
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        AccountSheet(
            accounts = accounts,
            activeUserId = current.userId,
            channel = selected,
            onSwitchAccount = {
                sheetOpen = false
                runCatching { session.switchTo(it) }.onFailure { e -> AunLog.e("auth", "switch failed", e) }
            },
            onAddAccount = {
                sheetOpen = false
                startSignIn(SignInMode.ADD_ACCOUNT)
            },
            onSignOut = { userId ->
                sheetOpen = false
                scope.launch {
                    attempt("auth", "sign-out") { session.signOut(userId) }.fold(
                        onSuccess = { logoutUrl ->
                            container.forget(userId)
                            // End the browser session too, so the next sign-in asks for credentials.
                            openInBrowser(logoutUrl)
                        },
                        onFailure = { snackbar.showSnackbar(resources.studioText(it.studioMessage())) }
                    )
                }
            },
            onSwitchChannel = {
                sheetOpen = false
                channels.clearSelection()
            },
            onDismiss = { sheetOpen = false },
            onAddPasskey = if (authenticator.isSupported) {
                {
                    sheetOpen = false
                    passkeys.add(authenticator)
                }
            } else {
                null
            }
        )
    }
}

private fun StudioSection.toTab(): StudioTab = when (this) {
    StudioSection.DASHBOARD -> StudioTab.DASHBOARD
    StudioSection.CONTENT -> StudioTab.CONTENT
    StudioSection.ANALYTICS -> StudioTab.ANALYTICS
}

/** "Add a passkey" needs a sign-in within 15 minutes (the server's step_up_required window). */
private const val STEP_UP_MAX_AGE_S = 900

/**
 * The web header: the screen title and, on phones, the identity (the channel being acted as), which opens the
 * account sheet. On large screens the title starts on the rail's edge and identity lives in the rail header.
 */
@Composable
private fun StudioTopBar(title: String, identity: StudioChannel?, railAligned: Boolean, onAccount: () -> Unit) {
    FanousHeaderBar {
        if (railAligned) Spacer(Modifier.width(RAIL_WIDTH - 12.dp))
        FanousHeaderTitle(title, Modifier.weight(1f), large = true)
        if (identity != null) IdentityButton(identity, onClick = onAccount)
    }
}

/** The identity control: the channel's avatar, no ring (as the web header). Opens the account sheet. */
@Composable
private fun IdentityButton(channel: StudioChannel, onClick: () -> Unit) {
    val name = channel.localizedName(currentLocale().language)
    val label = stringResource(net.alummahnow.feature.studio.R.string.studio_account_menu_cd)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = "$label, $name" }
            .testTag("account-button"),
        contentAlignment = Alignment.Center
    ) { ChannelAvatar(channel, 36) }
}
