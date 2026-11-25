package net.alummahnow.feature.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.core.bridge.studio.hidesFeature
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.analytics.AnalyticsViewModel
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.content.ContentViewModel
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.feature.studio.features.StudioFeaturesViewModel
import net.alummahnow.fixtures.BrandChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Owner rule 2026-11-16: a feature that is off, unconfigured or outside the tier is not there, and nothing says so. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HiddenFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    // --- Features ---

    @Test
    fun `features start hidden, then follow the server`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { features = UserFeatures(setOf("upload", "live")) }
        val vm = StudioFeaturesViewModel(repo, "c1")
        assertEquals(UserFeatures.NONE, vm.state.value)
        advanceUntilIdle()
        assertTrue(vm.state.value.upload)
        assertTrue("live" in vm.state.value)

        repo.features = UserFeatures.NONE
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.upload)
    }

    @Test
    fun `a failed read keeps the last answer, and hides everything when there is none`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { featuresError = CoreError.Network(IOException("offline")) }
        val vm = StudioFeaturesViewModel(repo, "c1")
        advanceUntilIdle()
        assertEquals(UserFeatures.NONE, vm.state.value)

        repo.featuresError = null
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.upload)

        repo.featuresError = CoreError.Network(IOException("offline"))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.upload)
        assertEquals(3, repo.featuresCalls)
    }

    @Test
    fun `feature_off, not supported and upgrade required all hide`() {
        assertTrue(StudioError.FeatureOff().hidesFeature())
        assertTrue(StudioError.NotSupported("restore").hidesFeature())
        assertTrue(StudioError.UpgradeRequired("upload").hidesFeature())
        assertFalse(StudioError.Forbidden().hidesFeature())
        assertFalse(CoreError.Http(429).hidesFeature())
    }

    // --- Recently deleted ---

    @Test
    fun `without soft delete there is no Recently deleted tab`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { softDelete = false }
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        assertFalse(ContentStatus.DELETED in vm.state.value.tabs)
        vm.setStatus(ContentStatus.DELETED)
        advanceUntilIdle()
        assertEquals(ContentStatus.PUBLISHED, vm.state.value.status)
        assertTrue(repo.queries.none { it.status == ContentStatus.DELETED })
    }

    @Test
    fun `with soft delete the Recently deleted tab is there`() = runTest(dispatcher) {
        val vm = ContentViewModel(FakeRepository(), "c1")
        advanceUntilIdle()
        assertTrue(ContentStatus.DELETED in vm.state.value.tabs)
    }

    @Test
    fun `restore answering feature_off removes the tab and its actions without a message`() = runTest(dispatcher) {
        val repo = FakeRepository()
        repo.deleted += sampleVideos[0].copy(status = ContentStatus.DELETED)
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.setStatus(ContentStatus.DELETED)
        advanceUntilIdle()
        vm.toggle(sampleVideos[0].id)
        repo.restoreError = StudioError.FeatureOff()
        vm.restore()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.softDelete)
        assertFalse(ContentStatus.DELETED in s.tabs)
        assertEquals(ContentStatus.PUBLISHED, s.status)
        assertNull(s.notice)
        assertNull(s.error)
        assertFalse(s.selecting)
    }

    @Test
    fun `the deleted list answering not supported goes back to Published quietly`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { deletedError = StudioError.NotSupported("deleted-videos") }
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.setStatus(ContentStatus.DELETED)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.softDelete)
        assertEquals(ContentStatus.PUBLISHED, s.status)
        assertNull(s.error)
        assertEquals(3, s.videos.size)
    }

    @Test
    fun `a plan refusal on bulk edit says nothing`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { bulkError = StudioError.UpgradeRequired("bulk") }
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.toggle("v1")
        vm.bulkEdit(net.alummahnow.core.bridge.studio.BulkChanges(isPublic = false))
        advanceUntilIdle()
        assertNull(vm.state.value.notice)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `Content shows Recently deleted only with soft delete`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var state by mutableStateOf(ContentUiState(videos = sampleVideos, loading = false))
        compose.setContent { AunTheme { ContentScreen(state, noop) } }
        compose.onNodeWithTag("status-published").assertExists()
        // Not in the row, and not under More either.
        compose.onNodeWithTag("status-more").performClick()
        compose.onNodeWithTag("status-failed").assertExists()
        compose.onAllNodesWithTag("status-deleted").assertCountEquals(0)
        state = state.copy(softDelete = true)
        compose.waitForIdle()
        compose.onNodeWithTag("status-deleted").assertExists()
    }

    // --- Analytics ---

    @Test
    fun `analytics answering feature_off hides the tab`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { analyticsError = StudioError.FeatureOff() }
        val vm = AnalyticsViewModel(repo, "c1")
        assertFalse(vm.state.value.visible)
        advanceUntilIdle()
        assertTrue(vm.state.value.hidden)
        assertFalse(vm.state.value.visible)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `analytics route missing hides the tab`() = runTest(dispatcher) {
        val vm = AnalyticsViewModel(FakeRepository().apply { analyticsError = StudioError.NotSupported("analytics") }, "c1")
        advanceUntilIdle()
        assertFalse(vm.state.value.visible)
    }

    @Test
    fun `analytics that loads shows the tab, and a dropped connection keeps it with a retry`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = AnalyticsViewModel(repo, "c1")
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)

        repo.analyticsError = CoreError.Network(IOException("offline"))
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)
        assertEquals(net.alummahnow.design.R.string.aun_error_network, vm.state.value.error)
    }

    // --- Dashboard ---

    @Test
    fun `dashboard has no upload card without uploads`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        compose.setContent { AunTheme { DashboardScreen(dashboard(canUpload = false), {}) } }
        compose.onNodeWithTag("dashboard").assertExists()
        compose.onAllNodesWithTag("dashboard-upload-ok").assertCountEquals(0)
        compose.onAllNodesWithText("invitation", substring = true).assertCountEquals(0)
    }

    @Test
    fun `dashboard has no upload note when uploads are on either`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        compose.setContent { AunTheme { DashboardScreen(dashboard(canUpload = true), {}) } }
        compose.onNodeWithTag("dashboard").assertExists()
        compose.onAllNodesWithTag("dashboard-upload-ok").assertCountEquals(0)
    }

    // The brand channel (BrandChannel): the tiles count the lists the cards show, one draft and one scheduled video.
    private fun dashboard(canUpload: Boolean) = DashboardUiState.Content(
        CreatorAccess(canUpload, false, null),
        BrandChannel.counts,
        recent = net.alummahnow.feature.studio.dashboard.DashboardSection.Ready(libraryVideos.take(3)),
        comments = net.alummahnow.feature.studio.dashboard.DashboardSection.Ready(BrandChannel.comments),
        drafts = net.alummahnow.feature.studio.dashboard.DashboardSection.Ready(listOf(BrandChannel.studioDraft)),
        scheduled = net.alummahnow.feature.studio.dashboard.DashboardSection.Ready(listOf(BrandChannel.studioScheduled))
    )

    private val noop = ContentActions(
        onStatus = {}, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {}, onEdit = {},
        onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )

    // --- Screenshots: the real screens without the hidden parts ---

    private val contentOff = ContentUiState(videos = libraryVideos, loading = false, softDelete = false)
    private val dashboardOff = @androidx.compose.runtime.Composable { DashboardScreen(dashboard(false), {}) }

    @Test fun contentNoDeletedEnLight() = shoot("content_no_deleted", Lang.EN, ThemeMode.LIGHT) { ContentScreen(contentOff, noop) }

    @Test fun contentNoDeletedEnDark() = shoot("content_no_deleted", Lang.EN, ThemeMode.DARK) { ContentScreen(contentOff, noop) }

    @Test fun contentNoDeletedArLight() = shoot("content_no_deleted", Lang.AR, ThemeMode.LIGHT) { ContentScreen(contentOff, noop) }

    @Test fun contentNoDeletedArDark() = shoot("content_no_deleted", Lang.AR, ThemeMode.DARK) { ContentScreen(contentOff, noop) }

    @Test fun dashboardNoUploadEnLight() = shoot("dashboard_no_upload", Lang.EN, ThemeMode.LIGHT, content = dashboardOff)

    @Test fun dashboardNoUploadEnDark() = shoot("dashboard_no_upload", Lang.EN, ThemeMode.DARK, content = dashboardOff)

    @Test fun dashboardNoUploadArLight() = shoot("dashboard_no_upload", Lang.AR, ThemeMode.LIGHT, content = dashboardOff)

    @Test fun dashboardNoUploadArDark() = shoot("dashboard_no_upload", Lang.AR, ThemeMode.DARK, content = dashboardOff)

    @Test fun dashboardNoUploadTabletArDark() = shoot("dashboard_no_upload", Lang.AR, ThemeMode.DARK, Device.TABLET, dashboardOff)
}
