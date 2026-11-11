package net.alummahnow.studio

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.alummahnow.feature.studio.R as StudioR

/** Studio sections. */
enum class StudioTab(val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.studio_tab_dashboard, Icons.Filled.Dashboard),
    CONTENT(StudioR.string.studio_tab_content, Icons.Filled.VideoLibrary),
    UPLOAD(StudioR.string.studio_tab_upload, Icons.Filled.CloudUpload),
    ANALYTICS(StudioR.string.studio_tab_analytics, Icons.Filled.Insights)
}

/** Bottom navigation on phones, a navigation rail on tablets and unfolded foldables. */
@Composable
fun StudioHome(
    tabs: List<StudioTab>,
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (StudioTab) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= RAIL_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { t ->
                        NavigationRailItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
                content(selected)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content(selected) }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = t == selected,
                            onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}

private val RAIL_WIDTH = 600.dp
