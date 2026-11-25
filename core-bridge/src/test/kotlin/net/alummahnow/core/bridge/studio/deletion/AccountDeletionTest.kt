package net.alummahnow.core.bridge.studio.deletion

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared rules (wiki guides/data-lifecycle.md, "Self-service account deletion"), row by row. */
class AccountDeletionTest {
    private val log = mutableListOf<String>()
    private var now = 1_000_000L
    private val records = Records()
    private val api = Api()
    private val device = Device()
    private val deletion = AccountDeletion(records, api, device, nowMs = { now * 1000 })

    private val done = DeletionAnswer(200, """{"success":true,"complete":true}""")

    private fun more(token: String, extra: String = "") =
        DeletionAnswer(200, """{"success":true,"complete":false,"resumeToken":"$token"$extra}""")

    private fun complete(vararg errors: String) =
        DeletionAnswer(200, """{"success":true,"complete":true,"errors":[${errors.joinToString { "\"$it\"" }}]}""")

    private val gone = DeletionAnswer(200, """{"deleted":true,"deletedAt":1}""")

    private suspend fun run(accept: Set<Leftover> = emptySet(), stop: () -> Boolean = { false }) =
        deletion.run("u1", "u1@example.org", accept, stop)

    @Test
    fun `erases pass by pass, then deletes the account, then uploads, session and record`() = runTest {
        api.erases += listOf(more("t1"), more("t2"), done)
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(
            listOf(
                "hold", "save STARTED -", "erase u1 -", "save STARTED t1", "erase u1 t1", "save STARTED t2", "erase u1 t2",
                "save CONTENT_ERASED -", "account u1", "save DELETED -", "purge", "end", "remove"
            ),
            log
        )
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun `nothing is called when the record can't be saved or read`() = runTest {
        records.failPut = true
        assertEquals(DeletionOutcome.NoRecord(ErasedSoFar.NOTHING), run())
        assertEquals(listOf("hold", "release"), log)
        log.clear()
        records.failPut = false
        records.failGet = true
        assertEquals(DeletionOutcome.NoRecord(ErasedSoFar.SOME), run())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `every attempt erases again, also when only the account was left`() = runTest {
        records.records.value = listOf(record(DeletionStage.CONTENT_ERASED))
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("erase u1 -", "account u1"), calls())
    }

    @Test
    fun `an unfinished run goes on with its saved token, a restarted one with the new token`() = runTest {
        records.records.value = listOf(record(token = "old", leftover = setOf(Leftover.CHANNEL)))
        api.erases += listOf(more("new", ""","restarted":true"""), done)
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("erase u1 old", "erase u1 new", "account u1"), calls())
    }

