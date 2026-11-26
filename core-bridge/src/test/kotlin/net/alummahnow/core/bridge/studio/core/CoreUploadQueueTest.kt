package net.alummahnow.core.bridge.studio.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.UploadEngineInterface
import net.alummahnow.core.UploadInfo
import net.alummahnow.core.UploadRequest
import net.alummahnow.core.UploadState
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.deletion.SecureDeletionRecords
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
    private var enginesOpened = 0

    @Before fun setUp() {
        source.writeText("video")
    }

    private var blocked = false

    private fun queue(
        isBlocked: (String) -> Boolean = { blocked && it == "a" },
        onWorkNeeded: () -> Unit = {}
    ) = CoreUploadQueue(
        context = context,
        activeUserId = { active },
        clientFor = { error("no client") },
        repository = { error("no repository") },
        onWorkNeeded = {
            workAsked++
            onWorkNeeded()
        },
        newEngine = { userId, _ ->
            enginesOpened++
            engines.getOrPut(userId) { FakeEngine() }.also { it.open() }
        },
        holdTimeoutMs = 1_500,
        blocked = isBlocked
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

    @Test
    fun `removing a failed upload leaves no work for the next launch`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "forbidden: x")
        queue.remove(queue.items.value.single().id)
        assertFalse(queue().hasWork())
    }

    @Test
    fun `a batch queued while another uploads is staged too`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        val engine = engines.getOrPut("a") { FakeEngine() }
        engine.next = UploadState.UPLOADING to null
        var rounds = 0
        queue.process {
            rounds++
            if (rounds == 1) queue.enqueueNow(draft())
            if (rounds == 2) engine.finishAll()
        }
        assertEquals(2, engine.requests.size)
        assertEquals(emptyList<File>(), staged)
    }

    /** [enqueue] from a callback that is not suspending. */
    private fun CoreUploadQueue.enqueueNow(draft: UploadDraft) = kotlinx.coroutines.runBlocking { enqueue(listOf(draft)) }

    @Test
    fun `a purge removes the account's queue, files and engine, and no other account's`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.FAILED, "x")
        active = "b"
        queue.enqueue(listOf(draft()))
        queue.purgeAccount("a")
        assertEquals(emptyList<File>(), staged)
        assertTrue(engines.getValue("a").stopped)
        assertFalse(File(context.filesDir, "uploads/a").exists())
        assertEquals(1, queue.refresh().size)
        // Nothing of the deleted account comes back in a new process either.
        active = "a"
        assertEquals(emptyList<Any>(), queue().refresh())
    }

    @Test
    fun `a purge keeps the access to a picked file that another account still has to send`() = runTest {
        val uri = android.net.Uri.parse("content://docs/talk")
        val own = android.net.Uri.parse("content://docs/own")
        val queue = queue()
        queue.enqueue(listOf(draft().copy(source = uri.toString()), draft().copy(source = own.toString())))
        active = "b"
        queue.enqueue(listOf(draft().copy(source = uri.toString())))
        fun kept() = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        assertEquals(setOf(uri, own), kept())
        queue.purgeAccount("a")
        assertEquals(setOf(uri), kept())
        queue.purgeAccount("b")
        assertEquals(emptySet<Any>(), kept())
    }

    @Test
    fun `a purge reads the queue from disk, and one it can't read is not emptied`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        active = "b"
        queue().enqueue(listOf(draft())) // another process's word for it
        queue.purgeAccount("a")
        assertEquals(1, queue.refresh().size)
        val file = File(context.filesDir, "uploads/queue.json")
        val saved = file.readText()
        file.writeText("{broken")
        assertTrue(runCatching { queue.purgeAccount("b") }.isFailure)
        assertEquals("{broken", file.readText())
        file.writeText(saved)
        queue.purgeAccount("b")
        assertEquals(emptyList<Any>(), queue.refresh())
    }

    @Test
    fun `a purge while a file is being copied leaves no copy and writes nothing back`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        engines.getOrPut("a") { FakeEngine() }.onEnqueue = { kotlinx.coroutines.runBlocking { queue.purgeAccount("a") } }
        queue.process { }
        assertEquals(emptyList<File>(), staged)
        assertFalse(File(context.filesDir, "uploads/a").exists())
        assertFalse(queue().hasWork())
        assertFalse("Talk" in File(context.filesDir, "uploads/queue.json").readText())
    }

    @Test
    fun `a file that can't be staged leaves no copy`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(draft()))
        engines.getOrPut("a") { FakeEngine() }.onEnqueue = { error("refused") }
        queue.process { }
        assertEquals(emptyList<File>(), staged)
    }

    @Test
    fun `an account that is signed in and uploads again is not purged any more`() = runTest {
        val queue = queue()
        queue.purgeAccount("a")
        queue.enqueue(listOf(draft()))
        queue.run(UploadState.DONE)
        assertEquals(1, engines.getValue("a").requests.size)
    }

    @Test
    fun `nothing of an account that may be deleted is staged, sent, resumed or released, and the worker stops`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        assertTrue(held(queue).isSuccess)
        blocked = true
        queue.enqueue(listOf(draft()))
        queue.releaseUploads("a")
        queue.resume("up-1")
        queue.retry("up-1")
        queue.process { }
        assertFalse(queue.hasWork())
        assertEquals(1, engine.requests.size)
        assertEquals(listOf("pause up-1"), engine.actions)
        // Known to be there.
        blocked = false
        queue.releaseUploads("a")
        assertEquals(listOf("pause up-1", "resume up-1"), engine.actions)
        assertTrue(queue.hasWork())
    }

    @Test
    fun `an account that may be deleted starts nothing, also without a hold of this process`() = runTest {
        val engine = uploading(queue(), UploadState.FAILED)
        blocked = true
        val queue = queue()
        queue.enqueue(listOf(draft()))
        queue.retry("up-1")
        queue.process { }
        assertFalse(queue.hasWork())
        assertEquals(1, engine.requests.size)
        assertEquals(emptyList<String>(), engine.actions)
    }

    @Test
    fun `a hold with nothing under way outlives the process too`() = runTest {
        assertTrue(held(queue()).isSuccess)
        val next = queue()
        next.enqueue(listOf(draft()))
        next.process { }
        assertEquals(null, engines["a"]?.requests?.size?.takeIf { it > 0 })
        next.releaseLeft()
        next.process { engines.getValue("a").finishAll() }
        assertEquals(1, engines.getValue("a").requests.size)
    }

    @Test
    fun `a hold outlives the process, and the next start lets go only what may go on`() = runTest {
        val engine = uploading(queue())
        assertTrue(held(queue()).isSuccess)
        blocked = true
        val next = queue()
        next.retry("up-1")
        next.releaseLeft()
        assertEquals(listOf("pause up-1"), engine.actions)
        blocked = false
        val after = queue()
        after.releaseLeft()
        assertEquals(listOf("pause up-1", "resume up-1"), engine.actions)
        // Released once: a later process holds nothing.
        queue().releaseLeft()
        assertEquals(2, engine.actions.size)
    }

    @Test
    fun `startup cannot release a hold taken over by a new deletion`() = runTest {
        val previous = queue()
        val engine = uploading(previous)
        previous.holdUploads("b")
        previous.holdUploads("a")
        val asked = workAsked

        lateinit var next: CoreUploadQueue
        next = queue(onWorkNeeded = {
            // Releasing b gives a new deletion time to acquire a's hold after startup has listed it.
            if (workAsked == asked + 1) runBlocking { next.holdUploads("a") }
        })
        next.releaseLeft()

        assertEquals(listOf("pause up-1"), engine.actions)
        assertEquals(asked + 1, workAsked)
        assertEquals(UploadState.PAUSED, engine.get("up-1")?.state)
        next.releaseUploads("a")
        assertEquals(listOf("pause up-1", "resume up-1"), engine.actions)
    }

    @Test
    fun `unreadable deletion records keep uploads held across restarts`() = runTest {
        val previous = queue()
        val engine = uploading(previous)
        previous.holdUploads("a")
        val asked = workAsked
        val opened = enginesOpened
        val name = "aun_deletion_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        // A truncated Base64 value fails before the Keystore is needed.
        assertTrue(prefs.edit().putString("account-deletions", "A").commit())

        repeat(2) {
            val records = SecureDeletionRecords(KeystoreSecureStorage(context, name, discardUnreadableValues = false))
            val next = queue(isBlocked = records::isUnsure)
            next.releaseLeft()

            assertEquals(listOf("pause up-1"), engine.actions)
            assertEquals(asked, workAsked)
            assertFalse(next.hasWork())
            assertEquals(opened, enginesOpened)
            assertTrue(records.isUnsure("a"))
            assertTrue(runCatching { records.get("a") }.isFailure)
        }
    }

    @Test
    fun `held uploads are paused and not started, then go on`() = runTest {
        val queue = queue()
        val engine = engines.getOrPut("a") { FakeEngine() }
        engine.next = UploadState.UPLOADING to null
        queue.enqueue(listOf(draft()))
        var round = 0
        // While they are held the worker ends: a release starts it again.
        queue.process {
            if (++round == 1) {
                kotlinx.coroutines.runBlocking {
                    queue.holdUploads("a")
                    assertEquals(UploadPhase.PAUSED, queue.items.value.first { it.id == "up-1" }.phase)
                    queue.enqueue(listOf(draft()))
                }
            }
        }
        assertEquals(1, engine.requests.size)
        val asked = workAsked
        queue.releaseUploads("a")
        assertEquals(asked + 1, workAsked)
        round = 0
        queue.process { if (++round == 2) engine.finishAll() }
        assertEquals(listOf("pause up-1", "resume up-1"), engine.actions)
        assertEquals(2, engine.requests.size)
    }

    private suspend fun uploading(queue: CoreUploadQueue, state: UploadState = UploadState.UPLOADING): FakeEngine {
        val engine = engines.getOrPut("a") { FakeEngine() }
        engine.next = state to null
        queue.enqueue(listOf(draft()))
        // One round: it is handed to the engine.
        runCatching { queue.process { error("one round") } }
        return engine
    }

    private fun held(queue: CoreUploadQueue) = runCatching { kotlinx.coroutines.runBlocking { queue.holdUploads("a") } }

    @Test
    fun `a file that is being copied when uploads are held is not handed on until they go on`() = runTest {
        val queue = queue()
        val uri = android.net.Uri.parse("content://picker/talk")
        var copies = 0
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            if (copies++ == 0) assertTrue(held(queue).isSuccess)
            "video".byteInputStream()
        }
        queue.enqueue(listOf(draft().copy(source = uri.toString())))
        var round = 0
        queue.process {
            if (++round == 1) {
                assertEquals(emptyList<UploadRequest>(), engines["a"]?.requests.orEmpty())
                assertEquals(emptyList<File>(), staged)
                assertEquals(UploadPhase.PREPARING, queue.items.value.single().phase)
                kotlinx.coroutines.runBlocking { queue.releaseUploads("a") }
            } else {
                engines.getValue("a").finishAll()
            }
        }
        assertEquals(2, copies)
        assertEquals(1, engines.getValue("a").requests.size)
    }

    @Test
    fun `holding fails when an upload can't be paused, and a release lets it go on`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        engine.pauseFails = true
        assertEquals("no such upload", held(queue).exceptionOrNull()?.message)
        queue.releaseUploads("a")
        assertEquals(listOf("pause up-1", "resume up-1"), engine.actions)
    }

    @Test
    fun `holding waits for a request that is out, and pauses what its answer started again`() = runTest {
        val queue = queue()
        val engine = uploading(queue, UploadState.INITIATING)
        // Two tries find the store in use; then the answer is in and the upload would go on.
        engine.requestsOut += {}
        engine.requestsOut += { engine.set("up-1", UploadState.UPLOADING) }
        assertTrue(held(queue).isSuccess)
        assertEquals(0, engine.requestsOut.size)
        assertEquals(listOf("pause up-1", "pause up-1"), engine.actions)
        assertEquals(UploadState.PAUSED, engine.get("up-1")?.state)
        assertFalse(engine.stopped)
    }

    @Test
    fun `holding fails when an upload keeps starting again`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        engine.afterShutdown = { engine.set("up-1", UploadState.UPLOADING) }
        assertTrue(held(queue).isFailure)
        assertTrue(engine.actions.size in 2..20)
    }

    @Test
    fun `holding fails when a request stays out`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        repeat(100) { engine.requestsOut += {} }
        val failure = held(queue).exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException && failure !is kotlinx.coroutines.CancellationException)
        // Its staged file is kept: what can't be read has not ended.
        runCatching { queue.process { error("one round") } }
        assertEquals(1, staged.size)
        queue.releaseUploads("a")
        runCatching { queue.process { error("one round") } }
        assertEquals(1, staged.size)
    }

    @Test
    fun `nothing is resumed, retried or settled while uploads are held`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        assertTrue(held(queue).isSuccess)
        engine.set("up-1", UploadState.FAILED)
        queue.retry("up-1")
        assertEquals(UploadState.FAILED, engine.get("up-1")?.state)
        engine.set("up-1", UploadState.PAUSED)
        queue.resume("up-1")
        assertEquals(UploadState.PAUSED, engine.get("up-1")?.state)
        engine.set("up-1", UploadState.DONE)
        runCatching { queue.process { error("one round") } }
        assertEquals(1, staged.size)
        engine.set("up-1", UploadState.FAILED)
        queue.releaseUploads("a")
        assertEquals(UploadState.QUEUED, engine.get("up-1")?.state)
    }

    @Test
    fun `what the person pauses while uploads are held stays paused`() = runTest {
        val queue = queue()
        val engine = uploading(queue)
        assertTrue(held(queue).isSuccess)
        queue.pause("up-1")
        queue.releaseUploads("a")
        assertEquals(UploadState.PAUSED, engine.get("up-1")?.state)
    }

    @Test
    fun `an account with nothing left here can be held`() = runTest {
        val queue = queue()
        queue.purgeAccount("a")
        assertTrue(held(queue).isSuccess)
    }

    /** aun-upload's engine as the queue uses it; its uploads end as [next] says. */
    private class FakeEngine(private val uploads: MutableMap<String, UploadInfo> = linkedMapOf()) :
        UploadEngineInterface by unusedEngine() {
        var next: Pair<UploadState, String?> = UploadState.QUEUED to null
        var onEnqueue: () -> Unit = {}
        var stopped = false
        val requests = mutableListOf<UploadRequest>()
        val actions = mutableListOf<String>()

        fun finishAll() = uploads.keys.toList().forEach { set(it, UploadState.DONE) }

        /** What ends, in this order, while the store is tried again after a shutdown: nothing opens before. */
        val requestsOut = ArrayDeque<() -> Unit>()
        var pauseFails = false
        var afterShutdown: () -> Unit = {}

        fun set(id: String, state: UploadState) {
            uploads[id] = uploads.getValue(id).copy(state = state)
        }
        override fun pause(id: String) {
            actions += "pause $id"
            check(!pauseFails) { "no such upload" }
            set(id, UploadState.PAUSED)
        }
        override fun resume(id: String) {
            actions += "resume $id"
            set(id, UploadState.UPLOADING)
        }
        override suspend fun cancel(id: String) = set(id, UploadState.CANCELLED)
        override fun shutdown() {
            stopped = true
            afterShutdown()
        }

        /** As the core opens its store: not while a request of the old engine is out, and what ran is queued. */
        fun open() {
            if (stopped) {
                requestsOut.removeFirstOrNull()?.let {
                    it()
                    error("in use")
                }
                uploads.values.filter { it.state in LOADED_QUEUED }.forEach { set(it.id, UploadState.QUEUED) }
            }
            stopped = false
        }

        override fun enqueue(request: UploadRequest): String {
            onEnqueue()
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

private val LOADED_QUEUED = setOf(
    UploadState.HASHING,
    UploadState.INITIATING,
    UploadState.UPLOADING,
    UploadState.COMPLETING,
    UploadState.WAITING
)

private fun unusedEngine(): UploadEngineInterface = Proxy.newProxyInstance(
    UploadEngineInterface::class.java.classLoader,
    arrayOf(UploadEngineInterface::class.java)
) { _, m, _ -> error("unused: ${m.name}") } as UploadEngineInterface
