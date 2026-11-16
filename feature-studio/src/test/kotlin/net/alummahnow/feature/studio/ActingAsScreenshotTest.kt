package net.alummahnow.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.studio.ChannelRole
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheetContent
import net.alummahnow.feature.studio.channels.ChannelAvatar
import net.alummahnow.feature.studio.channels.ChannelPickerPanel
import net.alummahnow.feature.studio.channels.ChannelPickerScreen
import net.alummahnow.feature.studio.channels.ChannelsUiState
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Acting as switcher as people see it: the account sheet and the channel picker sheet over Studio,
 * and the first-run picker under Studio's top bar. Fixtures read like real people and channels in
 * each language. en/ar × light/dark × phone/tablet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ActingAsScreenshotTest {
    private val channels = listOf(
        StudioChannel("c1", "Dawn Recitations", mapOf("ar" to "تلاوات الفجر"), handle = "dawnrecitations", isMain = true),
        StudioChannel("c2", "Mountain Trails", mapOf("ar" to "دروب الجبال"), handle = "mountaintrails"),
        StudioChannel(
            "c3",
            "Family Kitchen",
            mapOf("ar" to "مطبخ العائلة"),
            handle = "familykitchen",
            role = ChannelRole.EDITOR,
            capabilities = setOf("video.upload", "video.edit")
        )
    )

    @Composable
    private fun accounts(): List<StudioAccount> {
        val ar = currentLocale().language == "ar"
        return listOf(
            StudioAccount("u1", if (ar) "أمينة يوسف" else "Amina Yusuf", "amina.yusuf@example.com", null, "c1", false),
            StudioAccount("u2", if (ar) "عمر حداد" else "Omar Haddad", "omar.haddad@example.com", null, null, false)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TopBar(channel: StudioChannel?) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel != null) {
                        ChannelAvatar(channel, 32)
                        Spacer(Modifier.width(12.dp))
                        Text(channel.localizedName(currentLocale().language), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(stringResource(R.string.studio_signin_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Filled.AccountCircle, contentDescription = null) }
            }
        )
    }

    /** Studio's dashboard, dimmed by the sheet's scrim, with [sheet] at the bottom. */
    @Composable
    private fun OverStudio(sheet: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    TopBar(channels[0])
                    DashboardScreen(
                        channels[0],
                        DashboardUiState.Content(
                            CreatorAccess(true, false, null),
                            ContentCounts(12, 2, 3, 1, 1)
                        ),
                        {
                        }
                    )
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM)))
            Box(Modifier.align(Alignment.BottomCenter)) { sheet() }
        }
    }

    private val accountSheet = @Composable {
        OverStudio { AccountSheetContent(accounts(), "u1", channels[0], {}, {}, {}, {}, onAddPasskey = {}) }
    }
    private val pickerSheet = @Composable { OverStudio { ChannelPickerPanel(channels, "c1", {}, {}) } }
    private val firstRun = @Composable {
        ChannelPickerScreen(ChannelsUiState.Content(channels, null), {}, {}, header = { TopBar(null) })
    }

    private fun all(name: String, content: @Composable () -> Unit) {
        Lang.entries.forEach { lang ->
            Device.entries.forEach { device ->
                listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode -> shoot(name, lang, mode, device, content) }
            }
        }
    }

    @Test fun accountSheet() = all("acting_as_account_sheet", accountSheet)

    @Test fun pickerSheet() = all("acting_as_picker_sheet", pickerSheet)

    @Test fun firstRunPicker() = all("acting_as_first_run", firstRun)

    private companion object {
        const val SCRIM = 0.32f
    }
}
