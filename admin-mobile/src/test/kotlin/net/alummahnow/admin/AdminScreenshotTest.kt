package net.alummahnow.admin

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.alummahnow.admin.push.PushState
import net.alummahnow.admin.queue.QueueScreen
import net.alummahnow.admin.queue.QueueUiState
import net.alummahnow.admin.queue.QueueViewModel
import net.alummahnow.admin.review.ReviewMessage
import net.alummahnow.admin.review.ReviewScreen
import net.alummahnow.admin.review.ReviewUiState
import net.alummahnow.admin.ui.AdminSignInScreen
import net.alummahnow.admin.ui.NotStaffScreen
import net.alummahnow.admin.ui.SettingsContent
import net.alummahnow.admin.ui.SignInState
import net.alummahnow.core.bridge.admin.FixtureReviewService
import net.alummahnow.core.bridge.admin.ReviewTab
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** en/ar (RTL) × light/dark for every Admin screen, plus the largest font size. Goldens are recorded on Linux. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminScreenshotTest {
    private enum class Lang(val qualifier: String) { EN("en-ldltr"), AR("ar-ldrtl") }

    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val fixture = FixtureReviewService(FixtureReviewService.Role.SENIOR, now = { now })

    private fun shoot(name: String, lang: Lang, mode: ThemeMode, fontScale: Float = 1f, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-w411dp-h891dp-port-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val suffix = if (fontScale > 1f) "_font${(fontScale * 100).toInt()}" else ""
        captureRoboImage("src/test/screenshots/${name}_${lang.name.lowercase()}_${mode.name.lowercase()}$suffix.png") {
            // The app's Scaffold paints the background; screens alone need a Surface to look the same.
            AunTheme(mode = mode) { androidx.compose.material3.Surface { content() } }
        }
    }

    private fun queue() = @Composable {
        val items = runBlocking { fixture.queue(ReviewTab.ACTION_NEEDED, null, null).items }
        QueueScreen(
            QueueUiState(items = QueueViewModel.sort(ReviewTab.ACTION_NEEDED, items), loading = false, lastUpdated = now),
            myUserId = "fx-senior", now = now, onTab = {}, onQuery = {}, onOpen = {}, onReverse = {}, onRefresh = {}, onLoadMore = {}
        )
    }

    private fun offlineQueue() = @Composable {
        val items = runBlocking { fixture.queue(ReviewTab.ACTION_NEEDED, null, null).items }
        QueueScreen(
            QueueUiState(items = items, loading = false, offline = true, lastUpdated = now.minusSeconds(300)),
            myUserId = "fx-senior", now = now, onTab = {}, onQuery = {}, onOpen = {}, onReverse = {}, onRefresh = {}, onLoadMore = {}
        )
    }

    private fun review(id: String, message: ReviewMessage? = null) = @Composable {
        val detail = runBlocking { fixture.detail(id) }
        val frames = detail.frames.associate { it.key to runBlocking { fixture.media(id, it.key) } }
        ReviewScreen(
            ReviewUiState(loading = false, detail = detail, frames = frames, message = message),
            now = now,
            online = true,
            onShow = {},
            onDecide = {},
            onDismissMessage = {},
            onRetry = {}
        )
    }

    private fun settings() = @Composable {
        SettingsContent("Fixture Senior", dark = false, language = "en", push = PushState.NOT_CONFIGURED, onDark = {
        }, onLanguage = {}, onSignOut = {})
    }

    @Test fun queueEnLight() = shoot("queue", Lang.EN, ThemeMode.LIGHT, content = queue())

    @Test fun queueEnDark() = shoot("queue", Lang.EN, ThemeMode.DARK, content = queue())

    @Test fun queueArLight() = shoot("queue", Lang.AR, ThemeMode.LIGHT, content = queue())

    @Test fun queueArDark() = shoot("queue", Lang.AR, ThemeMode.DARK, content = queue())

    @Test fun queueOfflineEn() = shoot("queue_offline", Lang.EN, ThemeMode.LIGHT, content = offlineQueue())

    @Test fun queueOfflineAr() = shoot("queue_offline", Lang.AR, ThemeMode.DARK, content = offlineQueue())

    @Test fun reviewBlurredEnLight() = shoot("review_nsfw", Lang.EN, ThemeMode.LIGHT, content = review("fx-nsfw"))

    @Test fun reviewBlurredEnDark() = shoot("review_nsfw", Lang.EN, ThemeMode.DARK, content = review("fx-nsfw"))

    @Test fun reviewBlurredArLight() = shoot("review_nsfw", Lang.AR, ThemeMode.LIGHT, content = review("fx-nsfw"))

    @Test fun reviewBlurredArDark() = shoot("review_nsfw", Lang.AR, ThemeMode.DARK, content = review("fx-nsfw"))

    @Test fun reviewMinorAr() = shoot("review_minor", Lang.AR, ThemeMode.DARK, content = review("fx-minor"))

    @Test fun reviewConflictEn() =
        shoot(
            "review_conflict",
            Lang.EN,
            ThemeMode.LIGHT,
            content = review("fx-conflict", ReviewMessage.Conflict("approve", "Amina", now.minusSeconds(20)))
        )

    @Test fun reviewConflictAr() =
        shoot(
            "review_conflict",
            Lang.AR,
            ThemeMode.DARK,
            content = review("fx-conflict", ReviewMessage.Conflict("approve", "Amina", now.minusSeconds(20)))
        )

    @Test fun reviewLargestFontEn() = shoot("review_risk", Lang.EN, ThemeMode.LIGHT, fontScale = 2f, content = review("fx-risk"))

    @Test fun reviewLargestFontAr() = shoot("review_risk", Lang.AR, ThemeMode.DARK, fontScale = 2f, content = review("fx-risk"))

    @Test fun signInNoLockEn() = shoot("signin_nolock", Lang.EN, ThemeMode.LIGHT) {
        AdminSignInScreen(SignInState.Idle, deviceSecure = false, onSignIn = {})
    }

    @Test fun signInNoLockAr() = shoot("signin_nolock", Lang.AR, ThemeMode.DARK) {
        AdminSignInScreen(SignInState.Idle, deviceSecure = false, onSignIn = {})
    }

    @Test fun signInEnDark() = shoot("signin", Lang.EN, ThemeMode.DARK) {
        AdminSignInScreen(SignInState.Idle, deviceSecure = true, onSignIn = {})
    }

    @Test fun signInArLight() = shoot("signin", Lang.AR, ThemeMode.LIGHT) {
        AdminSignInScreen(SignInState.Idle, deviceSecure = true, onSignIn = {})
    }

    @Test fun notStaffEn() = shoot("not_staff", Lang.EN, ThemeMode.LIGHT) { NotStaffScreen(onSignOut = {}) }

    @Test fun notStaffAr() = shoot("not_staff", Lang.AR, ThemeMode.DARK) { NotStaffScreen(onSignOut = {}) }

    @Test fun settingsEn() = shoot("settings", Lang.EN, ThemeMode.LIGHT, content = settings())

    @Test fun settingsAr() = shoot("settings", Lang.AR, ThemeMode.DARK, content = settings())
}
