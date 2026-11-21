package net.alummahnow.feature.home

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import net.alummahnow.design.format.currentLocale

/**
 * Local title-card art for screenshot fixtures: a thumbnail URL "fixture:thumbs/<name>.jpg" loads a test resource (no
 * network). Each card exists per language (scripts/title-cards.py): <name>.jpg has the Arabic title and <name>.en.jpg the
 * English one, so a frame only shows art in its own language.
 */
internal const val FIXTURE_THUMBS = "fixture:thumbs/"

/** The resource for [data] in [language]: the English card unless the frame is Arabic. */
private fun fixtureResource(data: Any?, language: String): String? {
    val path = (data as? String)?.removePrefix("fixture:") ?: return null
    return if (language == "ar") path else path.removeSuffix(".jpg") + ".en.jpg"
}

@OptIn(ExperimentalCoilApi::class)
private fun fixtureImages(language: String) = AsyncImagePreviewHandler { request ->
    val bitmap = fixtureResource(request.data, language)
        ?.let { FixtureImagesLoader::class.java.getResourceAsStream("/$it") }
        ?.use { BitmapFactory.decodeStream(it) }
    requireNotNull(bitmap) { "No $language fixture image for ${request.data}: run scripts/title-cards.py" }.asImage()
}

private object FixtureImagesLoader

/**
 * Renders [content] with fixture thumbnails drawn at once (Coil's preview path), as the screenshot tests need. The
 * card's language is the composition's locale (the Robolectric qualifiers the test sets).
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun WithFixtureImages(content: @Composable () -> Unit) {
    val language = currentLocale().language
    val handler = remember(language) { fixtureImages(language) }
    CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler, content = content)
}
