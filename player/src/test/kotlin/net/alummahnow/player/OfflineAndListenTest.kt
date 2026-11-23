package net.alummahnow.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.player.offline.AvailabilitySyncDecision
import net.alummahnow.player.offline.MaxVideosExceededException
import net.alummahnow.player.offline.NetworkTransferAllowance
import net.alummahnow.player.offline.OfflineDownloadPolicy
import net.alummahnow.player.offline.OfflineDownloadQuality
import net.alummahnow.player.offline.OfflineDownloadSettings
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore
import net.alummahnow.player.offline.StorageBudgetExceededException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.media3.common.util.UnstableApi
class OfflineAndListenTest {
    private val fakeCatalog = object : CatalogRepository {
        private val sample = VideoSummary(
            id = "v-quran-1",
            title = LocalizedText("Surah Al-Kahf Recitation", mapOf("ar" to "تلاوة سورة الكهف")),
            thumbnailUrl = null,
            durationSeconds = 1800,
            views = 1200,
            channelId = "ch-1",
            channelName = "Al-Huda",
            publishedAt = "2026-11-23T00:00:00Z",
            category = "Quran"
        )
        private val lecture = VideoSummary(
            id = "v-lecture-2",
            title = LocalizedText("Seerah Lesson 1"),
            thumbnailUrl = null,
            durationSeconds = 2400,
            views = 500,
            channelId = "ch-2",
            channelName = "Seerah Series",
            publishedAt = "2026-11-23T00:00:00Z",
            category = "Lectures"
        )

        override suspend fun homeRails(): List<Rail> = listOf(
            Rail(RailKind.CONTINUE_WATCHING, listOf(lecture)),
            Rail(RailKind.RECENT, listOf(lecture, sample)),
            Rail(RailKind.TRENDING, listOf(sample))
        )

        override suspend fun video(id: String): VideoDetail = VideoDetail(
            summary = if (id == sample.id) sample else lecture,
            description = LocalizedText("desc"),
            playUrl = "https://cdn.example/$id/master.m3u8",
            availableResolutions = listOf("480p"),
            chapters = emptyList(),
            subtitles = emptyList(),
            likes = 0,
            tags = emptyList(),
            isLive = false,
            isReady = true
        )

        override suspend fun audioUrl(videoId: String): String =
            "https://media.alummahnow.net/pub/$videoId/rev1/audio.m4a"

        override suspend fun related(video: VideoDetail, limit: Int): List<VideoSummary> = emptyList()
        override suspend fun search(query: String, limit: Int): List<VideoSummary> =
            listOf(sample, lecture).filter { it.title.fallback.contains(query, ignoreCase = true) }
    }

    private fun saveItem(
        store: OfflineDownloadStore,
        userId: String,
        videoId: String,
        title: String,
        bytes: ByteArray,
        quality: OfflineDownloadQuality = OfflineDownloadQuality.AUDIO,
        thumbnailPath: String? = null
    ) = store.saveFromStream(
        userId = userId,
        videoId = videoId,
        title = title,
        thumbnailPath = thumbnailPath,
        quality = quality,
        input = ByteArrayInputStream(bytes)
    )

    @Test
    fun plainStoreIsolatesAccountsPlaysLocalFilesAndForbidsSignedUrls() {
        val dir = Files.createTempDirectory("aun-dl-store").toFile()
        val store = OfflineDownloadStore(
            rootDir = dir,
            offlineDownloadsEnabled = true,
            wallClockMs = { 10_000L }
        )
        assertThrows(IllegalArgumentException::class.java) {
            saveItem(
                store = store,
                userId = "u1",
                videoId = "v1",
                title = "Bad",
                bytes = "ok".toByteArray(),
                thumbnailPath = "https://media.alummahnow.net/priv/token/thumb.jpg"
            )
        }
        val payload1 = "plain-audio-u1".toByteArray()
        val payload2 = "plain-audio-u2".toByteArray()
        val rec1 = saveItem(store, "u1", "v1", "User 1", payload1)
        saveItem(store, "u2", "v1", "User 2", payload2)
        assertTrue(rec1.fileName.endsWith(".m4a"))
        assertEquals(1, store.playableDownloads("u1").size)
        assertEquals(1, store.playableDownloads("u2").size)

        val file1 = store.playableFile("u1", "v1")
        assertNotNull(file1)
        assertArrayEquals(payload1, file1!!.readBytes())
        assertArrayEquals(payload2, store.playableFile("u2", "v1")!!.readBytes())
    }

