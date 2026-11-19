package net.alummahnow.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.LocalAunChrome
import net.alummahnow.feature.studio.R as StudioR

/** Studio sections. */
enum class StudioTab(val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.studio_tab_dashboard, Icons.Filled.Dashboard),
    CONTENT(StudioR.string.studio_tab_content, Icons.Filled.VideoLibrary),
    UPLOAD(StudioR.string.studio_tab_upload, Icons.Filled.CloudUpload),
    ANALYTICS(StudioR.string.studio_tab_analytics, Icons.Filled.Insights)
}

/**
 * Bottom navigation on phones, a navigation rail on tablets and unfolded foldables. Both sit on the Studio chrome
 * surface ([LocalAunChrome]); on tablets the rail continues straight down from the header (see [StudioScaffold]) and
 * a divider edges the page along the top and the inline-start side, so header + rail read as one frame.
 */
@Composable
fun StudioHome(
    tabs: List<StudioTab>,
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (StudioTab) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val chrome = LocalAunChrome.current
        if (maxWidth >= RAIL_BREAKPOINT) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = chrome.surface,
                    // The header above already handles the status bar: the rail starts flush under it.
                    windowInsets = WindowInsets(0),
                    modifier = Modifier.width(RAIL_COLUMN).testTag("studio-rail")
                ) {
                    tabs.forEach { t ->
                        NavigationRailItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            // Selected label = onSurface (the brightest text), never Earth `secondary` (~2.5:1 on night).
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .drawBehind {
                            val w = 1.dp.toPx()
                            drawRect(chrome.divider, size = Size(size.width, w))
                            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                            drawRect(chrome.divider, topLeft = Offset(x, 0f), size = Size(w, size.height))
                        }
                ) { content(selected) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content(selected) }
                NavigationBar(containerColor = chrome.surface, modifier = Modifier.testTag("studio-bottom-bar")) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            // Selected label = onSurface (the brightest text), never Earth `secondary` (~2.5:1 on night).
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}
