package net.alummahnow.core.bridge.studio.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.UploadEngineInterface
import net.alummahnow.core.UploadInfo
import net.alummahnow.core.UploadRequest
import net.alummahnow.core.UploadState
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoreUploadQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = File(context.cacheDir, "talk.mp4")
    private var active = "a"
    private val engines = mutableMapOf<String, FakeEngine>()
    private var workAsked = 0

    @Before fun setUp() {
        source.writeText("video")
    }

    private fun queue() = CoreUploadQueue(
        context = context,
        activeUserId = { active },
        clientFor = { error("no client") },
        repository = { error("no repository") },
        onWorkNeeded = { workAsked++ },
        newEngine = { userId, _ -> engines.getOrPut(userId) { FakeEngine() } }
    )

    private fun draft() = UploadDraft(source.path, "talk.mp4", 5, "video/mp4", "Talk", channelId = "c1")

    private val staged get() = File(context.filesDir, "upload-staging").listFiles().orEmpty().toList()

    /** The engine ends each upload as [state]; `process` runs until nothing is active. */
    private suspend fun CoreUploadQueue.run(state: UploadState, error: String? = null) {
        engines.getOrPut("a") { FakeEngine() }.next = state to error
        process { }
    }

    @Test
    fun `a refused upload keeps its staged file, so a retry has something to send`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "forbidden: Accept the Terms")
        val item = queue.items.value.single()
        assertEquals(UploadPhase.FAILED, item.phase)
        assertTrue(item.isRefused)
        assertEquals("video", staged.single().readText())

        // After agreeing, the person taps Retry: the engine uploads the same file.
        val asked = workAsked
        queue.retry(item.id)
        assertEquals(asked + 1, workAsked)
        assertEquals(UploadPhase.QUEUED, queue.items.value.single().phase)
        assertFalse(queue.items.value.single().isRefused)
        assertTrue(File(engines.getValue("a").requests.single().filePath).exists())
    }

    @Test
    fun `a failed upload does not start the worker at every launch, and removing it deletes the file`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "forbidden: x")
        assertFalse(queue.hasWork())
        queue.remove(queue.items.value.single().id)
        assertEquals(emptyList<File>(), staged)
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a finished or cancelled upload leaves no file behind`() = runTest {
        for (state in listOf(UploadState.DONE, UploadState.CANCELLED)) {
            val queue = queue()
            queue.enqueue(listOf(draft()))
            queue.run(state)
            assertEquals("$state", emptyList<File>(), staged)
            assertFalse("$state", queue.hasWork())
        }
    }

    @Test
    fun `after a switch, a refresh shows only the active account's uploads`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "forbidden: x")
        active = "b"
        assertEquals(emptyList<Any>(), queue.refresh())
        assertEquals(emptyList<Any>(), queue.items.value)
    }

    @Test
    fun `only a refused upload is one the server refused`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "network error: gone")
        assertFalse(queue.items.value.single().isRefused)
    }

    /** aun-upload's engine as the queue uses it; its uploads end as [next] says. */
    private class FakeEngine(private val uploads: MutableMap<String, UploadInfo> = linkedMapOf()) :
        UploadEngineInterface by unusedEngine() {
        var next: Pair<UploadState, String?> = UploadState.QUEUED to null
        val requests = mutableListOf<UploadRequest>()

        override fun enqueue(request: UploadRequest): String {
            requests += request
            val id = "up-${requests.size}"
            uploads[id] = UploadInfo(
                id = id,
                filePath = request.filePath,
                title = request.title,
                state = next.first,
                sentBytes = 0u,
                totalBytes = 5u,
                videoId = null,
                sha256 = null,
                lastError = next.second,
                priority = 0,
                createdAtMs = 0,
                updatedAtMs = 0
            )
            return id
        }
        override fun list() = uploads.values.toList()
        override fun get(id: String) = uploads[id]
        override fun retry(id: String) {
            uploads[id] = uploads.getValue(id).copy(state = UploadState.QUEUED)
        }
        override fun remove(id: String) {
            uploads.remove(id)
        }
    }
}

private fun unusedEngine(): UploadEngineInterface = Proxy.newProxyInstance(
    UploadEngineInterface::class.java.classLoader,
    arrayOf(UploadEngineInterface::class.java)
) { _, m, _ -> error("unused: ${m.name}") } as UploadEngineInterface
