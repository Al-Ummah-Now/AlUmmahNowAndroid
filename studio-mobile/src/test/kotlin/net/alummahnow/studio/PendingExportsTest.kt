package net.alummahnow.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.feature.upload.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PendingExportsTest {
    private val base = Files.createTempDirectory("aun-exports").toFile()
    private val root = File(base, "editor-exports")
    private val index = File(base, "pending.json")

    @After fun cleanUp() {
        base.deleteRecursively()
    }

    private fun store() = PendingExports(root, index)

    /** An export as the editor makes it: a file in the account's folder, handed on as the provider's content URI. */
    private fun PendingExports.export(userId: String, name: String): PickedFile {
        val file = File(dir(userId).apply { mkdirs() }, name).apply { writeText("video") }
        return PickedFile(
            "content://net.alummahnow.studio.editor.exports/exports/${dir(userId).name}/$name",
            name,
            file.length(),
            "video/mp4"
        )
    }

    private fun account(id: String) = StudioAccount(id, id, "$id@example.org", null, null, false)

    @Test
    fun `exports stay per account and channel through a new process, and no other account gets them`() {
        val first = store()
        val one = first.export("a", "1.mp4")
        val two = first.export("a", "2.mp4")
        first.add("a", "c1", one)
        first.add("a", "c1", one)
        first.add("a", "c2", two)

        val next = store()
        assertEquals(setOf("a" to "c1", "a" to "c2"), next.waiting.value)
        assertEquals(emptyList<PickedFile>(), next.take("b", "c1").files)
        assertEquals(listOf(one), next.take("a", "c1").files)
        assertEquals(emptyList<PickedFile>(), next.take("a", "c1").files)
        assertEquals(listOf(two), store().take("a", "c2").files)
    }

    @Test
    fun `an export the system removed is reported, not handed on`() {
        val store = store()
        store.add("a", "c1", store.export("a", "1.mp4"))
        File(store.dir("a"), "1.mp4").delete()
        assertEquals(PendingExports.Taken(emptyList(), 1), store.take("a", "c1"))
    }

    @Test
    fun `switching keeps an account's exports and leaving deletes only that account's`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.add("a", "c1", store.export("a", "1.mp4"))
        store.add("b", "c9", store.export("b", "2.mp4"))
        val accounts = MutableStateFlow(listOf(account("a"), account("b")))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { store.follow(accounts) }
        accounts.value = listOf(account("b"), account("a")) // the active account changed
        assertTrue(File(store.dir("a"), "1.mp4").exists())
        accounts.value = listOf(account("b"))
        assertFalse(store.dir("a").exists())
        assertEquals(setOf("b" to "c9"), store().waiting.value)
        accounts.value = emptyList()
        assertFalse(store.dir("b").exists())
        assertEquals(emptySet<Pair<String, String>>(), store().waiting.value)
        job.cancel()
    }

    @Test
    fun `an empty account list at start deletes nothing, and an account missing at start is swept`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.add("a", "c1", store.export("a", "1.mp4"))
        val accounts = MutableStateFlow(emptyList<StudioAccount>())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { store.follow(accounts) }
        assertTrue(File(store.dir("a"), "1.mp4").exists())
        accounts.value = listOf(account("b"))
        assertFalse(store.dir("a").exists())
        job.cancel()
    }

    @Test
    fun `an unreadable list starts empty`() {
        index.writeText("not json")
        assertEquals(emptySet<Pair<String, String>>(), store().waiting.value)
    }
}
