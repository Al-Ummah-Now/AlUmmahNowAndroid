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
            channelId = "ch-1",
            channelName = "Al-Huda",
            thumbnailUrl = null,
            durationSeconds = 1800,
            views = 1200,
            category = "Quran"
        )
        private val lecture = VideoSummary(
            id = "v-lecture-2",
            title = LocalizedText("Seerah Lesson 1"),
            channelId = "ch-2",
            channelName = "Seerah Series",
            thumbnailUrl = null,
            durationSeconds = 2400,
            views = 500,
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
            subtitles = emptyList(),
            chapters = emptyList()
        )

        override suspend fun audioUrl(videoId: String): String =
            "https://media.alummahnow.net/pub/$videoId/rev1/audio.m4a"

        override suspend fun related(video: VideoDetail, limit: Int): List<VideoSummary> = emptyList()
        override suspend fun search(query: String, limit: Int): List<VideoSummary> =
            listOf(sample, lecture).filter { it.title.fallback.contains(query, ignoreCase = true) }
    }

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
        // Cross-video and cross-license file swapping fails on open.
        assertThrows(IOException::class.java) {
            AunDl01RandomAccessReader.open(vault.secretKey(), enc, "vid-other", "lic-1")
        }
        assertThrows(IOException::class.java) {
            AunDl01RandomAccessReader.open(vault.secretKey(), enc, "vid-1", "lic-other")
        }
        // Truncating at exact chunk boundary fails final-chunk AAD on open.
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
        // Signed HTTP URLs are strictly forbidden in thumbnailPath.
        assertThrows(IllegalArgumentException::class.java) {
            store.saveFromStream(
                userId = "u1", licenseId = "lic-bad", videoId = "v1", title = "Bad",
                thumbnailPath = "https://media.alummahnow.net/priv/token/thumb.jpg",
                quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 10_000L, expiresAtMs = 50_000L,
                input = ByteArrayInputStream("ok".toByteArray())
            )
        }
        // Two users downloading the same video stay isolated.
        store.saveFromStream(
            userId = "u1", licenseId = "lic-u1", videoId = "v1", title = "User 1",
            quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 10_000L, expiresAtMs = 20_000L,
            input = ByteArrayInputStream("user1".toByteArray())
        )
        store.saveFromStream(
            userId = "u2", licenseId = "lic-u2", videoId = "v1", title = "User 2",
            quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 10_000L, expiresAtMs = 90_000L,
            input = ByteArrayInputStream("user2".toByteArray())
        )
        assertEquals(1, store.playableDownloads("u1").size)
        assertEquals(1, store.playableDownloads("u2").size)

        // Roll wall clock backward while monotonic advances past u1's expiry: u1 MUST NOT be playable!
        wall = 1_000L
        mono = 15_000L // server anchor 10_000 + 14_000 = 24_000 > 20_000
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
        store.saveFromStream(
            userId = "u1", licenseId = "lic-1", videoId = "v1", title = "Keep me",
            quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 1_000L, expiresAtMs = 90_000L,
            input = ByteArrayInputStream(ByteArray(300) { 1 })
        )
        // Adding a second download over budget must throw instead of silently deleting v1!
        assertThrows(StorageBudgetExceededException::class.java) {
            store.saveFromStream(
                userId = "u1", licenseId = "lic-2", videoId = "v2", title = "Too big",
                quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 1_000L, expiresAtMs = 90_000L,
                input = ByteArrayInputStream(ByteArray(300) { 2 })
            )
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
        store.saveFromStream(
            userId = "u1", licenseId = "lic-rev", videoId = "v-rev", title = "Revoke",
            quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 1_000L, expiresAtMs = 90_000L,
            input = ByteArrayInputStream("abc".toByteArray())
        )
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
        store.saveFromStream(
            userId = "u1", licenseId = "lic-off", videoId = "v-offline", title = "Offline Tafsir",
            quality = OfflineDownloadQuality.AUDIO, serverTimeMs = 1_000L, expiresAtMs = 90_000L,
            input = ByteArrayInputStream("offline audio bytes".toByteArray())
        )

        var listenOn = false
        val carTree = CarBrowseTree(
            catalog = fakeCatalog,
            downloadStore = store,
            listenModeEnabled = { listenOn },
            activeUserId = { "u1" },
            rootTitle = "Al Ummah Now",
            tabTitle = { it.name }
        )
        // Gate: Android Auto is completely empty while listenMode is off!
        assertTrue(carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH).isEmpty())
        assertTrue(carTree.search("Tafsir", Locale.ENGLISH).isEmpty())

        listenOn = true
        val tabs = carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH)
        assertEquals(4, tabs.size)

        // Car Downloads tab plays offline via aun-offline:// without calling catalog.video()!
        val offlineItem = carTree.resolvePlayable("v-offline", audioOnly = true, locale = Locale.ENGLISH)
        assertEquals("aun-offline://v-offline", offlineItem.localConfiguration?.uri.toString())

        // Car online playback uses the audio.m4a rendition, NOT the muxed master.m3u8!
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