    @Test
    fun storeEnforcesStorageAndVideoCountCapsWithoutSilentEviction() {
        val dir = Files.createTempDirectory("aun-dl-caps").toFile()
        val store = OfflineDownloadStore(
            rootDir = dir,
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L }
        )
        store.updateSettings(OfflineDownloadSettings(maxStorageBytes = 500L, maxVideos = 2))
        saveItem(store, "u1", "v1", "Keep me", ByteArray(300) { 1 })
        assertThrows(StorageBudgetExceededException::class.java) {
            saveItem(store, "u1", "v2", "Too big", ByteArray(300) { 2 })
        }
        assertNotNull(store.findPlayableByVideoId("u1", "v1"))

        saveItem(store, "u1", "v2", "Fits", ByteArray(100) { 2 })
        assertThrows(MaxVideosExceededException::class.java) {
            saveItem(store, "u1", "v3", "Third", ByteArray(50) { 3 })
        }
        assertEquals(2, store.playableDownloads("u1").size)
        assertTrue(store.remove("u1", "v1"))
        assertEquals(1, store.playableDownloads("u1").size)
    }

    @Test
    fun availabilitySyncRemovesPrivateOrDeletedVideos() {
        val dir = Files.createTempDirectory("aun-dl-sync").toFile()
        val store = OfflineDownloadStore(
            rootDir = dir,
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L }
        )
        saveItem(store, "u1", "v-priv", "Going private", "abc".toByteArray())
        saveItem(store, "u2", "v-priv", "Going private", "def".toByteArray())
        saveItem(store, "u1", "v-ok", "Still public", "ghi".toByteArray())
        val fileBefore = store.playableFile("u1", "v-priv")!!
        assertTrue(fileBefore.exists())

        val deleted = store.applyAvailabilitySync(
            listOf(
                AvailabilitySyncDecision("v-priv", available = false, reason = "video_private"),
                AvailabilitySyncDecision("v-ok", available = true)
            )
        )
        assertEquals(2, deleted)
        assertFalse(fileBefore.exists())
        assertEquals(1, store.allDownloads("u1").size)
        assertTrue(store.allDownloads("u2").isEmpty())
    }

    @Test
    fun carBrowseTreeIsHiddenWhenListenModeOffAndPlaysOfflineAndAudioRenditionsWhenOn() = runBlocking {
        val dir = Files.createTempDirectory("aun-car").toFile()
        val store = OfflineDownloadStore(
            rootDir = dir,
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L }
        )
        saveItem(store, "u1", "v-offline", "Offline Tafsir", "offline audio".toByteArray())

        var listenOn = false
        val carTree = CarBrowseTree(
            catalog = fakeCatalog,
            downloadStore = store,
            listenModeEnabled = { listenOn },
            activeUserId = { "u1" },
            rootTitle = "Al Ummah Now",
            tabTitle = { it.name }
        )
        assertTrue(carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH).isEmpty())
        assertTrue(carTree.search("Tafsir", Locale.ENGLISH).isEmpty())

        listenOn = true
        assertEquals(4, carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH).size)
        val offlineItem = carTree.resolvePlayable("v-offline", audioOnly = true, locale = Locale.ENGLISH)
        assertEquals("file", offlineItem.localConfiguration?.uri?.scheme)
        assertTrue(offlineItem.localConfiguration?.uri?.path?.endsWith(".m4a") == true)
        val onlineAudio = carTree.resolvePlayable("v-quran-1", audioOnly = true, locale = Locale.ENGLISH)
        assertEquals("https://media.alummahnow.net/pub/v-quran-1/rev1/audio.m4a", onlineAudio.localConfiguration?.uri.toString())
    }

    @Test
    fun listenControllerSleepTimerQueueAndResumePersistence() {
        var now = 1_000L
        val controller = ListenModeController(listenModeEnabled = false, clockMs = { now })
        controller.setAudioOnly(true)
        assertFalse(controller.state.value.isAudioOnly)

        controller.setFeatureEnabled(true)
        controller.setAudioOnly(true)
        assertTrue(controller.state.value.isAudioOnly)
        controller.setSleepTimer(SleepTimerPreset.Minutes(15))
        now += 15 * 60_000L
        assertTrue(controller.tickSleepTimer())

        val resumeDir = Files.createTempDirectory("aun-resume").toFile()
        val resume = PlaybackResumeStore(File(resumeDir, "resume.json"))
        resume.save("v-quran-1", 123_456L)
        assertEquals(123_456L, resume.load()?.positionMs)
        assertEquals(
            NetworkTransferAllowance.BLOCKED_WIFI_ONLY,
            OfflineDownloadPolicy.canDownload(true, OfflineDownloadSettings(wifiOnly = true), true, false)
        )
    }
}
