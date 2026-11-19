package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.core.bridge.studio.ChannelRole
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Studio frame: header + rail (tablet) / header + bottom bar (phone), en/ar × light/dark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class StudioFrameTest {
    @get:Rule val compose = createComposeRule()

    private val channel = StudioChannel(
        "c1",
        "Al Ummah Now",
        mapOf("ar" to "الأمة الآن"),
        handle = "alummahnow",
        isMain = true,
        role = ChannelRole.OWNER
    )

    private enum class Dev(val q: String) { PHONE("w411dp-h891dp-port-xxhdpi"), TABLET("w1280dp-h800dp-land-xhdpi") }

    private fun qualifiers(ar: Boolean, dev: Dev) =
        RuntimeEnvironment.setQualifiers("${if (ar) "ar-ldrtl" else "en-ldltr"}-${dev.q}")

    @Composable
    private fun Page() {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = FanousTokens.Spacing.lg)) {
            items(12) { i ->
                Card(Modifier.fillMaxWidth().padding(vertical = FanousTokens.Spacing.sm).height(64.dp)) {
                    Text("${i + 1}", Modifier.padding(FanousTokens.Spacing.lg))
                }
            }
        }
    }

    @Composable
    private fun Frame() = StudioScaffold(channel, railBelow = true, onAccount = {}) { m ->
        StudioHome(StudioTab.entries, StudioTab.DASHBOARD, onSelect = {}, modifier = m) { Page() }
    }

    private fun shoot(name: String, ar: Boolean, mode: ThemeMode, dev: Dev, content: @Composable () -> Unit) {
        qualifiers(ar, dev)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val file = "src/test/screenshots/${name}_${if (ar) "ar" else "en"}_${mode.name.lowercase()}_${dev.name.lowercase()}.png"
        captureRoboImage(file) { AunTheme(mode = mode) { content() } }
    }

    @Test fun tabletEnLight() = shoot("frame", false, ThemeMode.LIGHT, Dev.TABLET) { Frame() }

    @Test fun tabletEnDark() = shoot("frame", false, ThemeMode.DARK, Dev.TABLET) { Frame() }

    @Test fun tabletArLight() = shoot("frame", true, ThemeMode.LIGHT, Dev.TABLET) { Frame() }

    @Test fun tabletArDark() = shoot("frame", true, ThemeMode.DARK, Dev.TABLET) { Frame() }

    @Test fun phoneEnLight() = shoot("frame", false, ThemeMode.LIGHT, Dev.PHONE) { Frame() }

    @Test fun phoneEnDark() = shoot("frame", false, ThemeMode.DARK, Dev.PHONE) { Frame() }

    @Test fun phoneArLight() = shoot("frame", true, ThemeMode.LIGHT, Dev.PHONE) { Frame() }

    @Test fun phoneArDark() = shoot("frame", true, ThemeMode.DARK, Dev.PHONE) { Frame() }

    /** Phone header once the page has scrolled under it: raised surface + low elevation. */
    @Composable
    private fun Lifted() = Column(Modifier.fillMaxSize()) {
        StudioTopBar(channel, alignToRail = false, lifted = true, onAccount = {})
        Page()
    }

    @Test fun phoneLiftedEnLight() = shoot("frame_lifted", false, ThemeMode.LIGHT, Dev.PHONE) { Lifted() }

    @Test fun phoneLiftedArDark() = shoot("frame_lifted", true, ThemeMode.DARK, Dev.PHONE) { Lifted() }

    @Test
    fun `tablet rail starts flush under the header and the avatar is centred over it`() {
        qualifiers(ar = false, dev = Dev.TABLET)
        compose.setContent { AunTheme { Frame() } }
        val header = compose.onNodeWithTag("studio-header").getBoundsInRoot()
        compose.onNodeWithTag("studio-rail").assertTopPositionInRootIsEqualTo(header.bottom).assertLeftPositionInRootIsEqualTo(0.dp)
        // Title starts at the content edge: rail column + the page's 16 dp padding.
        compose.onNodeWithTag("studio-header-title").assertLeftPositionInRootIsEqualTo(RAIL_COLUMN + FanousTokens.Spacing.lg)
        compose.onNodeWithTag("studio-header").assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun `rtl mirrors the frame - rail and avatar column on the right`() {
        qualifiers(ar = true, dev = Dev.TABLET)
        compose.setContent { AunTheme { Frame() } }
        val rail = compose.onNodeWithTag("studio-rail").getBoundsInRoot()
        val header = compose.onNodeWithTag("studio-header").getBoundsInRoot()
        assertEquals(header.right, rail.right)
        val title = compose.onNodeWithTag("studio-header-title").getBoundsInRoot()
        assertEquals(header.right - RAIL_COLUMN - FanousTokens.Spacing.lg, title.right)
    }

    @Test
    fun `header carries the identity - name and role`() {
        qualifiers(ar = false, dev = Dev.PHONE)
        compose.setContent { AunTheme { Frame() } }
        compose.onNodeWithText("Al Ummah Now").assertExists()
        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithTag("studio-bottom-bar").assertExists()
    }
}
