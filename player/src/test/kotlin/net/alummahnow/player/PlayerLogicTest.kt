package net.alummahnow.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import java.util.Locale
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.model.RailKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerLogicTest {
    private val ar = Locale.forLanguageTag("ar")

    @Test fun `double tap seeks back on the start side, mirrored in RTL`() {
        assertEquals(-10_000L, SeekGestures.doubleTapDelta(10f, 300f, isRtl = false))
        assertEquals(10_000L, SeekGestures.doubleTapDelta(290f, 300f, isRtl = false))
        assertEquals(10_000L, SeekGestures.doubleTapDelta(10f, 300f, isRtl = true))
        assertEquals(-10_000L, SeekGestures.doubleTapDelta(290f, 300f, isRtl = true))
        assertEquals(0L, SeekGestures.doubleTapDelta(150f, 300f, isRtl = true))
        assertEquals(0L, SeekGestures.doubleTapDelta(1f, 0f, isRtl = false))
    }

    @Test fun `seek target is clamped to the media`() {
        assertEquals(0L, SeekGestures.clampedTarget(3_000, -10_000, 60_000))
        assertEquals(60_000L, SeekGestures.clampedTarget(55_000, 10_000, 60_000))
        assertEquals(20_000L, SeekGestures.clampedTarget(10_000, 10_000, C.TIME_UNSET))
    }

    private fun video(height: Int) = Format.Builder().setSampleMimeType(
        MimeTypes.VIDEO_H264
    ).setHeight(height).setWidth(height * 16 / 9).build()
    private fun text(
        lang: String,
        label: String
    ) = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(lang).setLabel(label).build()

    private val tracks = Tracks(
        ImmutableList.of(
            Tracks.Group(
                TrackGroup(video(240), video(360), video(360)),
                true,
                intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                booleanArrayOf(false, true, false)
            ),
            Tracks.Group(TrackGroup("en", text("en", "English")), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            Tracks.Group(TrackGroup("ar", text("ar", "العربية")), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        )
    )

    @Test fun `qualities are Auto then distinct heights high to low, and overrides round-trip`() {
        val options = TrackOptions.qualities(tracks)
        assertEquals(listOf(null, 360, 240), options.map { it.height })
        val base = TrackSelectionParameters.DEFAULT
        assertTrue(TrackOptions.selectedQuality(base, tracks).isAuto)

        val forced = TrackOptions.applyQuality(base, tracks, options[2])
        assertEquals(240, TrackOptions.selectedQuality(forced, tracks).height)
        assertTrue(TrackOptions.selectedQuality(TrackOptions.applyQuality(forced, tracks, options[0]), tracks).isAuto)
    }

    @Test fun `subtitles offer Off plus each language and toggle the text track type`() {
        val options = TrackOptions.subtitles(tracks)
        assertEquals(listOf(null, "en", "ar"), options.map { it.language })
        assertEquals("en", TrackOptions.selectedSubtitle(TrackSelectionParameters.DEFAULT, tracks).language)

        val off = TrackOptions.applySubtitle(TrackSelectionParameters.DEFAULT, options[0])
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertEquals(null, TrackOptions.selectedSubtitle(off, tracks).language)

        val arabic = TrackOptions.applySubtitle(off, options[2])
        assertFalse(C.TRACK_TYPE_TEXT in arabic.disabledTrackTypes)
        assertEquals(listOf("ar"), arabic.preferredTextLanguages)
    }

    @Test fun `playable media item carries HLS, VTT subtitles and localized metadata`() {
        val item = MediaItems.playable(detail("v1"), ar)
        assertEquals("v1", item.mediaId)
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration!!.mimeType)
        val subs = item.localConfiguration!!.subtitleConfigurations
        assertEquals(listOf("en", "ar"), subs.map { it.language })
        assertEquals(C.SELECTION_FLAG_DEFAULT, subs[0].selectionFlags)
        assertEquals("مرئي v1", item.mediaMetadata.title.toString())
        assertEquals(75_000L, item.mediaMetadata.durationMs)
    }

    @Test fun `browse tree lists one folder per rail then the rail's videos`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tree = BrowseTree(FakeCatalog(), "Root") { ctx.getString(BrowseTree.titleRes(it)) }
        assertTrue(tree.root().mediaMetadata.isBrowsable == true)

        val folders = tree.children(BrowseTree.ROOT_ID, Locale.ENGLISH)
        assertEquals(listOf("aun:rail:TRENDING", "aun:rail:RECENT"), folders.map { it.mediaId })
        assertEquals("Trending", folders[0].mediaMetadata.title.toString())

        val videos = tree.children(BrowseTree.FOLDER_PREFIX + RailKind.TRENDING.name, Locale.ENGLISH)
        assertEquals(listOf("t1", "t2"), videos.map { it.mediaId })
        assertTrue(videos.all { it.mediaMetadata.isPlayable == true && it.localConfiguration == null })
        assertEquals(emptyList<Any>(), tree.children("aun:rail:NOPE", Locale.ENGLISH))
    }

    @Test fun `only our own package may pass prebuilt media items`() {
        assertTrue(acceptsPrebuiltItems("net.alummahnow.app", "net.alummahnow.app"))
        assertFalse(acceptsPrebuiltItems("com.google.android.projection.gearhead", "net.alummahnow.app"))
        assertFalse(acceptsPrebuiltItems("com.example.other", "net.alummahnow.app"))
    }
}
