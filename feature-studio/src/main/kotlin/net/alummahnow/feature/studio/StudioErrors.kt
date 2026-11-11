package net.alummahnow.feature.studio

import androidx.annotation.StringRes
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.StudioError

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
