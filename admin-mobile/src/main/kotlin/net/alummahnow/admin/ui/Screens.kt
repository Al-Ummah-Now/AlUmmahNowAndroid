package net.alummahnow.admin.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.alummahnow.admin.R
import net.alummahnow.admin.push.PushState
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.passkey.PasskeySignInOptions
import net.alummahnow.feature.passkey.R as PasskeyR

/** The smallest touch target on every control (AUN-ADMIN-NATIVE: 48 dp). */
val MinTarget = 48.dp

@Composable
private fun CenteredPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(FanousTokens.Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)
            ) { content() }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Logo() {
    Image(painterResource(net.alummahnow.design.R.drawable.aun_logo), contentDescription = null, modifier = Modifier.size(96.dp))
}

/**
 * Sign-in for staff: "Sign in with a passkey" (the OS passkey sheet, no browser) or "Sign in another way" ([onSignIn],
 * the browser). A device without a secure lock screen cannot sign in.
 */
@Composable
fun AdminSignInScreen(
    state: SignInState,
    deviceSecure: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    passkeySupported: Boolean = false,
    passkeyWorking: Boolean = false,
    onPasskey: () -> Unit = {}
) {
    CenteredPage(modifier) {
        Logo()
        Heading(stringResource(R.string.signin_title))
        Text(stringResource(R.string.signin_body), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (!deviceSecure) {
            Text(
                stringResource(R.string.signin_needs_lock),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("needs-lock")
            )
        }
        when (state) {
            SignInState.Completing -> CircularProgressIndicator()
            is SignInState.Error -> Text(
                stringResource(state.message),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            else -> Unit
        }
        PasskeySignInOptions(
            supported = passkeySupported,
            enabled = deviceSecure && state != SignInState.Completing && state != SignInState.InBrowser,
            working = passkeyWorking,
            fallbackLabel = stringResource(R.string.signin_button),
            onPasskey = onPasskey,
            onOtherWay = onSignIn,
            otherWayTag = "sign-in"
        )
    }
}

/** Shown on open and after 5 minutes in the background until the device unlock succeeds. */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    CenteredPage(modifier) {
        Logo()
        Heading(stringResource(R.string.lock_title))
        Text(stringResource(R.string.lock_body), textAlign = TextAlign.Center)
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("unlock")) {
            Text(stringResource(R.string.lock_button))
        }
    }
}

/** Someone without a staff role. */
@Composable
fun NotStaffScreen(onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    CenteredPage(modifier) {
        Logo()
        Heading(stringResource(R.string.not_staff_title))
        Text(stringResource(R.string.not_staff_body), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("sign-out")) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
fun ProfileErrorScreen(offline: Boolean, onRetry: () -> Unit, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    CenteredPage(modifier) {
        Heading(stringResource(if (offline) R.string.error_offline else R.string.error_generic))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = onSignOut, modifier = Modifier.heightIn(min = MinTarget)) { Text(stringResource(R.string.sign_out)) }
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = FanousTokens.Spacing.md))
    }
}

/** Account, Light/Dark (no System option), language, alert status and sign out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    name: String?,
    dark: Boolean,
    language: String,
    push: PushState?,
    onDark: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    onAddPasskey: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SettingsContent(name, dark, language, push, onDark, onLanguage, onSignOut, onAddPasskey)
    }
}

@Composable
fun SettingsContent(
    name: String?,
    dark: Boolean,
    language: String,
    push: PushState?,
    onDark: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onSignOut: () -> Unit,
    onAddPasskey: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(
            rememberScrollState()
        ).padding(horizontal = FanousTokens.Spacing.xl, vertical = FanousTokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
    ) {
        if (name != null) Text(stringResource(R.string.settings_signed_in_as, name), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics {
                heading()
            }
        )
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_theme_light), !dark, { onDark(false) }, "theme-light")
            RadioRow(stringResource(R.string.settings_theme_dark), dark, { onDark(true) }, "theme-dark")
        }
        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics {
                heading()
            }
        )
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_language_en), language == "en", { onLanguage("en") }, "lang-en")
            RadioRow(stringResource(R.string.settings_language_ar), language == "ar", { onLanguage("ar") }, "lang-ar")
        }
        Text(
            stringResource(R.string.settings_alerts),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics {
                heading()
            }
        )
        Text(
            stringResource(
                when (push) {
                    PushState.ON -> R.string.alerts_on
                    PushState.NOT_CONFIGURED -> R.string.alerts_not_configured
                    PushState.BLOCKED -> R.string.alerts_blocked
                    PushState.FAILED -> R.string.alerts_failed
                    null -> R.string.alerts_checking
                }
            ),
            modifier = Modifier.testTag("alerts-status")
        )
        HorizontalDivider()
        if (onAddPasskey != null) {
            Text(stringResource(PasskeyR.string.passkey_explainer), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onAddPasskey, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("add-passkey")) {
                Text(stringResource(PasskeyR.string.passkey_add))
            }
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("sign-out")) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

/**
 * The in-app confirm sheet for actions that cannot be undone (ban, remove, suspend). Never a system dialog. Phase 1
 * has no such action yet; phases 2–3 use it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { ConfirmContent(title, body, confirmLabel, onConfirm, onDismiss) }
}

@Composable
fun ConfirmContent(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(FanousTokens.Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(body)
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("confirm"),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text(confirmLabel) }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** Whether the device has a validated network now (decisions are never queued offline). */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnline(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = isOnline(context)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
