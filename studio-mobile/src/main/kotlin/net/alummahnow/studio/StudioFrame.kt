package net.alummahnow.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalAunChrome
import net.alummahnow.feature.studio.channels.ChannelAvatar
import net.alummahnow.feature.studio.channels.roleLabel

/** Width of the navigation rail column (Material 3 rail container); the header's avatar is centred over it. */
internal val RAIL_COLUMN = 80.dp

/** Rail (tablet / unfolded) or bottom bar (phone): the same breakpoint everywhere in the Studio frame. */
internal val RAIL_BREAKPOINT = 600.dp

@Composable
internal fun isRailLayout(): Boolean = LocalConfiguration.current.screenWidthDp.dp >= RAIL_BREAKPOINT

/**
 * The Studio frame: one chrome surface (Fawanees `surface`) for the header and the navigation.
 *
 * - Tablet: the header spans the full width and the rail continues from it on the same surface, so header + rail read
 *   as one L-shaped frame around the page (no step or seam). The header stays flat; a divider edges the page instead.
 * - Phone: the header and the bottom bar share the surface; the header lifts (raised surface + low elevation) once
 *   the page scrolls under it.
 *
 * [railBelow] is true when a rail sits under the header's leading column (a channel is open, not the editor).
 * [scrollResetKey] re-flattens the phone header when the page changes (e.g. another tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioScaffold(
    channel: StudioChannel?,
    railBelow: Boolean,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    scrollResetKey: Any? = null,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val rail = isRailLayout()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(scrollResetKey) { scroll.state.contentOffset = 0f }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            StudioTopBar(
                channel = channel,
                alignToRail = rail && railBelow,
                lifted = !rail && scroll.state.overlappedFraction > 0.01f,
                onAccount = onAccount
            )
        },
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

/**
 * The header. Identity lives here only (owner rule): the active channel's avatar, name and your role on it.
 * With a rail below, the avatar is centred over the rail column and the title starts where the page content does.
 */
@Composable
internal fun StudioTopBar(channel: StudioChannel?, alignToRail: Boolean, lifted: Boolean, onAccount: () -> Unit) {
    val chrome = LocalAunChrome.current
    val color by animateColorAsState(if (lifted) chrome.raised else chrome.surface, label = "header-surface")
    val elevation by animateDpAsState(if (lifted) FanousTokens.Elevation.low else 0.dp, label = "header-elevation")
    Surface(
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = elevation,
        modifier = Modifier.testTag(if (lifted) "studio-header-lifted" else "studio-header")
    ) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).height(HEADER_HEIGHT),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (channel != null) {
                // Rail column: avatar centred over the rail. Phone: the usual 16 dp edge, then a 12 dp gap.
                Box(
                    if (alignToRail) Modifier.width(RAIL_COLUMN) else Modifier.padding(start = FanousTokens.Spacing.lg),
                    contentAlignment = Alignment.Center
                ) { ChannelAvatar(channel, AVATAR_DP) }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = if (alignToRail) FanousTokens.Spacing.lg else FanousTokens.Spacing.md)
                        .semantics(mergeDescendants = true) { heading() }
                        .testTag("studio-header-title")
                ) {
                    Text(
                        channel.localizedName(currentLocale().language),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(roleLabel(channel.role)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = FanousTokens.Spacing.lg)
                )
            }
            IconButton(onClick = onAccount, modifier = Modifier.padding(end = FanousTokens.Spacing.xs).testTag("account-button")) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = stringResource(net.alummahnow.feature.studio.R.string.studio_account_menu_cd)
                )
            }
        }
    }
}

private val HEADER_HEIGHT = 64.dp
private const val AVATAR_DP = 32
