package net.alummahnow.feature.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.HeldComment
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.analytics.AnalyticsViewModel
import net.alummahnow.feature.studio.content.ContentViewModel
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardSection
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.feature.studio.dashboard.DashboardViewModel
import net.alummahnow.fixtures.BrandChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Owner review 2026-11-16: no hollow Dashboard, no endless spinners. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private fun repoWithEverything() = FakeRepository().apply {
        videos += StudioVideo("p1", "Khutbah, processing", status = ContentStatus.PROCESSING, createdAt = "2026-11-15T10:00:00Z")
        videos += StudioVideo("d1", "Draft about patience", status = ContentStatus.DRAFTS, isDraft = true)
        videos += StudioVideo("s1", "Friday reminder", status = ContentStatus.SCHEDULED)
    }

    private fun content(vm: DashboardViewModel) = vm.state.value as DashboardUiState.Content

    // --- Sections ---

    @Test
    fun `a video in review or not approved is a recent upload, and is asked for only when there is one`() = runTest(dispatcher) {
        val none = repoWithEverything()
        DashboardViewModel(none, "c1")
        advanceUntilIdle()
        assertTrue(none.queries.none { it.status == ContentStatus.IN_REVIEW || it.status == ContentStatus.NOT_APPROVED })
        val repo = repoWithEverything().apply {
            videos += StudioVideo("r1", "Held", status = ContentStatus.IN_REVIEW, createdAt = "2026-11-17T10:00:00Z")
            videos += StudioVideo("n1", "Turned down", status = ContentStatus.NOT_APPROVED, createdAt = "2026-11-16T10:00:00Z")
        }
        val vm = DashboardViewModel(repo, "c1")
        advanceUntilIdle()
        assertEquals(listOf("r1", "n1", "p1"), (content(vm).recent as DashboardSection.Ready).value.take(3).map { it.id })
    }

    @Test
    fun `the recent lists don't wait for the counts`() = runTest(dispatcher) {
        val repo = repoWithEverything().apply { countsGate = CompletableDeferred() }
        DashboardViewModel(repo, "c1")
        advanceUntilIdle()
        assertTrue(repo.queries.map { it.status }.containsAll(listOf(ContentStatus.PROCESSING, ContentStatus.PUBLISHED)))
    }

    @Test
    fun `the counts have In review and Not approved only with a video in them`() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w1280dp-h800dp-land")
        var state by mutableStateOf(full(canUpload = true))
        compose.setContent { AunTheme { DashboardScreen(state, {}) } }
        compose.onNodeWithText("In review").assertDoesNotExist()
        compose.onNodeWithText("Not approved").assertDoesNotExist()
        state = state.copy(counts = BrandChannel.counts.copy(inReview = 2, notApproved = 1))
        compose.waitForIdle()
        compose.onNodeWithText("In review").assertIsDisplayed()
        compose.onNodeWithText("Not approved").assertIsDisplayed()
        val top = { label: String -> compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(top("In review"), top("Failed"))
        assertTrue(top("In review") > top("Processing")) // four and three, not five and two
    }

    @Test
    fun `the dashboard has recent videos, comments, drafts and scheduled`() = runTest(dispatcher) {
        val comment = HeldComment("first", "First", "Author", "user", "video", "target", false)
        val comments = CommentsSummary(2, 14, listOf(comment, comment.copy(id = "second")))
        val vm = DashboardViewModel(repoWithEverything().apply { this.comments = comments }, "c1")
        advanceUntilIdle()
        val s = content(vm)
        val recent = (s.recent as DashboardSection.Ready).value
        assertEquals("p1", recent.first().id) // newest first, processing included with its status
        assertTrue(recent.size <= 5)
        assertEquals(comments, (s.comments as DashboardSection.Ready).value)
        assertEquals(listOf("d1"), (s.drafts as DashboardSection.Ready).value.map { it.id })
        assertEquals(listOf("s1"), (s.scheduled as DashboardSection.Ready).value.map { it.id })
        assertFalse(s.nothingLeft)
    }

    @Test
    fun `without uploads the dashboard keeps its other sections`() = runTest(dispatcher) {
        val vm = DashboardViewModel(repoWithEverything().apply { canUpload = false }, "c1")
        advanceUntilIdle()
        val s = content(vm)
        assertFalse(s.access!!.canUpload)
        assertTrue(s.recent is DashboardSection.Ready)
        assertTrue(s.drafts is DashboardSection.Ready)
        assertFalse(s.nothingLeft)
    }

    @Test
    fun `no comment role means no comments section`() = runTest(dispatcher) {
        val vm = DashboardViewModel(FakeRepository().apply { commentsError = StudioError.Forbidden() }, "c1")
        advanceUntilIdle()
        assertEquals(DashboardSection.Hidden, content(vm).comments)
    }

    @Test
    fun `sections with no data are plain empty states, not hidden`() = runTest(dispatcher) {
        val vm = DashboardViewModel(FakeRepository().apply { comments = CommentsSummary(0, 0) }, "c1")
        advanceUntilIdle()
        val s = content(vm)
        assertEquals(DashboardSection.Ready(emptyList<StudioVideo>()), s.drafts)
        assertEquals(DashboardSection.Ready(emptyList<StudioVideo>()), s.scheduled)
        assertEquals(DashboardSection.Ready(CommentsSummary(0, 0)), s.comments)
    }

    @Test
    fun `a section that fails says so and is not hidden`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { contentErrors[ContentStatus.DRAFTS] = CoreError.Network(IOException("offline")) }
        val vm = DashboardViewModel(repo, "c1")
        advanceUntilIdle()
        assertEquals(DashboardSection.Failed(net.alummahnow.design.R.string.aun_error_network), content(vm).drafts)
    }

    @Test
    fun `nothing left when every section is off and uploads are off`() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            canUpload = false
            countsMissing = true
            commentsError = StudioError.FeatureOff()
            ContentStatus.entries.forEach { contentErrors[it] = StudioError.FeatureOff() }
        }
        val vm = DashboardViewModel(repo, "c1")
        advanceUntilIdle()
        assertTrue(content(vm).nothingLeft)
    }

    // --- Every load ends: feature_off, 404, error, cancellation ---

    private val outcomes: List<Pair<String, Exception>> = listOf(
        "feature_off" to StudioError.FeatureOff(),
        "404" to CoreError.NotFound("route"),
        "error" to CoreError.Http(500),
        "cancellation" to CancellationException("timed out")
    )

    @Test
    fun `dashboard loading ends for every outcome`() = runTest(dispatcher) {
        outcomes.forEach { (name, e) ->
            val repo = FakeRepository().apply {
                countsError = e
                commentsError = e
                ContentStatus.entries.forEach { contentErrors[it] = e }
            }
            val vm = DashboardViewModel(repo, "c1")
            advanceUntilIdle()
            assertTrue(name, vm.state.value != DashboardUiState.Loading)
        }
    }

    @Test
    fun `analytics loading ends for every outcome`() = runTest(dispatcher) {
        outcomes.forEach { (name, e) ->
            val vm = AnalyticsViewModel(FakeRepository().apply { analyticsError = e }, "c1")
            advanceUntilIdle()
            assertFalse(name, vm.state.value.loading)
            assertTrue(name, vm.state.value.checked)
        }
    }

    @Test
    fun `analytics hides on feature_off and 404, and keeps the tab with a retry on error and cancellation`() = runTest(dispatcher) {
        val hidden = outcomes.associate { (name, e) ->
            val vm = AnalyticsViewModel(FakeRepository().apply { analyticsError = e }, "c1")
            advanceUntilIdle()
            name to vm.state.value.hidden
        }
        assertEquals(mapOf("feature_off" to true, "404" to true, "error" to false, "cancellation" to false), hidden)
    }

    @Test
    fun `content loading ends for every outcome`() = runTest(dispatcher) {
        outcomes.forEach { (name, e) ->
            val vm = ContentViewModel(FakeRepository().apply { contentErrors[ContentStatus.PUBLISHED] = e }, "c1")
            advanceUntilIdle()
            assertFalse(name, vm.state.value.loading)
        }
    }

    @Test
    fun `a newer load replaces a cancelled one without an error`() = runTest(dispatcher) {
        val vm = DashboardViewModel(FakeRepository(), "c1")
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value is DashboardUiState.Content)
    }

    // --- UI ---

    // The brand channel (BrandChannel, shared with the Content list and the viewer): the tiles count what the cards show.
    private fun full(canUpload: Boolean) = DashboardUiState.Content(
        access = CreatorAccess(canUpload, false, null),
        counts = BrandChannel.counts,
        recent = DashboardSection.Ready(libraryVideos.take(3)),
        comments = DashboardSection.Ready(BrandChannel.comments),
        drafts = DashboardSection.Ready(listOf(BrandChannel.studioDraft)),
        scheduled = DashboardSection.Ready(listOf(BrandChannel.studioScheduled))
    )

    @Test
    fun `the upload-off dashboard is not hollow`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var opened: String? = null
        compose.setContent { AunTheme { DashboardScreen(full(canUpload = false), {}, onOpenVideo = { opened = it }) } }
        compose.onAllNodesWithTag("dashboard-upload-ok").assertCountEquals(0)
        compose.onNodeWithTag("dashboard-recent").assertExists()
        compose.onNodeWithTag("dashboard-comments").performScrollTo()
        compose.onNodeWithTag("dashboard-drafts").performScrollTo()
        compose.onNodeWithTag("dashboard-scheduled").performScrollTo()
        compose.onNodeWithTag("dashboard-video-${BrandChannel.kahf.id}").performScrollTo().performClick()
        assertEquals(BrandChannel.kahf.id, opened)
    }

    @Test
    fun `a hidden section is not drawn`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        compose.setContent { AunTheme { DashboardScreen(full(true).copy(comments = DashboardSection.Hidden), {}) } }
        compose.onAllNodesWithTag("dashboard-comments").assertCountEquals(0)
    }

    // --- Screenshots: what a person sees in the viewport ---

    private val on = @androidx.compose.runtime.Composable { DashboardScreen(full(canUpload = true), {}) }
    private val off = @androidx.compose.runtime.Composable { DashboardScreen(full(canUpload = false), {}) }

    @Test fun dashboardSectionsEnDark() = shoot("dashboard_sections", Lang.EN, ThemeMode.DARK, content = on)

    @Test fun dashboardSectionsArLight() = shoot("dashboard_sections", Lang.AR, ThemeMode.LIGHT, content = on)

    private val reviewed = @androidx.compose.runtime.Composable {
        DashboardScreen(full(canUpload = true).copy(counts = BrandChannel.counts.copy(inReview = 2, notApproved = 1)), {})
    }

    @Test fun dashboardReviewEnLight() = shoot("dashboard_review", Lang.EN, ThemeMode.LIGHT, content = reviewed)

    @Test fun dashboardReviewArDark() = shoot("dashboard_review", Lang.AR, ThemeMode.DARK, content = reviewed)

    @Test fun dashboardReviewTabletArLight() = shoot("dashboard_review", Lang.AR, ThemeMode.LIGHT, Device.TABLET, reviewed)

    @Test fun dashboardSectionsNoUploadEnLight() = shoot("dashboard_sections_no_upload", Lang.EN, ThemeMode.LIGHT, content = off)

    @Test fun dashboardSectionsNoUploadArDark() = shoot("dashboard_sections_no_upload", Lang.AR, ThemeMode.DARK, content = off)

    @Test fun dashboardSectionsNoUploadTabletEnDark() =
        shoot("dashboard_sections_no_upload", Lang.EN, ThemeMode.DARK, Device.TABLET, off)
}
