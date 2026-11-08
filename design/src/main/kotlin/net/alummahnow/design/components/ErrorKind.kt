package net.alummahnow.design.components

import androidx.annotation.StringRes
import net.alummahnow.design.R

/** UI-level error categories; features map core errors to these so messages stay localised. */
enum class ErrorKind(@param:StringRes val message: Int) {
    NETWORK(R.string.aun_error_network),
    NOT_FOUND(R.string.aun_error_not_found),
    GENERIC(R.string.aun_error_generic)
}
