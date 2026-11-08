package net.alummahnow.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.alummahnow.design.R
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.viewsLabel
import net.alummahnow.design.theme.FanousTokens

/** Display model for a video card. Features map core models into this so `:design` stays core-agnostic. */
data class VideoCardData(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val channelName: String?,
    val views: Long
)

/** Test tag on every clickable video card (instrumented tests find cards without depending on locale). */
const val VIDEO_CARD_TAG = "aun-video-card"

@Composable
fun VideoCard(
    data: VideoCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 260.dp
) {
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag(VIDEO_CARD_TAG)
            .padding(bottom = FanousTokens.Spacing.sm)
    ) {
        Thumbnail(data.thumbnailUrl, data.durationSeconds)
        Text(
            text = data.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = FanousTokens.Spacing.sm, start = FanousTokens.Spacing.xs, end = FanousTokens.Spacing.xs)
        )
        val meta = listOfNotNull(data.channelName?.takeIf { it.isNotBlank() }, viewsLabel(data.views)).joinToString(" • ")
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.xs)
        )
    }
}

@Composable
fun Thumbnail(url: String?, durationSeconds: Long?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(FanousTokens.NightDeep, FanousTokens.NightShallow, FanousTokens.Earth)))
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (durationSeconds != null) {
            val label = durationLabel(durationSeconds)
            val cd = stringResource(R.string.aun_duration_cd, label)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(FanousTokens.Spacing.sm)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = cd }
            )
        }
    }
}
