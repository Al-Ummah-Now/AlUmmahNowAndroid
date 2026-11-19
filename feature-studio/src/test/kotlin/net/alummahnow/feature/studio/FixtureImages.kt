package net.alummahnow.feature.studio

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler

/** Local title-card art for screenshot fixtures: a thumbnail URL "fixture:thumbs/<name>.jpg" loads a test resource (no network). */
internal const val FIXTURE_THUMBS = "fixture:thumbs/"

@OptIn(ExperimentalCoilApi::class)
private val fixtureImages = AsyncImagePreviewHandler { request ->
    val path = (request.data as? String)?.takeIf { it.startsWith("fixture:") }?.removePrefix("fixture:")
    path?.let { FixtureImagesLoader::class.java.getResourceAsStream("/$it") }
        ?.use { BitmapFactory.decodeStream(it) }
        ?.asImage()
}

private object FixtureImagesLoader

/** Renders [content] with fixture thumbnails drawn at once (Coil's preview path), as the screenshot tests need. */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun WithFixtureImages(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides fixtureImages, content = content)
}
