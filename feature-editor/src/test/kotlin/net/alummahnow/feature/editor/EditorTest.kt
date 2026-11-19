package net.alummahnow.feature.editor

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.media.EditClip
import net.alummahnow.core.bridge.media.EditDecisionList
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
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
class EditorTest {
    @get:Rule val compose = createComposeRule()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private val durations = mapOf("a" to 980_000L, "b" to 634_000L, "c" to 1_278_000L)
    private fun vm() = EditorViewModel(probe = { uri -> durations[uri] })

    @Test fun importAddsReadableVideosInOrderAndReportsTheRest() {
        val vm = vm()
        vm.import(listOf("a", "nope", "b"))
        val s = vm.state.value
        assertEquals(listOf("a", "b"), s.list.clips.map { it.sourceUri })
        assertEquals(s.list.clips[0].id, s.selectedId)
        assertTrue(s.importFailed)
        vm.dismissImportError()
        assertFalse(vm.state.value.importFailed)
    }

    @Test fun scrubClampsAndSelectsTheClipUnderThePlayhead() {
        val vm = vm()
        vm.import(listOf("a", "b"))
        vm.scrub(1_000_000)
        assertEquals(vm.state.value.list.clips[1].id, vm.state.value.selectedId)
        vm.scrub(99_000_000)
        assertEquals(1_613_999L, vm.state.value.playheadMs)
        vm.scrub(-5)
        assertEquals(0L, vm.state.value.playheadMs)
    }

    @Test fun moveReordersAndEmptyStateOffersImport() {
        val vm = vm()
        var imported = 0
        compose.setContent {
            val state by vm.state.collectAsState()
            AunTheme(mode = ThemeMode.DARK) {
                EditorScreen(state, false, { imported++ }, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("editor-import").performClick()
        assertEquals(1, imported)
        vm.import(listOf("a", "b"))
        val first = vm.state.value.list.clips[0].id
        vm.move(first, 1)
        assertEquals(first, vm.state.value.list.clips[1].id)
    }

    @Test fun splitDeleteTrimAreUndoableSteps() {
        val vm = vm()
        vm.import(listOf("a", "b"))
        vm.scrub(500_000)
        vm.split()
        assertEquals(3, vm.state.value.list.clips.size)
        vm.select(vm.state.value.list.clips[1].id)
        vm.deleteSelected()
        assertEquals(listOf(500_000L, 634_000L), vm.state.value.list.clips.map { it.durationMs })
        val id = vm.state.value.list.clips[1].id
        vm.trim(id, 10_000, 634_000, done = false)
        vm.trim(id, 20_000, 634_000, done = false)
        vm.trim(id, 30_000, 600_000, done = true)
        assertEquals(570_000L, vm.state.value.list.clips[1].durationMs)
        vm.undo() // the whole trim drag is one step
        assertEquals(634_000L, vm.state.value.list.clips[1].durationMs)
        vm.undo()
        assertEquals(3, vm.state.value.list.clips.size)
        vm.redo()
        assertEquals(2, vm.state.value.list.clips.size)
        assertTrue(vm.state.value.canUndo && vm.state.value.canRedo)
    }

    @Test fun exportReportsProgressThenTheFileAndAnEditMakesItStale() {
        val dir = java.nio.file.Files.createTempDirectory("export").toFile()
        val vm = EditorViewModel({ durations[it] }, { _, output, progress ->
            progress(0.5f)
            output.writeText("mp4")
        }, { dir })
        vm.import(listOf("a"))
        vm.export()
        val path = vm.state.value.exportedPath
        assertTrue(path != null && java.io.File(path).exists())
        vm.import(listOf("b"))
        assertEquals(null, vm.state.value.exportedPath)
    }

    @Test fun aFailedExportSaysSo() {
        val vm =
            EditorViewModel({ durations[it] }, { _, _, _ -> error("encoder") }, { java.nio.file.Files.createTempDirectory("x").toFile() })
        vm.import(listOf("a"))
        vm.export()
        assertTrue(vm.state.value.exportFailed)
        assertEquals(null, vm.state.value.exportProgress)
    }

    private val sample = EditorUiState(
        list = EditDecisionList(
            listOf(
                EditClip("a", 980_000, id = "c1"),
                EditClip("b", 634_000, id = "c2"),
                EditClip("c", 1_278_000, id = "c3")
            )
        ),
        selectedId = "c2",
        playheadMs = 1_210_000,
        canUndo = true
    )

    private val editor = @Composable { EditorScreen(sample, false, {}, {}, {}, { _, _ -> }, {}) }
    private val exported = @Composable {
        EditorScreen(sample.copy(exportedPath = "/cache/editor-exports/edit.mp4"), false, {}, {}, {}, { _, _ -> }, {})
    }
    private val empty = @Composable { EditorScreen(EditorUiState(), false, {}, {}, {}, { _, _ -> }, {}) }

    @Test fun shots() {
        for (lang in Lang.entries) {
            for (mode in ThemeMode.entries) {
                for (device in Device.entries) shoot("editor", lang, mode, device, editor)
                shoot("editor_empty", lang, mode, Device.PHONE, empty)
                shoot("editor_exported", lang, mode, Device.TABLET, exported)
            }
        }
    }
}

/** en/ar × dark/light × phone/tablet. Robolectric needs ldrtl/ldltr spelled out (see CLAUDE.md). */
internal enum class Lang(val qualifier: String) { EN("en-ldltr"), AR("ar-ldrtl") }

internal enum class Device(val qualifier: String) {
    PHONE("w411dp-h891dp-port-xxhdpi"),
    TABLET("w1280dp-h800dp-land-xhdpi")
}

internal fun shoot(name: String, lang: Lang, mode: ThemeMode, device: Device, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers("${lang.qualifier}-${device.qualifier}")
    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    val file = "src/test/screenshots/${name}_${lang.name.lowercase()}_${mode.name.lowercase()}_${device.name.lowercase()}.png"
    captureRoboImage(file) { AunTheme(mode = mode) { content() } }
}
