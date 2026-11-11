package net.alummahnow.feature.upload

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.core.bridge.studio.Visibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val zone = ZoneId.of("Asia/Riyadh")

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun `title tokens`() {
        assertEquals("Fajr reminder", TitlePattern.apply("{{filename}}", "Fajr reminder.mp4", 0, 1, today, "Lectures"))
        assertEquals("Tafsir 03 of 12 · 2026-11-10", TitlePattern.apply("Tafsir {{nn}} of {{total}} · {{date}}", "a.mp4", 2, 12, today, "C"))
        assertEquals("Lectures part 2", TitlePattern.apply("{{channel_name}} part {{n}}", "x.mov", 1, 3, today, "Lectures"))
        assertEquals("{{oops}} a", TitlePattern.apply("{{oops}} {{filename}}", "a.mp4", 0, 1, today, "C"))
        assertEquals("clip", TitlePattern.apply("  ", "clip.mp4", 0, 1, today, "C"))
        assertEquals(TitlePattern.MAX_TITLE, TitlePattern.apply("{{filename}}", "x".repeat(300) + ".mp4", 0, 1, today, "C").length)
    }

    @Test
    fun `tags split on Latin and Arabic commas, deduplicated`() {
        assertEquals(listOf("fiqh", "سيرة", "Ramadan"), TitlePattern.tags("fiqh, سيرة،Ramadan , FIQH,,"))
    }

    @Test
    fun `batch drafts carry the set-for-all values and per-file titles`() = runTest {
        val queue = FakeQueue()
        val vm = UploadViewModel(queue, FakeRepo(), "c1", "Lectures", { today }, { now }, { zone })
        assertEquals(listOf("p1"), vm.state.value.playlists.map { it.id })
        vm.addFiles(listOf(file("a.mp4"), file("b.mp4"), file("a.mp4")))
        assertEquals(2, vm.state.value.files.size)
        vm.updateForm { it.copy(titlePattern = "Dars {{n}}", description = "d", category = "fiqh", language = "ar", visibility = Visibility.PRIVATE, tagsText = "x, y") }
        vm.togglePlaylist("p1")
        vm.setTitle(1, "Custom title")
        assertEquals(listOf("Dars 1", "Custom title"), vm.titles())
        vm.start()
        val d = queue.drafts.single()
        assertEquals(listOf("Dars 1", "Custom title"), d.map { it.title })
        assertEquals(setOf("c1"), d.map { it.channelId }.toSet())
        assertEquals(Visibility.PRIVATE, d[0].visibility)
        assertEquals("fiqh", d[0].category)
        assertEquals("ar", d[0].language)
        assertEquals(listOf("x", "y"), d[0].tags)
        assertEquals(listOf("p1"), d[1].playlistIds)
        assertNull(d[0].scheduleAtIso)
        assertEquals(2, vm.state.value.queuedCount)
        assertEquals(0, vm.state.value.files.size)
    }

    @Test
    fun `schedule must be 5 minutes to a year ahead and is sent in UTC with its zone`() = runTest {
        val queue = FakeQueue()
        val vm = UploadViewModel(queue, FakeRepo(), "c1", "C", { today }, { now }, { zone })
        vm.addFiles(listOf(file("a.mp4")))
        vm.updateForm { it.copy(scheduleAt = ZonedDateTime.ofInstant(now.plusSeconds(60), zone)) }
        vm.start()
        assertEquals(R.string.upload_error_schedule_soon, vm.state.value.error)
        vm.updateForm { it.copy(scheduleAt = ZonedDateTime.ofInstant(now, zone).plusYears(2)) }
        vm.start()
        assertEquals(R.string.upload_error_schedule_far, vm.state.value.error)
        val at = vm.defaultScheduleTime()
        assertEquals(9, at.hour)
        vm.updateForm { it.copy(scheduleAt = at) }
        vm.start()
        val d = queue.drafts.single().single()
        assertEquals(at.toInstant().toString(), d.scheduleAtIso)
        assertEquals("Asia/Riyadh", d.timezone)
    }

    @Test
    fun `no files or more than 15 are refused`() {
        val vm = UploadViewModel(FakeQueue(), FakeRepo(), "c1", "C", { today }, { now }, { zone })
        vm.start()
        assertEquals(R.string.upload_error_no_files, vm.state.value.error)
        vm.addFiles((1..20).map { file("v$it.mp4") })
        assertEquals(UploadViewModel.MAX_FILES, vm.state.value.files.size)
        assertEquals(R.string.upload_error_too_many, vm.state.value.error)
    }

    private fun file(name: String) = PickedFile("content://media/$name", name, 1_000_000, "video/mp4")
}

private class FakeQueue : UploadQueue {
    override val items = MutableStateFlow<List<UploadItem>>(emptyList())
    val drafts = mutableListOf<List<UploadDraft>>()
    override suspend fun enqueue(drafts: List<UploadDraft>) {
        this.drafts += drafts
    }
    override fun pause(id: String) = Unit
    override fun resume(id: String) = Unit
    override fun retry(id: String) = Unit
    override suspend fun cancel(id: String) = Unit
    override fun remove(id: String) = Unit
}

private class FakeRepo : StudioRepository {
    override suspend fun playlists(channelId: String) = listOf(StudioPlaylist("p1", "Series", true))
    override suspend fun channels() = error("unused")
    override suspend fun creatorAccess() = error("unused")
    override suspend fun content(query: ContentQuery) = error("unused")
    override suspend fun counts(channelId: String) = error("unused")
    override suspend fun suggestions(channelId: String, text: String) = error("unused")
    override suspend fun video(id: String) = error("unused")
    override suspend fun update(id: String, channelId: String, edit: VideoEdit) = error("unused")
    override suspend fun bulkUpdate(ids: List<String>, changes: BulkChanges) = error("unused")
    override suspend fun softDeleteSupported(channelId: String) = error("unused")
    override suspend fun delete(ids: List<String>, channelId: String) = error("unused")
    override suspend fun restore(ids: List<String>, channelId: String) = error("unused")
    override suspend fun deletePermanently(id: String, channelId: String) = error("unused")
    override suspend fun schedule(videoId: String, channelId: String, publishAtIso: String, timezone: String) = error("unused")
    override suspend fun addToPlaylists(videoId: String, channelId: String, playlistIds: List<String>, orderKey: String) = error("unused")
    override suspend fun analytics(channelId: String, range: AnalyticsRange) = error("unused")
}
