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
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.channels.AccountSheet
import net.alummahnow.feature.studio.channels.ChannelAvatar
import net.alummahnow.feature.studio.channels.ChannelPickerScreen
import net.alummahnow.feature.studio.channels.ChannelsUiState
import net.alummahnow.feature.studio.channels.ChannelsViewModel
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardViewModel
import net.alummahnow.feature.studio.signin.SignInScreen
import net.alummahnow.feature.studio.signin.SignInViewModel
import net.alummahnow.feature.studio.studioMessage

/** Studio's top level: sign-in, then the channel picker, then the channel's Studio. */
@Composable
fun StudioApp(container: AppContainer, redirects: Flow<String>, openInBrowser: (String) -> Boolean) {
    val session = container.session
    val active by session.active.collectAsStateWithLifecycle()
    val accounts by session.accounts.collectAsStateWithLifecycle()
    val signIn: SignInViewModel = viewModel { SignInViewModel(session) }
    val signInState by signIn.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { redirects.collect { signIn.complete(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { signIn.browserClosed() }

    fun startSignIn(mode: SignInMode) {
        signIn.start(mode)?.let { url -> if (!openInBrowser(url)) signIn.noBrowser() }
    }

    val current = active
    if (current == null) {
        SignInScreen(state = signInState, onSignIn = { startSignIn(SignInMode.DEFAULT) }, onAddAccount = null, onBack = null)
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
            val dashboard: DashboardViewModel = viewModel(key = "dashboard-${current.userId}-${selected.id}") {
                DashboardViewModel(container.repository, selected.id)
            }
            val dashboardState by dashboard.state.collectAsStateWithLifecycle()
            DashboardScreen(selected, dashboardState, onRetry = dashboard::load, modifier = body)
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
            onDismiss = { sheetOpen = false }
        )
    }
}

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
