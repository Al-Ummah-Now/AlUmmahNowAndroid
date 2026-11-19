package net.alummahnow.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.alummahnow.design.components.fanousLayers
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color
import net.alummahnow.feature.studio.R as StudioR

/** Studio sections. */
enum class StudioTab(val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.studio_tab_dashboard, Icons.Filled.Dashboard),
    CONTENT(StudioR.string.studio_tab_content, Icons.Filled.VideoLibrary),
    UPLOAD(StudioR.string.studio_tab_upload, Icons.Filled.CloudUpload),
    ANALYTICS(StudioR.string.studio_tab_analytics, Icons.Filled.Insights)
}

/** Width at which phones switch from the bottom bar to the rail. */
val RAIL_BREAKPOINT = 600.dp

/** Width of the rail: the header's leading column matches it, so the title starts on the rail's edge. */
val RAIL_WIDTH = 96.dp

/** Item colours: the gold pill (`primary`) behind the active icon, `navActiveText`-like gold label, muted rest. */
@Composable
private fun itemColors(): Pair<NavigationBarItemColors, NavigationRailItemColors> {
    val f = LocalFanous.current
    val activeLabel = if (f.dark) f.colors.primaryHover.color else f.colors.link.color
    val rest = f.colors.textSecondary.color
    val pill = f.colors.primary.color
    val onPill = f.colors.onPrimary.color
    return NavigationBarItemDefaults.colors(
        selectedIconColor = onPill,
        selectedTextColor = activeLabel,
        indicatorColor = pill,
        unselectedIconColor = rest,
        unselectedTextColor = rest
    ) to NavigationRailItemDefaults.colors(
        selectedIconColor = onPill,
        selectedTextColor = activeLabel,
        indicatorColor = pill,
        unselectedIconColor = rest,
        unselectedTextColor = rest
    )
}

/**
 * Bottom navigation on phones, a rail on tablets and unfolded foldables. The rail sits under the full-width web
 * header (one straight seam: the header's border and gold rule run across it), on the theme's `rail` material with
 * its edge line; its header slot holds the identity (the only identity control on large screens).
 */
@Composable
fun StudioHome(
    tabs: List<StudioTab>,
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    modifier: Modifier = Modifier,
    railHeader: @Composable () -> Unit = {},
    content: @Composable (StudioTab) -> Unit
) {
    val (barColors, railColors) = itemColors()
    val f = LocalFanous.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= RAIL_BREAKPOINT) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight()
                        .fanousLayers(f.material.rail, RectangleShape, shadows = f.material.railEdge),
                    containerColor = Color.Transparent,
                    header = { railHeader() }
                ) {
                    tabs.forEach { t ->
                        NavigationRailItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            colors = railColors,
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
                Box(Modifier.weight(1f)) { content(selected) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content(selected) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(f.colors.divider.color))
                NavigationBar(
                    containerColor = f.colors.surface.color,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            colors = barColors,
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}
