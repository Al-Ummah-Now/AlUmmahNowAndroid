package net.alummahnow.feature.studio.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/**
 * Sign-in. [onSignIn] opens the browser; [hasAccounts] adds "Add another account" (prompt=login)
 * and a way back when other accounts are already signed in.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onSignIn: () -> Unit,
    onAddAccount: (() -> Unit)?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
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
            ) {
                Image(
                    painter = painterResource(net.alummahnow.design.R.drawable.aun_logo),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
                Text(
                    stringResource(R.string.studio_signin_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    stringResource(R.string.studio_signin_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                val busy = state is SignInUiState.Completing || state is SignInUiState.InBrowser
                if (busy) {
                    CircularProgressIndicator(Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.studio_signin_working), style = MaterialTheme.typography.bodyMedium)
                }
                if (state is SignInUiState.Error) {
                    Text(
                        stringResource(state.message),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("signin-error").semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                Button(
                    onClick = onSignIn,
                    enabled = state !is SignInUiState.Completing,
                    modifier = Modifier.fillMaxWidth().testTag("signin-button")
                ) { Text(stringResource(if (onAddAccount != null) R.string.studio_signin_continue else R.string.studio_signin_button)) }
                if (onAddAccount != null) {
                    OutlinedButton(
                        onClick = onAddAccount,
                        enabled = state !is SignInUiState.Completing,
                        modifier = Modifier.fillMaxWidth().testTag("signin-add-account")
                    ) { Text(stringResource(R.string.studio_signin_add)) }
                }
                if (onBack != null) {
                    androidx.compose.material3.TextButton(onClick = onBack) {
                        Text(stringResource(net.alummahnow.design.R.string.aun_cancel))
                    }
                }
            }
        }
    }
}