    @Test
    fun `an old sign-in is refused before anything, with a wider margin once a deletion started`() = runTest {
        device.authTime = now - 301
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING), run())
        assertEquals(emptyList<String>(), log)
        records.records.value = listOf(record())
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        records.records.value = listOf(record())
        device.authTime = now - 571
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.SOME), run())
    }

    @Test
    fun `the server's clock decides, not the phone's`() = runTest {
        api.now = now
        now += 5_000 // the phone is fast
        device.authTime = api.now!! - 10
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        api.now = now
        now -= 20_000 // the phone is slow
        device.authTime = api.now!! - 4_000
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING), run())
    }

    @Test
    fun `the account is read from properties id before every call, never from sub`() = runTest {
        device.tokenFor = { tokenOf("u2") }
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.NOTHING), run())
        device.tokenFor = { jwt("""{"sub":"u1","auth_time":${now - 10}}""") }
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.NOTHING), run())
        device.tokenFor = { null }
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.NOTHING), run())
        assertEquals(emptyList<String>(), calls())

        // Another account after the first pass: nothing more is called, and it is said that some may be gone.
        var reads = 0
        device.tokenFor = { tokenOf(if (++reads <= 2) "u1" else "u2") }
        api.erases += more("t1")
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.SOME), run())
        assertEquals(listOf("erase u1 -"), calls())

        // Another account between the erase and the account's own deletion.
        records.records.value = emptyList()
        log.clear()
        reads = 0
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.CONTENT), run())
        assertEquals(listOf("erase u1 -"), calls())
    }

    @Test
    fun `a refused first pass removed nothing and leaves no record`() = runTest {
        for (status in listOf(400, 401, 403, 404)) {
            api.erases += DeletionAnswer(status, "{}")
            assertEquals("$status", DeletionOutcome.Failed(ErasedSoFar.NOTHING), run())
            assertTrue("$status", records.records.value.isEmpty())
        }
        api.erases += DeletionAnswer(401, """{"error":"insufficient_user_authentication","max_age":600}""")
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING), run())
        assertTrue(records.records.value.isEmpty())
        assertTrue(calls().none { it.startsWith("account") })
    }

    @Test
    fun `a refused later pass keeps the record, some content may be gone`() = runTest {
        api.erases += listOf(more("t1"), DeletionAnswer(403, "{}"))
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
        assertEquals("t1", records.get("u1")?.resumeToken)
    }

    @Test
    fun `503 waits at least Retry-After, remembers it, and never goes on to step 2`() = runTest {
        api.erases += DeletionAnswer(503, """{"code":"resume_unavailable"}""", "120")
        assertEquals(DeletionOutcome.Wait((now + 120) * 1000, ErasedSoFar.SOME), run())
        assertEquals(listOf("erase u1 -"), calls())
        // Too early: nothing is called, also by a new attempt.
        now += 119
        assertEquals(DeletionOutcome.Wait((now + 1) * 1000, ErasedSoFar.SOME), run())
        assertEquals(listOf("erase u1 -"), calls())
        now += 2
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
    }

    @Test
    fun `a long Retry-After is kept whole, also for a new attempt`() = runTest {
        api.erases += DeletionAnswer(503, "{}", "86400")
        val until = (now + 86_400) * 1000
        assertEquals(DeletionOutcome.Wait(until, ErasedSoFar.SOME), run())
        assertEquals(until, records.get("u1")?.notBeforeMs)
        now += 86_399
        assertEquals(DeletionOutcome.Wait(until, ErasedSoFar.SOME), run())
        assertEquals(listOf("erase u1 -"), calls())
    }

    @Test
    fun `429 waits and removed nothing`() = runTest {
        api.erases += DeletionAnswer(429, "{}", "60")
        assertEquals(DeletionOutcome.Wait((now + 60) * 1000, ErasedSoFar.NOTHING), run())
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun `Retry-After is read as a number or a date, and never shortened`() {
        val at = 1_000_000_000_000L
        assertEquals(120L, AccountDeletion.retryAfterSeconds("120", at))
        assertEquals(300L, AccountDeletion.retryAfterSeconds(null, at))
        assertEquals(300L, AccountDeletion.retryAfterSeconds("soon", at))
        assertEquals(300L, AccountDeletion.retryAfterSeconds("-5", at))
        // Too large for a clock: ten years.
        assertEquals(315_360_000L, AccountDeletion.retryAfterSeconds("99999999999999999999", at))
        assertEquals(86_400L, AccountDeletion.retryAfterSeconds("86400", at))
        // 2001-09-09T01:46:40Z is `at`; ten minutes later.
        assertEquals(600L, AccountDeletion.retryAfterSeconds("Sun, 09 Sep 2001 01:56:40 GMT", at))
        assertEquals(172_800L, AccountDeletion.retryAfterSeconds("Tue, 11 Sep 2001 01:46:40 GMT", at))
    }

    @Test
    fun `no answer, a server error or an unfinished answer without a token is unknown, never nothing or done`() = runTest {
        for (answer in listOf(
            null,
            DeletionAnswer(500, ""),
            DeletionAnswer(200, "<html>"),
            DeletionAnswer(200, """{"complete":false}""")
        )) {
            records.records.value = emptyList()
            api.erases += answer
            assertEquals("$answer", DeletionOutcome.Failed(ErasedSoFar.SOME), run())
            assertEquals("$answer", DeletionStage.STARTED, records.get("u1")?.stage)
        }
        assertTrue(calls().none { it.startsWith("account") })
    }

    @Test
    fun `an unreadable channel is told before the account goes, and the person goes on or not`() = runTest {
        api.erases += complete("channel_unreadable:ch-1")
        assertEquals(DeletionOutcome.LeftBehind(setOf(Leftover.CHANNEL)), run())
        assertTrue(calls().none { it.startsWith("account") })
        assertEquals(setOf(Leftover.CHANNEL), records.get("u1")?.leftover)
        // Going on erases again, and deletes the account although the channel stays.
        api.erases += complete("channel_unreadable:ch-1")
        assertEquals(DeletionOutcome.Deleted(setOf(Leftover.CHANNEL)), run(accept = setOf(Leftover.CHANNEL)))
        assertEquals(listOf("erase u1 -", "erase u1 -", "account u1"), calls())
    }

    @Test
    fun `other errors are the same choice, and nobody goes on with more than they were told`() = runTest {
        val channel = setOf(Leftover.CHANNEL)
        val other = setOf(Leftover.OTHER)
        api.erases += complete("backups_unbound")
        assertEquals(DeletionOutcome.LeftBehind(other), run())
        api.erases += complete("channel_unreadable:ch-1")
        assertEquals(DeletionOutcome.LeftBehind(channel), run(accept = other))
        // A channel says nothing about other records.
        api.erases += complete("backups_unbound")
        assertEquals(DeletionOutcome.LeftBehind(other), run(accept = channel))
        assertTrue(calls().none { it.startsWith("account") })
    }

    @Test
    fun `a channel and other records are both told, and both needed to go on`() = runTest {
        val both = setOf(Leftover.CHANNEL, Leftover.OTHER)
        for (accepted in listOf(emptySet(), setOf(Leftover.CHANNEL), setOf(Leftover.OTHER))) {
            api.erases += complete("backups_unbound", "channel_unreadable:ch-1")
            assertEquals("$accepted", DeletionOutcome.LeftBehind(both), run(accept = accepted))
            assertEquals("$accepted", both, records.get("u1")?.leftover)
        }
        // Also when two passes of one run reported one each.
        api.erases += listOf(more("t1", ""","errors":["channel_unreadable:ch-1"]"""), complete("backups_unbound"))
        assertEquals(DeletionOutcome.LeftBehind(both), run(accept = setOf(Leftover.CHANNEL)))
        assertTrue(calls().none { it.startsWith("account") })
        // Less than they were told is no new question.
        api.erases += complete("backups_unbound")
        assertEquals(DeletionOutcome.Deleted(setOf(Leftover.OTHER)), run(accept = both))
    }

    @Test
    fun `an error reported by an earlier pass is not forgotten by the last one`() = runTest {
        api.erases += listOf(more("t1", ""","errors":["channel_unreadable:ch-1"]"""), done)
        assertEquals(DeletionOutcome.LeftBehind(setOf(Leftover.CHANNEL)), run())
    }

    @Test
    fun `after 50 passes, or a stop, the person confirms again and the progress is kept`() = runTest {
        repeat(60) { api.erases += more("t$it") }
        assertEquals(DeletionOutcome.Paused(stopped = false, ErasedSoFar.SOME), run())
        assertEquals(50, calls().size)
        assertEquals("t49", records.get("u1")?.resumeToken)
        api.erases.clear()
        api.erases += more("a")
        assertEquals(DeletionOutcome.Paused(stopped = true, ErasedSoFar.SOME), run(stop = { true }))
        assertEquals("a", records.get("u1")?.resumeToken)
    }

    @Test
    fun `a lost answer of step 2 is asked again, and stays unknown when it stays lost`() = runTest {
        api.accounts += listOf(null, gone)
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        log.clear()
        api.accounts += listOf(null, null)
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("erase u1 -", "account u1", "account u1"), calls())
        assertEquals(DeletionStage.CONTENT_ERASED, records.get("u1")?.stage)
        assertTrue("purge" !in log && "end" !in log)
        // Auth may have committed behind a 5xx; a token it now refuses doesn't say "failed".
        api.accounts += listOf(DeletionAnswer(502, ""), DeletionAnswer(401, """{"error":"invalid_token"}"""))
        assertEquals(DeletionOutcome.Unknown, run())
    }

    @Test
    fun `404 not_found right after the erase is deleted, any other 404 and a bare 200 are not`() = runTest {
        api.accounts += DeletionAnswer(404, """{"error":"not_found"}""")
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        for (answer in listOf(
            DeletionAnswer(404, "<html>"),
            DeletionAnswer(404, """{"error":"user_not_found"}"""),
            DeletionAnswer(200, "{}")
        )) {
            api.accounts += answer
            assertEquals("$answer", DeletionOutcome.Failed(ErasedSoFar.CONTENT), run())
            assertEquals("$answer", DeletionStage.CONTENT_ERASED, records.get("u1")?.stage)
        }
    }

    @Test
    fun `a sign-in that got old during the erase says the content is gone and the account isn't`() = runTest {
        api.accounts += DeletionAnswer(401, """{"error":"insufficient_user_authentication","max_age":600}""")
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.CONTENT), run())
        assertEquals(DeletionStage.CONTENT_ERASED, records.get("u1")?.stage)
        // With something left behind, it doesn't say that the content is gone.
        api.erases += complete("x")
        api.accounts += DeletionAnswer(401, """{"error":"insufficient_user_authentication"}""")
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.SOME), run(accept = setOf(Leftover.OTHER)))
    }

    @Test
    fun `uploads wait during the deletion and go on when it stops`() = runTest {
        api.erases += DeletionAnswer(500, "")
        run()
        assertEquals(listOf("hold", "release"), log.filter { it == "hold" || it == "release" })
    }

    @Test
    fun `nothing is called or recorded while uploads can't be held, and they go on`() = runTest {
        device.holdFails = true
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.NOTHING), run())
        assertEquals(listOf("hold", "release"), log)
        records.records.value = listOf(record(token = "t1"))
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
        assertEquals("t1", records.get("u1")?.resumeToken)
        assertEquals(emptyList<String>(), calls())
        device.holdFails = false
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
    }

    @Test
    fun `a cleanup that fails never turns success into an error and is finished at the next start`() = runTest {
        device.purgeFails = true
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(DeletionStage.DELETED, records.get("u1")?.stage)
        assertTrue("end" in log)
        log.clear()
        device.purgeFails = false
        deletion.finishCleanups()
        assertEquals(listOf("purge", "end", "remove"), log)
        assertNull(records.get("u1"))
    }

    @Test
    fun `a start cleans up only what was proven deleted`() = runTest {
        records.records.value = listOf(record(), record(DeletionStage.CONTENT_ERASED).copy(userId = "u2"))
        deletion.finishCleanups()
        assertEquals(emptyList<String>(), log)
        assertEquals(2, records.records.value.size)
    }

    @Test
    fun `an account restored after a finished deletion starts from the beginning`() = runTest {
        records.records.value = listOf(record(DeletionStage.DELETED, token = "old", leftover = setOf(Leftover.CHANNEL)))
        device.authTime = now - 400 // a new deletion has the first pass's margin
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING), run())
        device.authTime = now - 10
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals("erase u1 -", calls().first())
    }

    private fun calls() = log.filter { it.startsWith("erase") || it.startsWith("account") }

    private fun record(stage: DeletionStage = DeletionStage.STARTED, token: String? = null, leftover: Set<Leftover> = emptySet()) =
        DeletionRecord("u1", "u1@example.org", stage, token, leftover, startedAtMs = 1)

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"ES256"}""", payload).joinToString(".") { enc.encodeToString(it.toByteArray()) } + ".sig"
    }

    private fun tokenOf(userId: String) =
        jwt("""{"sub":"user:3f2a9c8b10294e1f","properties":{"id":"$userId"},"auth_time":${device.authTime}}""")

    private inner class Records : DeletionRecords {
        var failPut = false
        var failGet = false
        override val records = MutableStateFlow<List<DeletionRecord>>(emptyList())
        override fun get(userId: String): DeletionRecord? {
            check(!failGet) { "unreadable" }
            return records.value.firstOrNull { it.userId == userId }
        }
        override fun put(record: DeletionRecord) {
            check(!failPut) { "full" }
            log += "save ${record.stage} ${record.resumeToken ?: "-"}"
            records.value = records.value.filterNot { it.userId == record.userId } + record
        }
        override fun remove(userId: String) {
            log += "remove"
            records.value = records.value.filterNot { it.userId == userId }
        }
    }

    private inner class Api : DeletionApi {
        val erases = ArrayDeque<DeletionAnswer?>()
        val accounts = ArrayDeque<DeletionAnswer?>()
        var now: Long? = null
        override suspend fun erase(token: String, resumeToken: String?): DeletionAnswer {
            log += "erase ${DeletionToken.userIdOf(token)} ${resumeToken ?: "-"}"
            return if (erases.isEmpty()) done else erases.removeFirst() ?: throw IOException("lost")
        }
        override suspend fun deleteAccount(token: String): DeletionAnswer {
            log += "account ${DeletionToken.userIdOf(token)}"
            return if (accounts.isEmpty()) gone else accounts.removeFirst() ?: throw IOException("lost")
        }
        override suspend fun serverNowSeconds() = now
    }

    private inner class Device : DeletionDevice {
        var authTime = now - 10
        var tokenFor: () -> String? = { tokenOf("u1") }
        var purgeFails = false
        var holdFails = false
        override suspend fun token(userId: String) = tokenFor()
        override suspend fun holdUploads(userId: String) {
            log += "hold"
            check(!holdFails) { "a request is out" }
        }
        override suspend fun releaseUploads(userId: String) {
            log += "release"
        }
        override suspend fun purgeUploads(userId: String) {
            if (purgeFails) throw IOException("no space")
            log += "purge"
        }
        override suspend fun endSession(userId: String) {
            log += "end"
        }
    }
}
