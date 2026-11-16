@file:Suppress("SpreadOperator") // vararg arguments are passed through to String.format

package net.alummahnow.feature.studio

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.design.format.aunString

/** A localised message for any failure from the Studio bridge. */
@StringRes
fun Throwable.studioMessage(): Int = when (this) {
    is CoreError.Network -> net.alummahnow.design.R.string.aun_error_network
    is CoreError.NotSignedIn -> R.string.studio_error_signed_out
    is CoreError.NotFound -> net.alummahnow.design.R.string.aun_error_not_found
    is StudioError.Forbidden -> R.string.studio_error_forbidden
    is StudioError.UpgradeRequired -> R.string.studio_error_upgrade
    is StudioError.NotSupported -> R.string.studio_error_not_supported
    is StudioError.RestoreExpired -> R.string.studio_error_restore_expired
    is StudioError.Invalid -> R.string.studio_error_invalid
    else -> net.alummahnow.design.R.string.aun_error_generic
}

/** Days a deleted video can be restored (api-videos soft delete). */
const val RESTORE_DAYS = 30

/** The numbers some Studio messages show; they are formatted with the app's digits (٠–٩ in Arabic). */
private fun messageArgs(@StringRes id: Int): Array<Any> = when (id) {
    R.string.studio_error_restore_expired, R.string.studio_delete_body_soft -> arrayOf(RESTORE_DAYS)
    else -> emptyArray()
}

/** A Studio message (for example from [studioMessage]) with its numbers. */
@Composable
fun studioText(@StringRes id: Int): String = aunString(id, *messageArgs(id))

/** [studioText] outside composition (snackbars). */
fun Resources.studioText(@StringRes id: Int): String = aunString(id, *messageArgs(id))
