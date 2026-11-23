package net.alummahnow.player

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.player.offline.AunDl01Format
import net.alummahnow.player.offline.AunDl01RandomAccessReader
import net.alummahnow.player.offline.AunDl01StreamWriter
import net.alummahnow.player.offline.InMemoryDownloadKeyVault
import net.alummahnow.player.offline.LicenseRefreshDecision
import net.alummahnow.player.offline.LicenseSlotReleaser
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
        licenseId: String,
        videoId: String,
        title: String,
        bytes: ByteArray,
        serverTimeMs: Long = 10_000L,
        expiresAtMs: Long = 90_000L,
        thumbnailPath: String? = null
    ) = store.saveFromStream(
        userId = userId,
        licenseId = licenseId,
        videoId = videoId,
        title = title,
        thumbnailPath = thumbnailPath,
        quality = OfflineDownloadQuality.AUDIO,
        serverTimeMs = serverTimeMs,
        expiresAtMs = expiresAtMs,
        input = ByteArrayInputStream(bytes)
    )

    @Test
    fun aundl01StreamingEncryptRandomAccessAndTruncationRejection() {
        val dir = Files.createTempDirectory("aun-dl-cipher").toFile()
        val vault = InMemoryDownloadKeyVault()
        val payload = ByteArray(AunDl01Format.CHUNK_SIZE + 5000) { i -> (i * 31).toByte() }
        val enc = File(dir, "clip.aun")
        AunDl01StreamWriter.encryptStream(vault.secretKey(), "vid-1", "lic-1", ByteArrayInputStream(payload), enc)

        AunDl01RandomAccessReader.open(vault.secretKey(), enc, "vid-1", "lic-1").use { reader ->
            assertEquals(payload.size.toLong(), reader.plainLen)
            val slice = reader.readRange((AunDl01Format.CHUNK_SIZE - 100).toLong(), 250)
            assertArrayEquals(payload.copyOfRange(AunDl01Format.CHUNK_SIZE - 100, AunDl01Format.CHUNK_SIZE + 150), slice)
        }
        assertThrows(IOException::class.java) {
            AunDl01RandomAccessReader.open(vault.secretKey(), enc, "vid-other", "lic-1")
        }
        assertThrows(IOException::class.java) {
            AunDl01RandomAccessReader.open(vault.secretKey(), enc, "vid-1", "lic-other")
        }
        val raw = enc.readBytes()
        val hdrLen = 16 + 2 + "vid-1".length + 2 + "lic-1".length
        val truncated = File(dir, "trunc.aun").apply { writeBytes(raw.copyOfRange(0, hdrLen + AunDl01Format.CHUNK_STRIDE)) }
        assertThrows(IOException::class.java) {
            AunDl01RandomAccessReader.open(vault.secretKey(), truncated, "vid-1", "lic-1")
        }
    }

    @Test
    fun storeIsolatesAccountsBlocksClockRollbackAndForbidsSignedUrls() {
        val dir = Files.createTempDirectory("aun-dl-store").toFile()
        var wall = 10_000L
        var mono = 1_000L
        val store = OfflineDownloadStore(
            rootDir = dir,
            keyVault = InMemoryDownloadKeyVault(),
            offlineDownloadsEnabled = true,
            wallClockMs = { wall },
            monotonicClockMs = { mono }
        )
        assertThrows(IllegalArgumentException::class.java) {
            saveItem(
                store = store,
                userId = "u1",
                licenseId = "lic-bad",
                videoId = "v1",
                title = "Bad",
                bytes = "ok".toByteArray(),
                thumbnailPath = "https://media.alummahnow.net/priv/token/thumb.jpg"
            )
        }
        saveItem(store, "u1", "lic-u1", "v1", "User 1", "user1".toByteArray(), expiresAtMs = 20_000L)
        saveItem(store, "u2", "lic-u2", "v1", "User 2", "user2".toByteArray(), expiresAtMs = 90_000L)
        assertEquals(1, store.playableDownloads("u1").size)
        assertEquals(1, store.playableDownloads("u2").size)

        wall = 1_000L
        mono = 15_000L
        assertNull(store.findPlayableByVideoId("u1", "v1"))
        assertNotNull(store.findPlayableByVideoId("u2", "v1"))
    }

    @Test
    fun storeReleasesServerSlotsAndNeverSilentlyEvictsUserDownloads() {
        val dir = Files.createTempDirectory("aun-dl-slots").toFile()
        val released = mutableListOf<String>()
        val releaser = object : LicenseSlotReleaser {
            override fun releaseLicense(licenseId: String): Boolean {
                released.add(licenseId)
                return true
            }
        }
        val store = OfflineDownloadStore(
            rootDir = dir,
            keyVault = InMemoryDownloadKeyVault(),
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L },
            monotonicClockMs = { 100L },
            slotReleaser = releaser
        )
        store.updateSettings(OfflineDownloadSettings(maxStorageBytes = 500L))
        saveItem(store, "u1", "lic-1", "v1", "Keep me", ByteArray(300) { 1 }, serverTimeMs = 1_000L)
        assertThrows(StorageBudgetExceededException::class.java) {
            saveItem(store, "u1", "lic-2", "v2", "Too big", ByteArray(300) { 2 }, serverTimeMs = 1_000L)
        }
        assertNotNull(store.findPlayableByVideoId("u1", "v1"))
        assertTrue(store.remove("u1", "v1"))
        assertEquals(listOf("lic-1"), released)
    }

    @Test
    fun storeRefreshRevokesDeletesFileAndReleasesSlot() {
        val dir = Files.createTempDirectory("aun-dl-refresh").toFile()
        val released = mutableListOf<String>()
        val store = OfflineDownloadStore(
            rootDir = dir,
            keyVault = InMemoryDownloadKeyVault(),
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L },
            monotonicClockMs = { 100L },
            slotReleaser = object : LicenseSlotReleaser {
                override fun releaseLicense(licenseId: String): Boolean {
                    released.add(licenseId)
                    return true
                }
            }
        )
        saveItem(store, "u1", "lic-rev", "v-rev", "Revoke", "abc".toByteArray(), serverTimeMs = 1_000L)
        assertEquals(1, store.applyLicenseRefresh(listOf(LicenseRefreshDecision("lic-rev", "revoked")), 2_000L))
        assertEquals(listOf("lic-rev"), released)
        assertTrue(store.allDownloads("u1").isEmpty())
    }

    @Test
    fun carBrowseTreeIsHiddenWhenListenModeOffAndPlaysOfflineAndAudioRenditionsWhenOn() = runBlocking {
        val dir = Files.createTempDirectory("aun-car").toFile()
        val store = OfflineDownloadStore(
            rootDir = dir,
            keyVault = InMemoryDownloadKeyVault(),
            offlineDownloadsEnabled = true,
            wallClockMs = { 1_000L },
            monotonicClockMs = { 100L }
        )
        saveItem(store, "u1", "lic-off", "v-offline", "Offline Tafsir", "offline audio".toByteArray(), serverTimeMs = 1_000L)

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
        assertEquals("aun-offline://v-offline", offlineItem.localConfiguration?.uri.toString())
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
