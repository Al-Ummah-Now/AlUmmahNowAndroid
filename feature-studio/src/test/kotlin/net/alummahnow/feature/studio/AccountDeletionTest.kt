package net.alummahnow.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.deletion.DeletionRecord
import net.alummahnow.core.bridge.studio.deletion.DeletionStage
import net.alummahnow.core.bridge.studio.deletion.Leftover
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheetContent
import net.alummahnow.feature.studio.deletion.DeleteAccountDialog
import net.alummahnow.feature.studio.deletion.DeleteAccountUiState
import net.alummahnow.feature.studio.deletion.DeleteAction
import net.alummahnow.feature.studio.deletion.DeletionBanner
import net.alummahnow.feature.studio.deletion.isDeleteWord
import net.alummahnow.feature.studio.signin.SignInNotice
import net.alummahnow.feature.studio.signin.SignInScreen
import net.alummahnow.feature.studio.signin.SignInUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AccountDeletionTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_000_000_000L

    @Test
    fun `either word counts in either language, whatever the case, spaces and marks`() {
        listOf(
            "DELETE", "delete", " Delete ", "‎DELETE‏", "D‍ELETE",
            "حذف", " حذف ", "حَذْف", "حـــذف", "‏حذف‎", "⁧حذف⁩", "؜حذف"
        ).forEach { assertTrue(it, isDeleteWord(it)) }
        listOf("", "DELET", "DELETED", "DE LETE", "DÉLETE", "احذف", "حذفا", "حذ", "حدف", "حذف DELETE").forEach {
            assertFalse(it, isDeleteWord(it))
        }
    }

    private var state by mutableStateOf(DeleteAccountUiState(target = sampleAccount, ownedChannels = 2))
    private var confirmed = 0
    private var closed = 0
    private var clock = now

    private fun dialog(language: String = "en-ldltr") {
        RuntimeEnvironment.setQualifiers("$language-w411dp-h1400dp-port")
        compose.setContent { AunTheme { DeleteAccountDialog(state, { confirmed++ }, { closed++ }, nowMs = { clock }) } }
    }

    @Test
    fun `Cancel has focus, the account and its channels are named, and the action needs the word`() {
        dialog()
        compose.onNodeWithTag("delete-account-cancel").assertIsFocused()
        compose.onNodeWithTag("delete-account-name").assertTextContains("amina.yusuf@example.org", substring = true)
        compose.onNodeWithTag("delete-account-channels").assertTextContains("You own 2 channels.", substring = true)
        compose.onNodeWithTag("delete-account-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete-account-input").performTextInput("حذف")
        compose.onNodeWithTag("delete-account-confirm").assertIsEnabled()
        compose.onNodeWithTag("delete-account-input").performTextClearance()
        compose.onNodeWithTag("delete-account-confirm").assertIsNotEnabled()
    }

    /** wiki guides/data-lifecycle.md: the purge is after 30 days, and may be later. */
    private fun purge(language: String, said: String, never: String) {
        val texts = mutableListOf<String>()
        RuntimeEnvironment.setQualifiers(language)
        compose.setContent {
            texts += aunString(R.string.studio_delete_kept, 30)
            texts += aunString(R.string.studio_delete_done_purge, 30)
        }
        compose.waitForIdle()
        assertEquals(2, texts.distinct().size)
        texts.forEach {
            assertTrue(it, said in it)
            assertFalse(it, never in it)
        }
    }

    @Test
    fun `the purge is said to be after 30 days, never within them`() = purge("en-ldltr", "after 30 days", "within")

    @Test
    fun `the purge is said to be after 30 days in Arabic too`() = purge("ar-ldrtl", "بعد ٣٠ يومًا", "خلال")

    @Test
    fun `without a known number the channels are still named, and an account with none says nothing`() {
        state = state.copy(ownedChannels = null)
        dialog()
        compose.onNodeWithTag("delete-account-channels").assertTextContains("Channels you own are deleted whole", substring = true)
        state = state.copy(ownedChannels = 0)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-channels").assertDoesNotExist()
    }

    @Test
    fun `while it works there is a way out, until it is taken`() {
        dialog()
        compose.onNodeWithTag("delete-account-input").performTextInput("DELETE")
        compose.onNodeWithTag("delete-account-confirm").assertIsEnabled()
        state = state.copy(working = true, passes = 2)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-cancel").assertIsEnabled().performClick()
        assertEquals(1, closed)
        compose.onNodeWithTag("delete-account-confirm").assertIsNotEnabled()
        state = state.copy(stopping = true)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-cancel").assertIsNotEnabled()
        // Whatever the answer was, the dialog can be left.
        state = state.copy(working = false, stopping = false, message = R.string.studio_delete_error_started, isError = true)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-cancel").assertIsEnabled()
    }

    @Test
    fun `it can't go on before the server's time, and can after it without reopening`() {
        state = state.copy(message = R.string.studio_delete_wait, isError = true, notBeforeMs = now + 90_000)
        dialog()
        compose.onNodeWithTag("delete-account-input").performTextInput("delete")
        compose.onNodeWithTag("delete-account-wait", useUnmergedTree = true).assertTextContains("Try again in 2 minutes.")
        compose.onNodeWithTag("delete-account-confirm").assertIsNotEnabled()
        clock = now + 91_000
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-confirm").assertIsEnabled()
    }

    @Test
    fun `a long wait is said in hours, never less than is left`() {
        state = state.copy(message = R.string.studio_delete_wait, isError = true, notBeforeMs = now + 119 * 60_000)
        dialog()
        compose.onNodeWithTag("delete-account-wait", useUnmergedTree = true).assertTextContains("Try again in 119 minutes.")
        state = state.copy(notBeforeMs = now + 121 * 60_000)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-wait", useUnmergedTree = true).assertTextContains("Try again in 3 hours.")
    }

    @Test
    fun `at a large font, what happened is in view`() {
        RuntimeEnvironment.setFontScale(2f)
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h700dp-port")
        compose.setContent { AunTheme { DeleteAccountDialog(state, {}, {}, nowMs = { clock }) } }
        state = state.copy(message = R.string.studio_delete_error_started, isError = true)
        compose.waitForIdle()
        compose.onNodeWithTag("delete-account-message").assertIsDisplayed()
    }

    @Test
    fun `a sign-in needs no typed word`() {
        state = state.copy(action = DeleteAction.SIGN_IN, message = R.string.studio_delete_sign_in)
        dialog()
        compose.onNodeWithTag("delete-account-confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `the account list says which deletion is open`() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h1400dp-port")
        var opened = 0
        compose.setContent { AunTheme { Sheet(setOf("u1")) { opened++ } } }
        compose.onNodeWithTag("studio-delete-account").assertTextContains("Finish deleting").performClick()
        compose.onNodeWithTag("account-u1").assertTextContains("Deletion started, not finished")
        assertEquals(1, opened)
    }

    // --- Screenshots: whole screens, because a dialog is a window of its own ---

    private enum class Look(val lang: Lang, val mode: ThemeMode) {
        EN_DARK(Lang.EN, ThemeMode.DARK),
        EN_LIGHT(Lang.EN, ThemeMode.LIGHT),
        AR_DARK(Lang.AR, ThemeMode.DARK),
        AR_LIGHT(Lang.AR, ThemeMode.LIGHT)
    }

    private val Look.ar get() = lang == Lang.AR

    private fun account(look: Look) = if (look.ar) sampleAccount.copy(displayName = "أمينة يوسف") else sampleAccount

    @Composable
    private fun Sheet(started: Set<String>, look: Look = Look.EN_DARK, onDelete: () -> Unit = {}) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                AccountSheetContent(
                    accounts = listOf(
                        account(look),
                        StudioAccount("u2", if (look.ar) "بلال طارق" else "Bilal Tariq", "bilal.tariq@example.org", null, null, false)
                    ),
                    activeUserId = "u1",
                    channel = sampleChannels[0],
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onRequestSignOut = {},
                    onSwitchChannel = {},
                    onDismiss = {},
                    onOpenUrl = {},
                    webHost = "videos.qa.alummahnow.net",
                    onDeleteAccount = onDelete,
                    deletionStarted = started
                )
            }
        }
    }

    private fun shoot(name: String, look: Look, tablet: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val device = if (tablet) Device.TABLET else Device.PHONE
        RuntimeEnvironment.setQualifiers("${look.lang.qualifier}-${device.qualifier}")
        RuntimeEnvironment.setFontScale(fontScale)
        android.provider.Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )
        compose.setContent {
            AunTheme(mode = look.mode) {
                WithFixtureImages {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val size = if (fontScale == 1f) "" else "_font200"
        captureScreenRoboImage("src/test/screenshots/delete_account_${name}${size}_${look.name.lowercase()}_${device.name.lowercase()}.png")
    }

    private fun shootDialog(
        name: String,
        look: Look,
        typed: String = "",
        tablet: Boolean = false,
        fontScale: Float = 1f,
        change: DeleteAccountUiState.() -> DeleteAccountUiState = { this }
    ) = shoot(name, look, tablet, fontScale) {
        DeleteAccountDialog(
            DeleteAccountUiState(target = account(look), ownedChannels = 2).change(),
            onConfirm = {},
            onClose = {},
            initialTyped = typed,
            nowMs = { now }
        )
    }

    private fun word(look: Look) = if (look.ar) "حذف" else "DELETE"

    private fun message(name: String, look: Look, text: Int, error: Boolean, action: DeleteAction = DeleteAction.DELETE) =
        shootDialog(name, look, typed = word(look)) { copy(message = text, isError = error, action = action) }

    @Test fun openEnDark() = shootDialog("open", Look.EN_DARK)

    @Test fun openEnLight() = shootDialog("open", Look.EN_LIGHT)

    @Test fun openArDark() = shootDialog("open", Look.AR_DARK)

    @Test fun openArLight() = shootDialog("open", Look.AR_LIGHT)

    @Test fun openEnDarkTablet() = shootDialog("open", Look.EN_DARK, tablet = true)

    @Test fun openArLightTablet() = shootDialog("open", Look.AR_LIGHT, tablet = true)

    @Test fun openEnDark200() = shootDialog("open", Look.EN_DARK, fontScale = 2f)

    @Test fun openArLight200() = shootDialog("open", Look.AR_LIGHT, fontScale = 2f)

    @Test fun typedEnDark() = shootDialog("typed", Look.EN_DARK, "DELETE")

    @Test fun typedEnLight() = shootDialog("typed", Look.EN_LIGHT, "DELETE")

    @Test fun typedArDark() = shootDialog("typed", Look.AR_DARK, "حذف")

    @Test fun typedArLight() = shootDialog("typed", Look.AR_LIGHT, "حذف")

    @Test fun workingEnDark() = shootDialog("working", Look.EN_DARK, "DELETE") { copy(working = true) }

    @Test fun workingArLight() = shootDialog("working", Look.AR_LIGHT, "حذف") { copy(working = true, passes = 2) }

    @Test fun stoppingEnLight() = shootDialog("stopping", Look.EN_LIGHT, "DELETE") { copy(working = true, stopping = true, passes = 2) }

    @Test fun restartedArDark() = shootDialog("restarted", Look.AR_DARK, "حذف") { copy(working = true, passes = 1, restarted = true) }

    @Test fun signInEnDark() = message("sign_in", Look.EN_DARK, R.string.studio_delete_sign_in, false, DeleteAction.SIGN_IN)

    @Test fun signInArLight() = message("sign_in", Look.AR_LIGHT, R.string.studio_delete_sign_in, false, DeleteAction.SIGN_IN)

    @Test fun signedInEnLight() = message("signed_in_again", Look.EN_LIGHT, R.string.studio_delete_signed_in_again, false)

    @Test fun contentErasedSignInArDark() =
        message("sign_in_content_erased", Look.AR_DARK, R.string.studio_delete_sign_in_content_erased, false, DeleteAction.SIGN_IN)

    @Test fun nothingEnDark() = message("error_nothing", Look.EN_DARK, R.string.studio_delete_error_nothing, true)

    @Test fun startedArLight() = message("error_started", Look.AR_LIGHT, R.string.studio_delete_error_started, true)

    @Test fun unknownEnLight() = message("error_unknown", Look.EN_LIGHT, R.string.studio_delete_error_unknown, true)

    @Test fun contentErasedEnDark() = message("error_content_erased", Look.EN_DARK, R.string.studio_delete_error_content_erased, true)

    @Test fun moreArDark() = message("more", Look.AR_DARK, R.string.studio_delete_more, true)

    @Test fun leftChannelEnDark() = message(
        "left_channel",
        Look.EN_DARK,
        R.string.studio_delete_left_channel,
        false,
        DeleteAction.DELETE_ANYWAY
    )

    @Test fun leftChannelArLight() = message(
        "left_channel",
        Look.AR_LIGHT,
        R.string.studio_delete_left_channel,
        false,
        DeleteAction.DELETE_ANYWAY
    )

    @Test fun leftOtherEnLight() = message(
        "left_other",
        Look.EN_LIGHT,
        R.string.studio_delete_left_other,
        false,
        DeleteAction.DELETE_ANYWAY
    )

    @Test fun leftBothEnDark() = left(Look.EN_DARK)

    @Test fun leftBothArLight() = left(Look.AR_LIGHT)

    @Test fun leftBothArDark200() = left(Look.AR_DARK, 2f)

    private fun left(look: Look, fontScale: Float = 1f) = shootDialog("left_both", look, word(look), fontScale = fontScale) {
        copy(message = R.string.studio_delete_left_both, action = DeleteAction.DELETE_ANYWAY)
    }

    @Test fun doneBothEnLight() = signedOut("done_both", Look.EN_LIGHT) { doneBoth() }

    @Test fun doneBothArDark() = signedOut("done_both", Look.AR_DARK) { doneBoth() }

    @Composable
    private fun doneBoth() = aunString(R.string.studio_delete_done_both) + " " + aunString(R.string.studio_delete_done_purge, 30)

    @Test fun waitHoursEnDark() = hours(Look.EN_DARK)

    @Test fun waitHoursArLight() = hours(Look.AR_LIGHT)

    private fun hours(look: Look) = shootDialog("wait_hours", look, word(look)) {
        copy(message = R.string.studio_delete_wait, isError = true, notBeforeMs = now + 24 * 3_600_000)
    }

    @Test fun waitEnLight() = shootDialog("wait", Look.EN_LIGHT, "DELETE") {
        copy(message = R.string.studio_delete_wait, isError = true, notBeforeMs = now + 300_000)
    }

    @Test fun waitArDark() = shootDialog("wait", Look.AR_DARK, "حذف") {
        copy(message = R.string.studio_delete_wait, isError = true, notBeforeMs = now + 300_000)
    }

    private fun banner(look: Look, record: DeletionRecord, fontScale: Float = 1f, name: String = "banner") =
        shoot(name, look, fontScale = fontScale) { Column { DeletionBanner(record, onFinish = {}) } }

    private val started = DeletionRecord("u1", "amina.yusuf@example.org")

    @Test fun bannerEnDark() = banner(Look.EN_DARK, started)

    @Test fun bannerEnLight() = banner(Look.EN_LIGHT, started)

    @Test fun bannerArDark() = banner(Look.AR_DARK, started)

    @Test fun bannerArLight() = banner(Look.AR_LIGHT, started)

    @Test fun bannerArDark200() = banner(Look.AR_DARK, started, fontScale = 2f)

    @Test fun bannerChannelEnLight() =
        banner(
            Look.EN_LIGHT,
            started.copy(stage = DeletionStage.CONTENT_ERASED, leftover = setOf(Leftover.CHANNEL)),
            name = "banner_channel"
        )

    @Test fun sheetEnDark() = shoot("sheet", Look.EN_DARK) { Sheet(emptySet()) }

    @Test fun sheetArLight() = shoot("sheet", Look.AR_LIGHT) { Sheet(emptySet(), Look.AR_LIGHT) }

    @Test fun sheetStartedArDark() = shoot("sheet_started", Look.AR_DARK) { Sheet(setOf("u1"), Look.AR_DARK) }

    private fun signedOut(name: String, look: Look, onDismiss: (() -> Unit)? = null, notice: @Composable () -> String) = shoot(name, look) {
        SignInScreen(SignInUiState.Idle, {}, null, null, notices = listOf(SignInNotice(notice(), onDismiss)))
    }

    private fun done(look: Look, fontScale: Float = 1f) = shoot("done", look, fontScale = fontScale) {
        SignInScreen(
            SignInUiState.Idle,
            {},
            null,
            null,
            notices = listOf(SignInNotice(aunString(R.string.studio_delete_done) + " " + aunString(R.string.studio_delete_done_purge, 30)))
        )
    }

    @Test fun doneEnDark200() = done(Look.EN_DARK, 2f)

    @Test fun doneArLight200() = done(Look.AR_LIGHT, 2f)

    @Test fun startedEnLight200() = shootDialog("error_started", Look.EN_LIGHT, "DELETE", fontScale = 2f) {
        copy(message = R.string.studio_delete_error_started, isError = true)
    }

    @Test fun leftChannelArDark200() = shootDialog("left_channel", Look.AR_DARK, "حذف", fontScale = 2f) {
        copy(message = R.string.studio_delete_left_channel, action = DeleteAction.DELETE_ANYWAY)
    }

    @Test fun doneEnDark() = done(Look.EN_DARK)

    @Test fun doneEnLight() = done(Look.EN_LIGHT)

    @Test fun doneArDark() = done(Look.AR_DARK)

    @Test fun doneArLight() = done(Look.AR_LIGHT)

    private val unsure = started.copy(stage = DeletionStage.CONTENT_ERASED, receipt = "r", unsure = true)

    @Test fun bannerUnsureEnDark() = banner(Look.EN_DARK, unsure, name = "banner_unsure")

    @Test fun bannerUnsureArDark200() = banner(Look.AR_DARK, unsure, fontScale = 2f, name = "banner_unsure")

    @Test fun bannerUnsureArLight() = banner(Look.AR_LIGHT, unsure, name = "banner_unsure")

    private fun unsureSignedOut(look: Look) = signedOut("unsure_signed_out", look, onDismiss = {}) {
        aunString(R.string.studio_delete_unsure_signed_out, isolate("amina.yusuf@example.org"))
    }

    @Test fun unsureSignedOutEnLight() = unsureSignedOut(Look.EN_LIGHT)

    @Test fun unsureSignedOutArDark() = unsureSignedOut(Look.AR_DARK)

    @Test fun doneElsewhereEnDark() = signedOut("done_elsewhere", Look.EN_DARK) { aunString(R.string.studio_delete_done_elsewhere) }

    @Test fun doneElsewhereArLight() = signedOut("done_elsewhere", Look.AR_LIGHT) { aunString(R.string.studio_delete_done_elsewhere) }

    @Test fun openSignedOutEnLight() = signedOut("open_signed_out", Look.EN_LIGHT) {
        aunString(R.string.studio_delete_open_signed_out, isolate("amina.yusuf@example.org"))
    }

    @Test fun openSignedOutArDark() = signedOut("open_signed_out", Look.AR_DARK) {
        aunString(R.string.studio_delete_open_signed_out, isolate("amina.yusuf@example.org"))
    }
}
