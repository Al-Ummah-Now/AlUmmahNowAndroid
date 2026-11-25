package net.alummahnow.feature.studio.deletion

import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/**
 * Confirms deleting an account, in the app. Cancel takes focus first and there is always a way out: while a request is
 * out the button stops the deletion after it. The action needs the typed word, `DELETE` or «حذف».
 */
@Composable
fun DeleteAccountDialog(
    state: DeleteAccountUiState,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    initialTyped: String = "",
    nowMs: () -> Long = System::currentTimeMillis
) {
    val target = state.target ?: return
    val cancelFocus = remember { FocusRequester() }
    var typed by rememberSaveable(target.userId) { mutableStateOf(initialTyped) }
    val title = stringResource(R.string.studio_delete_title)
    val word = stringResource(R.string.studio_delete_word)
    val waitMinutes = minutesUntil(state.notBeforeMs, nowMs)
    val ready = state.action == DeleteAction.SIGN_IN || isDeleteWord(typed)

    val scroll = rememberScrollState()
    // What happens is said at the end: at a large font that is below the fold.
    LaunchedEffect(state.message, state.working, scroll.maxValue) {
        if (state.working || state.message != null) scroll.scrollTo(scroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .widthIn(max = 420.dp)
            .semantics { paneTitle = title }
            .testTag("delete-account-dialog"),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
                Text(
                    isolate(target.email?.takeIf { it.isNotBlank() } ?: target.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("delete-account-name")
                )
                Text(stringResource(R.string.studio_delete_body), style = MaterialTheme.typography.bodyMedium)
                ownedChannelsText(state.ownedChannels)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("delete-account-channels"))
                }
                Text(aunString(R.string.studio_delete_kept, PURGE_DAYS), style = MaterialTheme.typography.bodySmall)
                WordField(typed, word, enabled = !state.working) { typed = it }
                Status(state, waitMinutes)
            }
        },
        dismissButton = {
            var focused by remember { mutableStateOf(false) }
            TextButton(
                onClick = onClose,
                enabled = !state.stopping,
                border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                // Focusable by itself: a button alone takes no focus in touch mode, and TalkBack should start here.
                modifier = Modifier
                    .focusRequester(cancelFocus)
                    .onFocusChanged { focused = it.hasFocus }
                    .focusable()
                    .testTag("delete-account-cancel")
            ) {
                Text(
                    stringResource(
                        when {
                            state.stopping -> R.string.studio_delete_stopping
                            state.working -> R.string.studio_delete_stop
                            else -> net.alummahnow.design.R.string.aun_cancel
                        }
                    )
                )
            }
            LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = ready && !state.working && waitMinutes == null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (state.action == DeleteAction.SIGN_IN) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.testTag("delete-account-confirm")
            ) {
                Text(
                    stringResource(
                        when (state.action) {
                            DeleteAction.DELETE -> R.string.studio_delete_account
                            DeleteAction.SIGN_IN -> R.string.studio_delete_sign_in_action
                            DeleteAction.DELETE_ANYWAY -> R.string.studio_delete_anyway
                        }
                    )
                )
            }
        }
    )
}

/** The number when it is known, else the general sentence; nothing when the account owns none. */
@Composable
private fun ownedChannelsText(owned: Int?): String? = when {
    owned == null -> stringResource(R.string.studio_delete_channels)
    owned == 0 -> null
    else -> aunPlural(R.plurals.studio_delete_channels_count, owned, owned)
}

@Composable
private fun WordField(typed: String, word: String, enabled: Boolean, onTyped: (String) -> Unit) {
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        enabled = enabled,
        singleLine = true,
        label = { Text(aunString(R.string.studio_delete_prompt, isolate(word))) },
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledBorderColor = MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth().testTag("delete-account-input")
    )
}

@Composable
private fun Status(state: DeleteAccountUiState, waitMinutes: Int?) {
    if (state.working) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
            modifier = Modifier.testTag("delete-account-working").semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { STILL_ARC }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                when {
                    state.restarted -> stringResource(R.string.studio_delete_restarted)
                    state.passes > 0 -> aunString(R.string.studio_delete_pass, state.passes + 1)
                    else -> stringResource(R.string.studio_delete_working)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val message = state.message ?: return
    Column(
        Modifier
            .testTag("delete-account-message")
            .semantics { liveRegion = if (state.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)
    ) {
        val color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = color)
        if (waitMinutes != null) {
            Text(
                aunPlural(R.plurals.studio_delete_wait_minutes, waitMinutes, waitMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.testTag("delete-account-wait")
            )
        }
    }
}

/** Whole minutes left until [untilMs], counted down while the dialog is open; null once it may go on. */
@Composable
private fun minutesUntil(untilMs: Long, nowMs: () -> Long): Int? {
    var now by remember(untilMs) { mutableLongStateOf(nowMs()) }
    LaunchedEffect(untilMs) {
        while (untilMs > nowMs()) {
            delay(minOf(untilMs - nowMs(), TICK_MS))
            now = nowMs()
        }
    }
    val left = untilMs - now
    return if (left <= 0) null else ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
}

/**
 * The typed word: either one in either language, because a keyboard may have only one of them. Letter case, spaces
 * around it, and Arabic vowel marks, tatweel and direction marks anywhere in it don't count.
 */
internal fun isDeleteWord(input: String): Boolean {
    val word = buildString {
        input.forEach { ch ->
            val mark = ch == TATWEEL ||
                Character.getType(ch) == Character.FORMAT.toInt() ||
                (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() && Character.UnicodeBlock.of(ch) in ARABIC)
            if (!mark) append(ch)
        }
    }.trim()
    return word.equals("DELETE", ignoreCase = true) || word == "حذف"
}

private const val TATWEEL = 'ـ'
private val ARABIC =
    setOf(Character.UnicodeBlock.ARABIC, Character.UnicodeBlock.ARABIC_EXTENDED_A, Character.UnicodeBlock.ARABIC_SUPPLEMENT)

/** The sign-in record is purged this many days after the deletion (Auth, account-routes.ts). */
const val PURGE_DAYS = 30
private const val STILL_ARC = 0.72f
private const val MINUTE_MS = 60_000L
private const val TICK_MS = 5_000L
