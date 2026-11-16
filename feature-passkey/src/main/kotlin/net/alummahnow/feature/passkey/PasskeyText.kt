package net.alummahnow.feature.passkey

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import net.alummahnow.design.format.aunString

/** Passkeys an account can hold (Auth limit). */
const val MAX_PASSKEYS = 10

/** A passkey message with the numbers it shows, formatted with the app's digits (٠–٩ in Arabic). */
@Composable
fun passkeyText(@StringRes id: Int): String = when (id) {
    R.string.passkey_limit -> aunString(id, MAX_PASSKEYS)
    else -> aunString(id)
}
