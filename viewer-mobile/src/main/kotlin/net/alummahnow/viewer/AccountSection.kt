package net.alummahnow.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.auth.AuthState

/** Account row in settings: sign in with the browser (PKCE), or show who is signed in with a sign-out button. */
@Composable
fun AccountSection(state: AuthState, signInAvailable: Boolean, signInFailed: Boolean, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    if (!signInAvailable && state is AuthState.SignedOut) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
        when (state) {
            is AuthState.SignedIn -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_signed_in_as), style = MaterialTheme.typography.bodyMedium)
                    // Emails are Latin even in the Arabic UI; let the text pick its own direction.
                    Text(
                        state.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)
                    )
                }
                OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.settings_sign_out)) }
            }
            AuthState.SignedOut -> {
                Text(
                    stringResource(if (signInFailed) R.string.settings_sign_in_failed else R.string.settings_sign_in_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (signInFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(onClick = onSignIn, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.settings_sign_in)) }
            }
        }
    }
}
