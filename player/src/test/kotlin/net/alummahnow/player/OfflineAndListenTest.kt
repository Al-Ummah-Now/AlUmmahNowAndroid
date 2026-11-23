package net.alummahnow.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.player.offline.ChunkedAesCipher
import net.alummahnow.player.offline.InMemoryDownloadKeyVault
import net.alummahnow.player.offline.LicenseRefreshDecision
import net.alummahnow.player.offline.NetworkTransferAllowance
import net.alummahnow.player.offline.OfflineDownloadPolicy
import net.alummahnow.player.offline.OfflineDownloadQuality
import net.alummahnow.player.offline.OfflineDownloadSettings
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineAndListenTest {

    @Test fun `sleep timer expires on schedule and end-of-episode pauses playback`() {
        var now = 1_000_000L
        val controller = ListenModeController(listenModeEnabled = true, clockMs = { now })

        controller.setSleepTimer(SleepTimerPreset.Minutes(15))
        assertTrue(controller.state.value.sleepTimer.isActive)
        assertFalse(controller.tickSleepTimer())

        now += 15 * 60_000L
        assertTrue(controller.tickSleepTimer())
        assertFalse(controller.state.value.sleepTimer.isActive)

        controller.setSleepTimer(SleepTimerPreset.EndOfEpisode)
        assertFalse(controller.tickSleepTimer())
        assertTrue(controller.onEpisodeEnded())
        assertFalse(controller.state.value.sleepTimer.isActive)
    }

    @Test fun `listen mode audio-only toggle is blocked when feature switch is OFF`() {
        val controller = ListenModeController(listenModeEnabled = false)
        controller.setAudioOnly(true)
        assertFalse(controller.state.value.isAudioOnly)

        controller.setFeatureEnabled(true)
        controller.setAudioOnly(true)
        assertTrue(controller.state.value.isAudioOnly)

        controller.setFeatureEnabled(false)
        assertFalse(controller.state.value.isAudioOnly)
    }

    @Test fun `playback queue supports play-next, append, reorder, advance, and clear`() {
        val controller = ListenModeController(listenModeEnabled = true)
        val e1 = QueueEntry("v1", LocalizedText.of("First"), "Ch", null, 60)
        val e2 = QueueEntry("v2", LocalizedText.of("Second"), "Ch", null, 120)
        val e3 = QueueEntry("v3", LocalizedText.of("Third"), "Ch", null, 180)

        controller.setNowPlaying(e1)
        controller.addToEnd(e2)
        controller.playNext(e3)
        assertEquals(listOf("v1", "v3", "v2"), controller.state.value.queue.map { it.videoId })

        controller.moveQueueItem(2, 1)
        assertEquals(listOf("v1", "v2", "v3"), controller.state.value.queue.map { it.videoId })

        assertEquals("v2", controller.advance()?.videoId)
        controller.clearUpNext()
        assertEquals(listOf("v2"), controller.state.value.queue.map { it.videoId })
    }

    @Test fun `chunked AES-256-GCM cipher round-trips and detects tampering or wrong key`() {
        val dir = createTempDirectory("aun-cipher-test").toFile()
        try {
            val vaultA = InMemoryDownloadKeyVault()
            val vaultB = InMemoryDownloadKeyVault()
            val payload = ByteArray(150_000) { i -> (i * 31 % 251).toByte() }
            val dest = File(dir, "clip.aun")

            ChunkedAesCipher.encrypt(vaultA.secretKey(), "vid-1", "lic-1", payload, dest)
            assertFalse(dest.readBytes().contentEquals(payload))

            val decrypted = ChunkedAesCipher.decrypt(vaultA.secretKey(), "vid-1", "lic-1", dest)
            assertArrayEquals(payload, decrypted)

            // Wrong key must fail
            try {
                ChunkedAesCipher.decrypt(vaultB.secretKey(), "vid-1", "lic-1", dest)
                fail("expected wrong key to fail decryption")
            } catch (_: Exception) {
                // expected
            }

            // Wrong videoId AAD binding must fail
            try {
                ChunkedAesCipher.decrypt(vaultA.secretKey(), "vid-2", "lic-1", dest)
                fail("expected mismatched AAD to fail decryption")
            } catch (_: Exception) {
                // expected
            }

            // Tampering a single byte must fail
            val tampered = dest.readBytes()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
            dest.writeBytes(tampered)
            try {
                ChunkedAesCipher.decrypt(vaultA.secretKey(), "vid-1", "lic-1", dest)
                fail("expected tampered ciphertext to fail decryption")
            } catch (_: Exception) {
                // expected
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `offline download policy enforces feature gate and wifi-only setting`() {
        val wifiOnlySettings = OfflineDownloadSettings(wifiOnly = true)
        assertEquals(
            NetworkTransferAllowance.BLOCKED_FEATURE_OFF,
            OfflineDownloadPolicy.canDownload(
                featureEnabled = false,
                settings = OfflineDownloadSettings(),
                isConnected = true,
                isUnmeteredWifi = true
            )
        )
        assertEquals(
            NetworkTransferAllowance.BLOCKED_WIFI_ONLY,
            OfflineDownloadPolicy.canDownload(
                featureEnabled = true,
                settings = wifiOnlySettings,
                isConnected = true,
                isUnmeteredWifi = false
            )
        )
        assertEquals(
            NetworkTransferAllowance.ALLOWED,
            OfflineDownloadPolicy.canDownload(
                featureEnabled = true,
                settings = wifiOnlySettings,
                isConnected = true,
                isUnmeteredWifi = true
            )
        )
    }

    @Test fun `offline download store enforces encryption, refresh revocation, and sign-out wipe`() {
        val dir = createTempDirectory("aun-store-test").toFile()
        try {
            var now = 10_000L
            val vault = InMemoryDownloadKeyVault()
            val store = OfflineDownloadStore(dir, vault, offlineDownloadsEnabled = true, clockMs = { now })
            val r1 = store.saveEncrypted(
                licenseId = "lic-1",
                videoId = "v1",
                title = "Surah Al-Kahf",
                titleAr = "سورة الكهف",
                channelName = "Qari",
                quality = OfflineDownloadQuality.AUDIO,
                expiresAtMs = now + 5_000L,
                plaintextBytes = "audio-one".toByteArray()
            )
            assertEquals("audio-one", store.readDecrypted(r1.licenseId).decodeToString())
            assertEquals(1, store.playableDownloads().size)

            now += 1_000L
            val r2 = store.saveEncrypted(
                licenseId = "lic-2",
                videoId = "v2",
                title = "Fiqh Lecture",
                quality = OfflineDownloadQuality.Q480P,
                expiresAtMs = now + 5_000L,
                plaintextBytes = "video-two".toByteArray()
            )
            val file2 = File(dir, r2.fileName)
            assertTrue(file2.exists())

            val revoked = store.applyLicenseRefresh(
                listOf(
                    LicenseRefreshDecision("lic-1", "active", expiresAtMs = now + 30_000L),
                    LicenseRefreshDecision("lic-2", "revoked", reason = "creator_disabled")
                )
            )
            assertEquals(1, revoked)
            assertFalse(file2.exists())
            assertNull(store.findPlayableByVideoId("v2"))
            assertNotNull(store.findPlayableByVideoId("v1"))

            store.wipeAll()
            assertEquals(0, store.allDownloads().size)
            assertEquals(0L, store.totalUsedBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `android auto car browse tree exposes shallow audio-only tabs, downloads gating, and voice search`() = runTest {
        val dir = createTempDirectory("aun-car-test").toFile()
        try {
            val store = OfflineDownloadStore(dir, InMemoryDownloadKeyVault(), offlineDownloadsEnabled = false)
            val carTree = CarBrowseTree(FakeCatalog(), store, "Al Ummah Now") { it.name }

            assertTrue(isAndroidAutoController("com.google.android.projection.gearhead"))
            assertFalse(isAndroidAutoController("net.alummahnow.app"))

            // Downloads tab hidden while offlineDownloads feature switch is OFF
            val tabsOff = carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH)
            assertEquals(
                listOf(CarTab.CONTINUE_LISTENING.id, CarTab.SUBSCRIPTIONS.id, CarTab.RECITATION.id),
                tabsOff.map { it.mediaId }
            )

            // Downloads tab appears once enabled and lists playable downloads
            store.offlineDownloadsEnabled = true
            store.saveEncrypted(
                licenseId = "lic-car",
                videoId = "offline-1",
                title = "Surah Yasin",
                titleAr = "سورة يس",
                channelName = "Reciter",
                quality = OfflineDownloadQuality.AUDIO,
                expiresAtMs = System.currentTimeMillis() + 60_000L,
                plaintextBytes = byteArrayOf(1, 2, 3)
            )
            val tabsOn = carTree.children(CarBrowseTree.ROOT_ID, Locale.ENGLISH)
            assertEquals(4, tabsOn.size)
            val downloads = carTree.children(CarTab.DOWNLOADS.id, Locale.forLanguageTag("ar"))
            assertEquals(1, downloads.size)
            assertEquals("سورة يس", downloads[0].mediaMetadata.title.toString())

            // Voice search matches offline downloads even when searching Arabic title
            val searchResults = carTree.search("يس", Locale.forLanguageTag("ar"))
            assertTrue(searchResults.any { it.mediaId == "offline-1" })

            // Resume store round-trip
            val resumeStore = PlaybackResumeStore(File(dir, "resume.json"))
            resumeStore.save("t1", 42_000L)
            val resumed = resumeStore.load()
            assertEquals("t1", resumed?.videoId)
            assertEquals(42_000L, resumed?.positionMs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
