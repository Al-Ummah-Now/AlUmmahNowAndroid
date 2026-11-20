package net.alummahnow.feature.studio

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
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
    fun `the dashboard has recent videos, comments, drafts and scheduled`() = runTest(dispatcher) {
        val vm = DashboardViewModel(repoWithEverything(), "c1")
        advanceUntilIdle()
        val s = content(vm)
        val recent = (s.recent as DashboardSection.Ready).value
        assertEquals("p1", recent.first().id) // newest first, processing included with its status
        assertTrue(recent.size <= 5)
        assertEquals(CommentsSummary(2, 14), (s.comments as DashboardSection.Ready).value)
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

    private val recent = listOf(
        StudioVideo(
            "p1",
            "Friday khutbah: patience",
            titleLocalized = mapOf("ar" to "خطبة الجمعة: الصبر"),
            thumbnailUrl = "${FIXTURE_THUMBS}khutbah.jpg",
            durationSeconds = 1_260.0,
            status = ContentStatus.PROCESSING,
            createdAt = "2026-11-15T10:00:00Z"
        ),
        StudioVideo(
            "v1",
            "Introduction to Tafsir",
            titleLocalized = mapOf("ar" to "مقدمة في التفسير"),
            thumbnailUrl = "${FIXTURE_THUMBS}tafsir.jpg",
            durationSeconds = 1_830.0,
            status = ContentStatus.PUBLISHED,
            createdAt = "2026-11-12T10:00:00Z"
        ),
        StudioVideo(
            "v2",
            "Seerah overview",
            titleLocalized = mapOf("ar" to "نظرة عامة على السيرة"),
            thumbnailUrl = "${FIXTURE_THUMBS}seerah.jpg",
            durationSeconds = 600.0,
            status = ContentStatus.PUBLISHED,
            createdAt = "2026-11-09T10:00:00Z"
        )
    )
    private val drafts =
        listOf(
            StudioVideo(
                "d1",
                "Patience in hardship",
                titleLocalized = mapOf("ar" to "الصبر عند الشدائد"),
                thumbnailUrl = "${FIXTURE_THUMBS}patience.jpg",
                durationSeconds = 980.0,
                status = ContentStatus.DRAFTS,
                isDraft = true
            )
        )

    private fun full(canUpload: Boolean) = DashboardUiState.Content(
        access = CreatorAccess(canUpload, false, null),
        counts = ContentCounts(24, 2, 3, 1, 0),
        recent = DashboardSection.Ready(recent),
        comments = DashboardSection.Ready(CommentsSummary(3, 41)),
        drafts = DashboardSection.Ready(drafts),
        scheduled = DashboardSection.Ready(emptyList())
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
        compose.onNodeWithTag("dashboard-scheduled-empty").performScrollTo()
        compose.onNodeWithTag("dashboard-video-p1").performScrollTo().performClick()
        assertEquals("p1", opened)
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

    @Test fun dashboardSectionsNoUploadEnLight() = shoot("dashboard_sections_no_upload", Lang.EN, ThemeMode.LIGHT, content = off)

    @Test fun dashboardSectionsNoUploadArDark() = shoot("dashboard_sections_no_upload", Lang.AR, ThemeMode.DARK, content = off)

    @Test fun dashboardSectionsNoUploadTabletEnDark() =
        shoot("dashboard_sections_no_upload", Lang.EN, ThemeMode.DARK, Device.TABLET, off)
}
