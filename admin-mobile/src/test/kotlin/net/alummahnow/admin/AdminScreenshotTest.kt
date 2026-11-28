package net.alummahnow.admin

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.alummahnow.admin.media.LocalReviewPlayerFactory
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
import net.alummahnow.core.bridge.admin.ReviewDetail
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewStream
import net.alummahnow.core.bridge.admin.ReviewTab
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.fixtures.PausedPlayer
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

    /**
     * Creator content (titles, channels, descriptions, tags, reviewer names) in the frame's language: an Arabic frame
     * shows Arabic content. The model's rationale stays English, as the screen labels it.
     */
    private val arabic = mapOf(
        "Evening market walk" to "جولة في سوق المساء",
        "Old City Walks" to "جولات المدينة القديمة",
        "Charity drive update" to "آخر أخبار حملة التبرعات",
        "Hope Relief" to "إغاثة الأمل",
        "Ramadan recipes" to "وصفات رمضان",
        "Family Kitchen" to "مطبخ العائلة",
        "Debate night" to "ليلة المناظرة",
        "Campus Voices" to "أصوات الجامعة",
        "Swimming lesson" to "تعلّم السباحة",
        "Sports Club" to "النادي الرياضي",
        "Morning reminder" to "تذكير الصباح",
        "A slow walk through the old city's evening market: spice stalls, lanterns and the call to prayer at sunset." to
            "جولة هادئة في سوق المساء بالمدينة القديمة: أكشاك التوابل والفوانيس وأذان المغرب.",
        "Where your donations went this month, and how to join the winter appeal." to
            "أين ذهبت تبرعاتكم هذا الشهر، وكيف تشاركون في حملة الشتاء.",
        "Three quick iftar dishes from our family kitchen: lentil soup, stuffed dates and kunafa." to
            "ثلاثة أطباق سريعة للإفطار من مطبخ العائلة: شوربة العدس والتمر المحشو والكنافة.",
        "The student union's open debate on free speech, recorded live in the main hall." to
            "مناظرة اتحاد الطلبة المفتوحة عن حرية التعبير، مسجّلة مباشرة في القاعة الكبرى.",
        "Week three of the beginners' swimming class: floating and breathing." to
            "الأسبوع الثالث من دروس السباحة للمبتدئين: الطفو والتنفس.",
        "Donate now, limited time" to "تبرّع الآن، لفترة محدودة",
        "Free iftar box for every viewer" to "سلة إفطار مجانية لكل مشاهد",
        "Sit down, nobody wants to hear you" to "اجلس، لا أحد يريد سماعك",
        "travel" to "سفر",
        "old city" to "المدينة القديمة",
        "charity" to "تبرعات",
        "winter appeal" to "حملة الشتاء",
        "ramadan" to "رمضان",
        "recipes" to "وصفات",
        "debate" to "مناظرة",
        "campus" to "الجامعة",
        "swimming" to "سباحة",
        "sport" to "رياضة",
        "Amina" to "آمنة"
    )

    /** FixtureReviewService's staff accounts as the people a frame shows: (English name, Arabic name). */
    private val staff = mapOf(
        "Fixture Senior" to ("Huda Karim" to "هدى كريم"),
        "Fixture Reviewer" to ("Yusuf Haddad" to "يوسف حداد")
    )

    private fun String.inLang(lang: Lang): String {
        staff[this]?.let { (en, ar) -> return if (lang == Lang.AR) ar else en }
        return if (lang == Lang.AR) arabic[this] ?: this else this
    }

    private fun ReviewItem.inLang(lang: Lang) = copy(
        title = title?.inLang(lang),
        channelName = channelName?.inLang(lang),
        assignedToName = assignedToName?.inLang(lang),
        decidedByName = decidedByName?.inLang(lang)
    )

    private fun ReviewDetail.inLang(lang: Lang) = copy(
        item = item.inLang(lang),
        findings = findings.map { it.copy(text = it.text?.inLang(lang)) },
        subject = subject?.let { subj ->
            subj.copy(
                title = subj.title.inLang(lang),
                description = subj.description.inLang(lang),
                tags = subj.tags.map { it.inLang(lang) }
            )
        }
    )

    private fun queue(lang: Lang) = @Composable {
        val items = runBlocking { fixture.queue(ReviewTab.ACTION_NEEDED, null, null).items }.map { it.inLang(lang) }
        QueueScreen(
            QueueUiState(items = QueueViewModel.sort(ReviewTab.ACTION_NEEDED, items), loading = false, lastUpdated = now),
            myUserId = "fx-senior", now = now, onTab = {}, onQuery = {}, onOpen = {}, onReverse = {}, onRefresh = {}, onLoadMore = {}
        )
    }

    private fun offlineQueue(lang: Lang) = @Composable {
        val items = runBlocking { fixture.queue(ReviewTab.ACTION_NEEDED, null, null).items }.map { it.inLang(lang) }
        QueueScreen(
            QueueUiState(items = items, loading = false, offline = true, lastUpdated = now.minusSeconds(300)),
            myUserId = "fx-senior", now = now, onTab = {}, onQuery = {}, onOpen = {}, onReverse = {}, onRefresh = {}, onLoadMore = {}
        )
    }

    /**
     * A review as the reviewer sees it once the stream has loaded: the frames are title-card art of the video in the
     * frame's language (scripts/title-cards.py), and the player is ready and paused on the opening frame.
     */
    private fun review(id: String, lang: Lang, message: ReviewMessage? = null) = @Composable {
        val fixed = runBlocking { fixture.detail(id) }
        val detail = fixed.inLang(lang).copy(stream = ReviewStream("https://stream.fixture.invalid/${fixed.item.subjectId}/master.m3u8"))
        val frames = detail.frames.associate { it.key to frameArt(it.key, lang) }
        val durationMs = ((detail.subject?.durationSeconds ?: 0.0) * MS_PER_S).toLong()
        CompositionLocalProvider(LocalReviewPlayerFactory provides { PausedPlayer(id, durationMs) }) {
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
    }

    /** "review/v-risk/r1/frame-128.jpg" → the frames/v-risk-128 card in [lang], as bytes (frames load into memory). */
    private fun frameArt(key: String, lang: Lang): ByteArray {
        val (subject, second) = requireNotNull(FRAME_KEY.matchEntire(key)) { "Unexpected frame key $key" }.destructured
        val name = "/frames/$subject-$second${if (lang == Lang.AR) "" else ".en"}.jpg"
        return requireNotNull(javaClass.getResourceAsStream(name)) { "No frame art $name: run scripts/title-cards.py --frames" }
            .use { it.readBytes() }
    }

    /** Settings as the frame shows them: its own theme and language selected, alerts on (a release build with push set up). */
    private fun settings(lang: Lang, mode: ThemeMode) = @Composable {
        SettingsContent(
            "Fixture Senior".inLang(lang),
            dark = mode == ThemeMode.DARK,
            language = if (lang == Lang.AR) "ar" else "en",
            push = PushState.ON,
            onDark = {},
            onLanguage = {},
            onSignOut = {},
            onTelemetryChanged = {}
        )
    }

    @Test fun queueEnLight() = shoot("queue", Lang.EN, ThemeMode.LIGHT, content = queue(Lang.EN))

    @Test fun queueEnDark() = shoot("queue", Lang.EN, ThemeMode.DARK, content = queue(Lang.EN))

    @Test fun queueArLight() = shoot("queue", Lang.AR, ThemeMode.LIGHT, content = queue(Lang.AR))

    @Test fun queueArDark() = shoot("queue", Lang.AR, ThemeMode.DARK, content = queue(Lang.AR))

    @Test fun queueOfflineEn() = shoot("queue_offline", Lang.EN, ThemeMode.LIGHT, content = offlineQueue(Lang.EN))

    @Test fun queueOfflineAr() = shoot("queue_offline", Lang.AR, ThemeMode.DARK, content = offlineQueue(Lang.AR))

    @Test fun reviewBlurredEnLight() = shoot("review_nsfw", Lang.EN, ThemeMode.LIGHT, content = review("fx-nsfw", Lang.EN))

    @Test fun reviewBlurredEnDark() = shoot("review_nsfw", Lang.EN, ThemeMode.DARK, content = review("fx-nsfw", Lang.EN))

    @Test fun reviewBlurredArLight() = shoot("review_nsfw", Lang.AR, ThemeMode.LIGHT, content = review("fx-nsfw", Lang.AR))

    @Test fun reviewBlurredArDark() = shoot("review_nsfw", Lang.AR, ThemeMode.DARK, content = review("fx-nsfw", Lang.AR))

    @Test fun reviewMinorAr() = shoot("review_minor", Lang.AR, ThemeMode.DARK, content = review("fx-minor", Lang.AR))

    @Test fun reviewConflictEn() =
        shoot(
            "review_conflict",
            Lang.EN,
            ThemeMode.LIGHT,
            content = review("fx-conflict", Lang.EN, ReviewMessage.Conflict("approve", "Amina", now.minusSeconds(20)))
        )

    @Test fun reviewConflictAr() =
        shoot(
            "review_conflict",
            Lang.AR,
            ThemeMode.DARK,
            content = review("fx-conflict", Lang.AR, ReviewMessage.Conflict("approve", "آمنة", now.minusSeconds(20)))
        )

    @Test fun reviewLargestFontEn() = shoot("review_risk", Lang.EN, ThemeMode.LIGHT, fontScale = 2f, content = review("fx-risk", Lang.EN))

    @Test fun reviewLargestFontAr() = shoot("review_risk", Lang.AR, ThemeMode.DARK, fontScale = 2f, content = review("fx-risk", Lang.AR))

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

    @Test fun settingsEnLight() = shoot("settings", Lang.EN, ThemeMode.LIGHT, content = settings(Lang.EN, ThemeMode.LIGHT))

    @Test fun settingsEnDark() = shoot("settings", Lang.EN, ThemeMode.DARK, content = settings(Lang.EN, ThemeMode.DARK))

    @Test fun settingsArLight() = shoot("settings", Lang.AR, ThemeMode.LIGHT, content = settings(Lang.AR, ThemeMode.LIGHT))

    @Test fun settingsArDark() = shoot("settings", Lang.AR, ThemeMode.DARK, content = settings(Lang.AR, ThemeMode.DARK))

    private companion object {
        val FRAME_KEY = Regex("""review/([^/]+)/r1/frame-(\d+)\.jpg""")
        const val MS_PER_S = 1_000.0
    }
}
