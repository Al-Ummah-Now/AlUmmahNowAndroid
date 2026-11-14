package net.alummahnow.feature.passkey

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.alummahnow.core.bridge.auth.passkey.PasskeyAuthenticator
import net.alummahnow.design.theme.FanousTokens

/** The platform passkey API for this screen. Tests provide a fake; apps keep the default (Credential Manager). */
val LocalPasskeyAuthenticator = staticCompositionLocalOf<(Context) -> PasskeyAuthenticator> { { CredentialManagerAuthenticator(it) } }

@Composable
fun rememberPasskeyAuthenticator(): PasskeyAuthenticator {
    val context = LocalContext.current
    val factory = LocalPasskeyAuthenticator.current
    return remember(context, factory) { factory(context) }
}

private val MinTarget = 48.dp

/**
 * The sign-in choices: "Sign in with a passkey" (the OS sheet, no browser), the explainer, and "Sign in another way"
 * (the existing browser sign-in, unchanged). Where passkeys can't work at all, only [fallbackLabel] is shown, which
 * opens the browser sign-in. [otherWayTag] tags whichever control opens the browser (the apps keep their existing tags).
 */
@Composable
fun PasskeySignInOptions(
    supported: Boolean,
    enabled: Boolean,
    working: Boolean,
    fallbackLabel: String,
    onPasskey: () -> Unit,
    onOtherWay: () -> Unit,
    modifier: Modifier = Modifier,
    otherWayTag: String = "signin-other-way"
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        if (!supported) {
            Button(
                onClick = onOtherWay,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag(otherWayTag)
            ) { Text(fallbackLabel) }
            return@Column
        }
        if (working) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text(
                stringResource(R.string.passkey_working),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Button(
            onClick = onPasskey,
            enabled = enabled && !working,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("signin-passkey")
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.passkey_sign_in), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        Text(
            stringResource(R.string.passkey_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(
            onClick = onOtherWay,
            enabled = enabled && !working,
            modifier = Modifier.heightIn(min = MinTarget).testTag(otherWayTag)
        ) { Text(stringResource(R.string.passkey_sign_in_other)) }
    }
}

/**
 * The passkey dialogs (in-app, never a system dialog) for [vm], and the automatic retry of "Add a passkey" after the
 * re-sign-in. [onOtherWay] opens the browser sign-in; [onSignInAgain] opens the app's step-up sign-in.
 */
@Composable
fun PasskeyHost(vm: PasskeyViewModel, onOtherWay: () -> Unit, onSignInAgain: () -> Unit, onSignedIn: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val authenticator = rememberPasskeyAuthenticator()
    LaunchedEffect(state) { if (state == PasskeyUiState.RetryAdd) vm.add(authenticator) }
    PasskeyDialog(
        state = state,
        onDismiss = vm::dismiss,
        onRetrySignIn = { vm.signIn(authenticator) { onSignedIn() } },
        onOtherWay = {
            vm.dismiss()
            onOtherWay()
        },
        onSignInAgain = {
            vm.signInAgainStarted()
            onSignInAgain()
        }
    )
}

/** One dialog per state; nothing for idle, working (the OS sheet is up) or a cancel. */
@Composable
fun PasskeyDialog(
    state: PasskeyUiState,
    onDismiss: () -> Unit,
    onRetrySignIn: () -> Unit,
    onOtherWay: () -> Unit,
    onSignInAgain: () -> Unit
) {
    when (state) {
        is PasskeyUiState.Problem -> {
            val p = state.problem
            if (p.offerOtherWay) {
                Dialog(
                    title = stringResource(p.title),
                    body = stringResource(p.body),
                    confirm = stringResource(R.string.passkey_sign_in_other) to onOtherWay,
                    dismiss = if (p == PasskeyProblem.NO_PASSKEY || p == PasskeyProblem.SIGN_IN_UNSUPPORTED) {
                        stringResource(net.alummahnow.design.R.string.aun_cancel) to onDismiss
                    } else {
                        stringResource(R.string.passkey_try_again) to {
                            onDismiss()
                            onRetrySignIn()
                        }
                    },
                    onDismissRequest = onDismiss
                )
            } else {
                Dialog(stringResource(p.title), stringResource(p.body), stringResource(R.string.passkey_ok) to onDismiss, null, onDismiss)
            }
        }
        PasskeyUiState.NeedsSignInAgain -> Dialog(
            title = stringResource(R.string.passkey_add),
            body = stringResource(R.string.passkey_stale),
            confirm = stringResource(R.string.passkey_sign_in_again) to onSignInAgain,
            dismiss = stringResource(net.alummahnow.design.R.string.aun_cancel) to onDismiss,
            onDismissRequest = onDismiss
        )
        PasskeyUiState.Added -> Dialog(
            title = stringResource(R.string.passkey_add),
            body = stringResource(R.string.passkey_added),
            confirm = stringResource(R.string.passkey_ok) to onDismiss,
            dismiss = null,
            onDismissRequest = onDismiss
        )
        else -> Unit
    }
}

@Composable
private fun Dialog(
    title: String,
    body: String,
    confirm: Pair<String, () -> Unit>,
    dismiss: Pair<String, () -> Unit>?,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.semantics { paneTitle = title }.testTag("passkey-dialog"),
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.testTag("passkey-dialog-body")) },
        confirmButton = {
            TextButton(onClick = confirm.second, modifier = Modifier.heightIn(min = MinTarget).testTag("passkey-dialog-confirm")) {
                Text(confirm.first)
            }
        },
        dismissButton = dismiss?.let { (label, action) ->
            {
                TextButton(onClick = action, modifier = Modifier.heightIn(min = MinTarget).testTag("passkey-dialog-dismiss")) {
                    Text(label)
                }
            }
        }
    )
}
