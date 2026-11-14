package net.alummahnow.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioChannel
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
import net.alummahnow.feature.upload.UploadRoute
import net.alummahnow.feature.upload.UploadViewModel

/** Studio's top level: sign-in, then the channel picker, then the channel's Studio. */
@Composable
fun StudioApp(container: AppContainer, redirects: Flow<String>, openInBrowser: (String) -> Boolean) {
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

    Scaffold(
        topBar = { StudioTopBar(selected, onAccount = { sheetOpen = true }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        if (selected == null) {
            ChannelPickerScreen(channelsState, onSelect = channels::select, onRetry = channels::load, modifier = body)
        } else {
            var tab by rememberSaveable(selected.id) { mutableStateOf(StudioTab.DASHBOARD) }
            var editing by rememberSaveable(selected.id) { mutableStateOf<String?>(null) }
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
                StudioHome(StudioTab.entries, tab, onSelect = { tab = it }, modifier = body) { t ->
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
                            DashboardScreen(selected, dashboardState, onRetry = dashboard::load)
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
                        onFailure = { snackbar.showSnackbar(resources.getString(it.studioMessage())) }
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

/** "Add a passkey" needs a sign-in within 15 minutes (the server's step_up_required window). */
private const val STEP_UP_MAX_AGE_S = 900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(channel: StudioChannel?, onAccount: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel != null) {
                    ChannelAvatar(channel, 32)
                    Spacer(Modifier.width(12.dp))
                    Text(channel.localizedName(currentLocale().language), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onAccount, modifier = Modifier.testTag("account-button")) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = stringResource(net.alummahnow.feature.studio.R.string.studio_account_menu_cd)
                )
            }
        }
    )
}
