package net.alummahnow.feature.upload

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import net.alummahnow.design.format.aunString

/** An upload message with the numbers it shows, formatted with the app's digits (٠–٩ in Arabic). */
@Composable
fun uploadText(@StringRes id: Int): String = when (id) {
    R.string.upload_error_too_many -> aunString(id, UploadViewModel.MAX_FILES)
    R.string.upload_error_schedule_soon -> aunString(id, UploadViewModel.MIN_SCHEDULE_AHEAD.toMinutes())
    R.string.upload_error_title_long -> aunString(id, TitlePattern.MAX_TITLE)
    else -> aunString(id)
}
