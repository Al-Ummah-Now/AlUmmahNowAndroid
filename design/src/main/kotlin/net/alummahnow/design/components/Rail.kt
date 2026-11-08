package net.alummahnow.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.FanousTokens

/** A titled horizontal rail of video cards. LazyRow mirrors automatically in RTL. */
@Composable
fun VideoRail(
    title: String,
    items: List<VideoCardData>,
    onItemClick: (VideoCardData) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 260.dp
) {
    Column(modifier = modifier.padding(vertical = FanousTokens.Spacing.sm)) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = FanousTokens.Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            items(items, key = { it.id }) { item ->
                VideoCard(item, onClick = { onItemClick(item) }, width = cardWidth)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
            .semantics { heading() }
    )
}
