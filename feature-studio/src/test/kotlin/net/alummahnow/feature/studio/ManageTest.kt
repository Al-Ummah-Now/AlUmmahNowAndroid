package net.alummahnow.feature.studio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.analytics.AnalyticsScreen
import net.alummahnow.feature.studio.analytics.AnalyticsUiState
import net.alummahnow.feature.studio.analytics.AnalyticsViewModel
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentNotice
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.content.ContentViewModel
import net.alummahnow.feature.studio.edit.EditForm
import net.alummahnow.feature.studio.edit.VideoEditScreen
import net.alummahnow.feature.studio.edit.VideoEditUiState
import net.alummahnow.feature.studio.edit.VideoEditViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ManageTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun `content loads per tab and search is debounced`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        assertEquals(3, vm.state.value.videos.size)
        vm.setQuery("ram")
        vm.setQuery("rama")
        advanceUntilIdle()
        assertEquals(listOf("v3"), vm.state.value.videos.map { it.id })
        assertEquals("Ramadan reminders", vm.state.value.suggestions.single().text)
        assertEquals(1, repo.queries.count { it.text == "rama" })
        assertTrue(repo.queries.none { it.text == "ram" })
        vm.setStatus(ContentStatus.DELETED)
        advanceUntilIdle()
        assertTrue(vm.state.value.videos.isEmpty())
    }

    @Test
    fun `bulk edit, delete and restore`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.toggle("v1")
        vm.toggle("v2")
        vm.bulkEdit(BulkChanges(isPublic = false, addTags = listOf("new")))
        advanceUntilIdle()
        assertEquals(listOf("v1", "v2"), repo.bulkCalls.single().first)
        assertEquals(ContentNotice.Updated(2, 0), vm.state.value.notice)
        assertFalse(vm.state.value.selecting)
        assertTrue(repo.videos.first { it.id == "v1" }.tags.contains("new"))

        vm.toggle("v1")
        vm.delete()
        advanceUntilIdle()
        assertEquals(ContentNotice.Deleted(1, 0), vm.state.value.notice)
        assertTrue(vm.state.value.videos.none { it.id == "v1" })
        assertTrue(vm.state.value.softDelete)

        vm.setStatus(ContentStatus.DELETED)
        advanceUntilIdle()
        assertEquals(listOf("v1"), vm.state.value.videos.map { it.id })
        vm.toggle("v1")
        vm.restore()
        advanceUntilIdle()
        assertEquals(ContentNotice.Restored(1, 0), vm.state.value.notice)
        assertTrue(repo.videos.any { it.id == "v1" })
    }

    @Test
    fun `selection is capped at 50`() = runTest(dispatcher) {
        val repo = FakeRepository()
        repo.videos.clear()
        repo.videos += (1..60).map { sampleVideos[0].copy(id = "x$it") }
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.selectAll()
        assertEquals(BulkChanges.MAX_BULK, vm.state.value.selected.size)
    }

    @Test
    fun `edit sends only what changed`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = VideoEditViewModel(repo, "c1", "v1")
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)
        vm.update { it.copy(title = "  ") }
        vm.save()
        assertEquals(R.string.studio_title_required, vm.state.value.error)
        vm.update { it.copy(title = "New title", isPublic = false, tagsText = "tafsir, quran") }
        assertTrue(vm.state.value.dirty)
        vm.save()
        advanceUntilIdle()
        val v = repo.videos.first { it.id == "v1" }
        assertEquals("New title", v.title)
        assertFalse(v.isPublic)
        assertEquals(listOf("tafsir", "quran"), v.tags)
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `analytics reloads on range change`() = runTest(dispatcher) {
        val vm = AnalyticsViewModel(FakeRepository(), "c1")
        advanceUntilIdle()
        assertEquals(13607L, vm.state.value.data?.totalViews)
        vm.setRange(AnalyticsRange.LAST_90)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
    }

    // --- UI behaviour ---

    private fun actions(log: MutableList<String>) = ContentActions(
        onStatus = { log += "status:$it" }, onQuery = {}, onPickSuggestion = {}, onToggle = { log += "toggle:$it" },
        onSelectAll = {}, onClearSelection = {}, onEdit = { log += "edit:$it" }, onBulkEdit = {}, onDelete = { log += "delete" },
        onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )

    @Test
    fun `delete asks first and Cancel keeps the videos`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val log = mutableListOf<String>()
        compose.setContent { AunTheme { ContentScreen(ContentUiState(videos = sampleVideos, selected = setOf("v1"), loading = false), actions(log)) } }
        compose.onNodeWithTag("bulk-delete").performClick()
        compose.onNodeWithTag("confirm-cancel").performClick()
        assertTrue("delete" !in log)
        compose.onNodeWithTag("bulk-delete").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        assertTrue("delete" in log)
    }

    @Test
    fun `tap edits, long press selects`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val log = mutableListOf<String>()
        compose.setContent { AunTheme { ContentScreen(ContentUiState(videos = sampleVideos, loading = false), actions(log)) } }
        compose.onNodeWithTag("video-v2").performClick()
        compose.onNodeWithTag("video-v3").performTouchInput { longClick() }
        assertEquals(listOf("edit:v2", "toggle:v3"), log)
    }

    // --- Screenshots ---

    private val list = ContentUiState(videos = sampleVideos, loading = false)
    private val selecting = list.copy(selected = setOf("v1", "v3"))
    private val deletedTab = ContentUiState(status = ContentStatus.DELETED, loading = false, videos = sampleVideos.take(2).map { it.copy(status = ContentStatus.DELETED, restorableUntil = "2026-12-10T09:00:00Z") })
    private val noop = actions(mutableListOf())
    private val form = EditForm("Introduction to Tafsir", "Lesson one of the series.", true, "quran_tafsir", "ar", "tafsir, قرآن")
    private val edit = VideoEditUiState(loading = false, original = form, form = form.copy(title = "Introduction to Tafsir (revised)"))

    @Test fun contentEnLight() = shoot("content", Lang.EN, ThemeMode.LIGHT) { ContentScreen(list, noop) }

    @Test fun contentArDark() = shoot("content", Lang.AR, ThemeMode.DARK) { ContentScreen(list, noop) }

    @Test fun contentSelectingEnDark() = shoot("content_selecting", Lang.EN, ThemeMode.DARK) { ContentScreen(selecting, noop) }

    @Test fun contentSelectingArLight() = shoot("content_selecting", Lang.AR, ThemeMode.LIGHT) { ContentScreen(selecting, noop) }

    @Test fun contentDeletedArDark() = shoot("content_deleted", Lang.AR, ThemeMode.DARK) { ContentScreen(deletedTab, noop) }

    @Test fun contentTabletEnLight() = shoot("content", Lang.EN, ThemeMode.LIGHT, Device.TABLET) { ContentScreen(list, noop) }

    @Test fun editEnLight() = shoot("edit", Lang.EN, ThemeMode.LIGHT) { VideoEditScreen(edit, {}, {}, {}, {}) }

    @Test fun editArDark() = shoot("edit", Lang.AR, ThemeMode.DARK) { VideoEditScreen(edit, {}, {}, {}, {}) }

    @Test fun analyticsEnDark() = shoot("analytics", Lang.EN, ThemeMode.DARK) { AnalyticsScreen(AnalyticsUiState(loading = false, data = sampleAnalytics), {}, {}) }

    @Test fun analyticsArLight() = shoot("analytics", Lang.AR, ThemeMode.LIGHT) { AnalyticsScreen(AnalyticsUiState(loading = false, data = sampleAnalytics), {}, {}) }

    @Test fun analyticsTabletArDark() = shoot("analytics", Lang.AR, ThemeMode.DARK, Device.TABLET) { AnalyticsScreen(AnalyticsUiState(loading = false, data = sampleAnalytics), {}, {}) }
}
