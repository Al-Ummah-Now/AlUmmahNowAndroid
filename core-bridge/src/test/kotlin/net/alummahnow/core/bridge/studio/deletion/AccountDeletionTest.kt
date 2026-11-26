package net.alummahnow.core.bridge.studio.deletion

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared rules (wiki guides/data-lifecycle.md, "Self-service account deletion"), row by row. */
@Suppress("LargeClass") // one table of rules, one set of fakes
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
                "hold", "receipt u1", "save STARTED -", "erase u1 -", "save STARTED t1", "erase u1 t1", "save STARTED t2", "erase u1 t2",
                "save CONTENT_ERASED -", "save CONTENT_ERASED - unsure", "account u1", "save DELETED -", "purge", "end", "remove"
            ),
            log
        )
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun `nothing is called when the record can't be saved or read`() = runTest {
        records.failPut = true
        assertEquals(DeletionOutcome.NoRecord(ErasedSoFar.NOTHING), run())
        assertEquals(listOf("hold", "receipt u1", "release"), log)
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
        device.tokenFor = { tokenOf(if (++reads <= 3) "u1" else "u2") }
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

    // --- After a lost answer (Auth: POST /account/deletion-receipt, GET /account/status) ---

    private val isDeleted = DeletionAnswer(200, """{"deleted":true}""")
    private val isLive = DeletionAnswer(200, """{"deleted":false}""")
    private val refused = DeletionAnswer(401, """{"error":"invalid_token"}""")
    private val badReceipt = DeletionAnswer(401, """{"error":"invalid_receipt"}""")

    /** A deletion whose answer never came. */
    private fun lost(stage: DeletionStage = DeletionStage.CONTENT_ERASED, leftover: Set<Leftover> = emptySet()) =
        record(
            stage,
            leftover = leftover
        ).copy(receipt = "r0", receiptExpiresAtMs = (now + 1_000) * 1000, unsure = true, sentIssuedAt = now)

    private fun allCalls() = log.filter { it.substringBefore(' ') in setOf("receipt", "erase", "account", "status") }

    @Test
    fun `the receipt is asked for before anything is removed, and saved with the account`() = runTest {
        val until = (now + 2_000) * 1000
        api.receipts += DeletionAnswer(200, """{"receipt":"adr1.abc_-.x","expiresAt":$until}""")
        api.onErase = { assertEquals("adr1.abc_-.x" to until, records.get("u1")?.let { it.receipt to it.receiptExpiresAtMs }) }
        api.onAccount = { assertEquals("adr1.abc_-.x" to true, records.get("u1")?.let { it.receipt to it.unsure }) }
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("receipt u1", "erase u1 -", "account u1"), allCalls())
    }

    @Test
    fun `without a receipt nothing is removed and no record is left`() = runTest {
        for ((answer, outcome) in listOf(
            null to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            DeletionAnswer(500, "") to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            refused to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            DeletionAnswer(200, "{}") to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            DeletionAnswer(200, """{"receipt":"","expiresAt":9}""") to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            DeletionAnswer(200, """{"receipt":"r"}""") to DeletionOutcome.Failed(ErasedSoFar.NOTHING),
            DeletionAnswer(200, """{"receipt":"a\\nb","expiresAt":9}""") to DeletionOutcome.Failed(ErasedSoFar.NOTHING), // not for a header
            DeletionAnswer(
                401,
                """{"error":"insufficient_user_authentication"}"""
            ) to DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING),
            DeletionAnswer(429, "{}", "60") to DeletionOutcome.Wait((now + 60) * 1000, ErasedSoFar.NOTHING),
            DeletionAnswer(503, "{}", "60") to DeletionOutcome.Wait((now + 60) * 1000, ErasedSoFar.NOTHING)
        )) {
            log.clear()
            api.receipts += answer
            assertEquals("$answer", outcome, run())
            assertEquals("$answer", listOf("hold", "receipt u1", "release"), log)
            assertTrue("$answer", records.records.value.isEmpty())
        }
    }

    @Test
    fun `a retry without a new receipt keeps its record, and a wait is remembered`() = runTest {
        records.records.value = listOf(record(token = "t1"))
        api.receipts += null
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
        assertEquals(listOf(record(token = "t1")), records.records.value)
        api.receipts += DeletionAnswer(429, "{}", "60")
        assertEquals(DeletionOutcome.Wait((now + 60) * 1000, ErasedSoFar.SOME), run())
        assertEquals((now + 60) * 1000 to "t1", records.get("u1")?.let { it.notBeforeMs to it.resumeToken })
        now += 61
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertTrue("erase u1 t1" in log)
    }

    @Test
    fun `a server without receipts deletes as before`() = runTest {
        api.receipts += DeletionAnswer(404, "")
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        log.clear()
        api.receipts += DeletionAnswer(404, "")
        api.accounts += listOf(null, null)
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("receipt u1", "erase u1 -", "account u1", "account u1"), allCalls())
        // A receipt saved earlier still answers.
        log.clear()
        records.records.value = listOf(lost())
        api.receipts += DeletionAnswer(404, "Not found")
        api.statuses += listOf(isLive, isDeleted)
        api.erases += refused
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("status r0", "receipt u1", "erase u1 -", "status r0"), allCalls())
        // Nor does a status route that isn't there end the receipt.
        log.clear()
        records.records.value = listOf(lost())
        api.statuses += DeletionAnswer(404, "Not found")
        api.receipts += null
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("status r0", "receipt u1"), allCalls())
        assertEquals(listOf(lost()), records.records.value)
    }

    @Test
    fun `an account Auth doesn't have any more is gone, and nothing is erased`() = runTest {
        val notFound = DeletionAnswer(404, """{"error":"not_found","error_description":"Account not found"}""")
        api.receipts += notFound
        assertEquals(DeletionOutcome.Deleted(emptySet(), elsewhere = true), run())
        assertEquals(listOf("hold", "receipt u1", "save DELETED -", "purge", "end", "remove"), log)
        // An erase that hadn't finished says nothing of the content; a finished one knows what stayed.
        for ((before, expected) in listOf(
            record(token = "t1") to DeletionOutcome.Deleted(emptySet(), elsewhere = true),
            record(DeletionStage.CONTENT_ERASED, leftover = setOf(Leftover.CHANNEL)) to DeletionOutcome.Deleted(setOf(Leftover.CHANNEL))
        )) {
            log.clear()
            records.records.value = listOf(before)
            api.receipts += notFound
            assertEquals(expected, run())
            assertEquals(listOf("receipt u1"), allCalls())
            assertTrue("release" !in log)
        }
        // Any other 404 is a server without the route.
        for (body in listOf("Not found", "404 Not Found", "{}", """{"error":"user_not_found"}""")) {
            log.clear()
            api.receipts += DeletionAnswer(404, body)
            assertEquals(body, DeletionOutcome.Deleted(emptySet()), run())
            assertEquals(body, listOf("receipt u1", "erase u1 -", "account u1"), allCalls())
        }
    }

    @Test
    fun `an expired receipt is dropped without asking, by the server's clock`() = runTest {
        val expired = lost().copy(receiptExpiresAtMs = now * 1000)
        records.records.value = listOf(expired)
        api.now = now
        api.receipts += null
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("receipt u1"), allCalls())
        assertEquals(listOf(expired.copy(receipt = null, receiptExpiresAtMs = 0)), records.records.value)
        // A phone that is fast doesn't end it.
        log.clear()
        records.records.value = listOf(expired)
        api.now = now - 10
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("status r0"), allCalls())
    }

    @Test
    fun `offline, a phone's clock never ends a receipt`() = runTest {
        val expired = lost().copy(receiptExpiresAtMs = now * 1000)
        records.records.value = listOf(expired)
        assertEquals(DeletionOutcome.Unknown, run())
        assertTrue(deletion.settleLost().isEmpty())
        assertEquals(emptyList<String>(), allCalls())
        assertEquals("r0", records.get("u1")?.receipt)
    }

    @Test
    fun `a receipt refused during an attempt goes at once`() = runTest {
        api.accounts += listOf(null, null)
        api.statuses += badReceipt
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(null to true, records.get("u1")?.let { it.receipt to it.unsure })
        log.clear()
        assertTrue(deletion.settleLost().isEmpty())
        assertTrue(allCalls().none { it.startsWith("status") })
    }

    @Test
    fun `while the account may be deleted its uploads stay held, until it is known`() = runTest {
        api.accounts += listOf(null, null)
        api.statuses += null
        assertEquals(DeletionOutcome.Unknown, run())
        assertTrue("release" !in log)
        // A retry that can't find out holds them still.
        log.clear()
        api.statuses += null
        assertEquals(DeletionOutcome.Unknown, run())
        assertTrue("release" !in log)
        // There after all.
        log.clear()
        now += 60
        api.statuses += isLive
        assertTrue(deletion.settleLost().isEmpty())
        assertEquals(listOf("status r1", "release"), log.filter { it.startsWith("status") || it == "release" })
    }

    @Test
    fun `a new receipt says the account is there`() = runTest {
        records.records.value = listOf(lost())
        api.statuses += badReceipt
        api.erases += DeletionAnswer(500, "")
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
        assertEquals("r1" to false, records.get("u1")?.let { it.receipt to it.unsure })
        assertTrue("release" in log)
    }

    @Test
    fun `without a receipt, a token issued well after step 2 says the account is there`() = runTest {
        val sent = lost().copy(receipt = null, receiptExpiresAtMs = 0, sentIssuedAt = now - 100)
        records.records.value = listOf(sent)
        device.issuedAt = now + 1_700 // could still be from before Auth deleted it
        assertTrue(deletion.settleLost().isEmpty())
        assertEquals(true, records.get("u1")?.unsure)
        assertTrue("release" !in log)
        now += 60
        device.issuedAt = now + 1_800
        assertTrue(deletion.settleLost().isEmpty())
        assertEquals(false, records.get("u1")?.unsure)
        assertTrue("release" in log)
        // Another account's token says nothing.
        records.records.value = listOf(sent)
        now += 60
        device.tokenFor = { tokenOf("u2") }
        deletion.settleLost()
        assertEquals(true, records.get("u1")?.unsure)
    }

    @Test
    fun `step 2 saves when its token was issued`() = runTest {
        device.issuedAt = 4_242
        api.onAccount = { assertEquals(4_242L, records.get("u1")?.sentIssuedAt) }
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
    }

    @Test
    fun `a run taken up from an earlier attempt is followed by one from the start`() = runTest {
        records.records.value = listOf(record(token = "t1", leftover = setOf(Leftover.CHANNEL)))
        api.erases += listOf(complete("channel_unreadable:ch-1"), done)
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("erase u1 t1", "erase u1 -", "account u1"), calls())
        // Unless it began again by itself.
        log.clear()
        records.records.value = listOf(record(token = "t1"))
        api.erases += listOf(more("t2", ""","restarted":true"""), done)
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("erase u1 t1", "erase u1 t2", "account u1"), calls())
    }

    @Test
    fun `the app asks by itself a limited number of times, not too often, and a new attempt counts anew`() = runTest {
        records.records.value = listOf(lost().copy(receiptExpiresAtMs = Long.MAX_VALUE))
        repeat(100) {
            api.statuses += null
            deletion.settleLost()
            deletion.settleLost() // at once again
            now += 60
        }
        assertEquals(AccountDeletion.MAX_ASKS, allCalls().size)
        // The person may still ask.
        log.clear()
        api.statuses.clear()
        api.accounts += listOf(null, null)
        api.statuses += listOf(null)
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("status r0"), allCalls())
        api.statuses += listOf(isLive, null)
        device.authTime = now - 10
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(0, records.get("u1")?.asks)
        // A clock that went back doesn't stop it.
        records.records.value = listOf(lost().copy(receiptExpiresAtMs = Long.MAX_VALUE, lastAskMs = Long.MAX_VALUE))
        api.statuses += isDeleted
        assertEquals(setOf("u1"), deletion.settleLost().keys)
        // Not counted, not asked.
        log.clear()
        records.records.value = listOf(lost())
        records.failPut = true
        deletion.settleLost()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `not known can be dismissed, until something is known`() = runTest {
        records.records.value = listOf(record())
        deletion.dismiss("u1") // started and not finished: that stays
        assertEquals(false, records.get("u1")?.dismissed)
        records.records.value = listOf(lost())
        deletion.dismiss("u1")
        assertEquals(true, records.get("u1")?.dismissed)
        deletion.settleLost() // it is there
        assertEquals(false to false, records.get("u1")?.let { it.unsure to it.dismissed })
        records.failPut = true
        records.records.value = listOf(lost())
        deletion.dismiss("u1") // can't be saved: no crash
    }

    @Test
    fun `an account switched while uploads are held gets no receipt`() = runTest {
        device.onHold = { device.tokenFor = { tokenOf("u2") } }
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.NOTHING), run())
        assertEquals(listOf("hold", "release"), log)
    }

    @Test
    fun `an answer that stays lost is asked for, and a deletion that worked is finished`() = runTest {
        api.accounts += listOf(null, null)
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(listOf("receipt u1", "erase u1 -", "account u1", "account u1", "status r1"), allCalls())
        assertEquals(listOf("save DELETED -", "purge", "end", "remove"), log.takeLast(4))
        // The same when the session ended in between, or another account was made active.
        for (next in listOf<() -> String?>({ throw IOException("session ended") }, { tokenOf("u2") })) {
            log.clear()
            device.tokenFor = { tokenOf("u1") }
            api.onAccount = { device.tokenFor = next }
            api.accounts += null
            api.statuses += isDeleted
            assertEquals(DeletionOutcome.Deleted(emptySet()), run())
            assertEquals(listOf("receipt u1", "erase u1 -", "account u1", "status r1"), allCalls())
        }
    }

    @Test
    fun `step 2 is not called when it can't be saved that it is`() = runTest {
        records.failWhen = { it.unsure }
        assertEquals(DeletionOutcome.NoRecord(ErasedSoFar.CONTENT), run())
        assertEquals(listOf("receipt u1", "erase u1 -"), allCalls())
    }

    @Test
    fun `a retry asks first, and needs no session, sign-in or wait`() = runTest {
        records.records.value = listOf(lost(leftover = setOf(Leftover.CHANNEL)).copy(notBeforeMs = Long.MAX_VALUE))
        device.authTime = 0
        device.tokenFor = { throw IOException("session ended") }
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(setOf(Leftover.CHANNEL)), run())
        assertEquals(listOf("status r0", "save DELETED -", "purge", "end", "remove"), log)
    }

    @Test
    fun `a retry of an account that is there starts over with a new receipt, and is no longer unsure`() = runTest {
        records.records.value = listOf(lost())
        api.erases += DeletionAnswer(500, "")
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
        assertEquals(listOf("status r0", "receipt u1", "erase u1 -"), allCalls())
        assertEquals("r1" to false, records.get("u1")?.let { it.receipt to it.unsure })
    }

    @Test
    fun `without an answer nothing else is called, and nothing is said to be there or gone`() = runTest {
        val noAnswers = listOf(
            null,
            DeletionAnswer(500, ""),
            DeletionAnswer(503, "{}"),
            DeletionAnswer(429, "{}"),
            DeletionAnswer(403, "{}"),
            DeletionAnswer(200, "<html>"),
            DeletionAnswer(200, "{}")
        )
        for (answer in noAnswers) {
            log.clear()
            records.records.value = listOf(lost())
            api.statuses += answer
            assertEquals("$answer", DeletionOutcome.Unknown, run())
            assertEquals("$answer", listOf("status r0"), log)
            assertEquals("$answer", listOf(lost()), records.records.value)
        }
        // When step 2 was never sent, the attempt goes on.
        records.records.value = listOf(lost().copy(unsure = false))
        api.statuses += null
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
    }

    @Test
    fun `a receipt that can't be used any more is dropped, and the attempt goes on as without one`() = runTest {
        records.records.value = listOf(lost())
        api.statuses += badReceipt
        api.receipts += null
        assertEquals(DeletionOutcome.Unknown, run())
        assertEquals(listOf("status r0", "receipt u1"), allCalls())
        assertEquals(listOf(lost().copy(receipt = null, receiptExpiresAtMs = 0)), records.records.value)
        assertTrue("purge" !in log && "end" !in log)
        // Nor can the session say more: it may have ended because the account is gone.
        device.tokenFor = { throw IOException("session ended") }
        assertEquals(DeletionOutcome.Unknown, run())
    }

    @Test
    fun `an erase refused with 401 asks whether the account is gone`() = runTest {
        // Deleted somewhere else: this device knows nothing of the content.
        api.erases += refused
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(emptySet(), elsewhere = true), run())
        assertEquals(listOf("receipt u1", "erase u1 -", "status r1"), allCalls())
        assertTrue("purge" in log && "end" in log && records.records.value.isEmpty())
        // This device's erase had finished.
        records.records.value = listOf(record(DeletionStage.CONTENT_ERASED, leftover = setOf(Leftover.OTHER)))
        api.erases += refused
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(setOf(Leftover.OTHER)), run())
        // A pass of this attempt came in between: the content is not known any more.
        records.records.value = listOf(record(DeletionStage.CONTENT_ERASED))
        api.erases += listOf(more("t1"), refused)
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(emptySet(), elsewhere = true), run())
        // There: it is a refusal like any other.
        api.erases += refused
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.NOTHING), run())
        // A sign-in that is too old is no question.
        log.clear()
        api.erases += DeletionAnswer(401, """{"error":"insufficient_user_authentication"}""")
        assertEquals(DeletionOutcome.NeedsSignIn(300, ErasedSoFar.NOTHING), run())
        assertEquals(listOf("receipt u1", "erase u1 -"), allCalls())
    }

    @Test
    fun `a refused erase is unknown, not failed, while it isn't known what became of the account`() = runTest {
        // An Auth without receipts any more (a new one would say that the account is there).
        val noRoute = DeletionAnswer(404, "Not found")
        records.records.value = listOf(lost())
        api.statuses += badReceipt
        api.receipts += noRoute
        api.erases += refused
        assertEquals(DeletionOutcome.Unknown, run())
        records.records.value = listOf(lost())
        api.statuses += listOf(noRoute, null)
        api.receipts += noRoute
        api.erases += refused
        assertEquals(DeletionOutcome.Unknown, run())
        records.records.value = listOf(lost())
        api.statuses += listOf(noRoute, isLive)
        api.receipts += noRoute
        api.erases += refused
        assertEquals(DeletionOutcome.Failed(ErasedSoFar.SOME), run())
    }

    @Test
    fun `step 2 refused with 401 asks too, and a no is remembered as one`() = runTest {
        api.accounts += refused
        api.statuses += isDeleted
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        val signIn = DeletionAnswer(401, """{"error":"insufficient_user_authentication"}""")
        for ((answer, calls) in listOf(refused to 1, DeletionAnswer(403, "{}") to 0, signIn to 0)) {
            log.clear()
            records.records.value = emptyList()
            api.accounts += answer
            val outcome = run()
            val expected =
                if (answer == signIn) DeletionOutcome.NeedsSignIn(300, ErasedSoFar.CONTENT) else DeletionOutcome.Failed(ErasedSoFar.CONTENT)
            assertEquals("$answer", expected, outcome)
            assertEquals("$answer", calls, log.count { it == "status r1" })
            assertEquals("$answer", "r1" to false, records.get("u1")?.let { it.receipt to it.unsure })
        }
    }

    @Test
    fun `a start settles what was lost, and erases and deletes nothing`() = runTest {
        records.records.value = listOf(
            lost(leftover = setOf(Leftover.OTHER)),
            record().copy(userId = "u2"), // no receipt
            record(DeletionStage.DELETED).copy(userId = "u3", receipt = "r3") // proven already
        )
        api.statuses += isDeleted
        assertEquals(mapOf("u1" to DeletionOutcome.Deleted(setOf(Leftover.OTHER))), deletion.settleLost())
        assertEquals(listOf("save CONTENT_ERASED - unsure", "status r0", "save DELETED -", "purge", "end", "remove"), log)
        assertEquals(setOf("u2", "u3"), records.records.value.map { it.userId }.toSet())
        // No answer, there after all, then a receipt that expired.
        records.records.value = listOf(lost())
        api.statuses += listOf(null, isLive, badReceipt)
        for (expected in listOf("r0" to true, "r0" to false, null to false)) {
            assertTrue(deletion.settleLost().isEmpty())
            assertEquals(expected, records.get("u1")?.let { it.receipt to it.unsure })
            now += 60
        }
    }

    @Test
    fun `nothing is settled while an attempt runs`() = runTest {
        var settled: Map<String, DeletionOutcome.Deleted>? = null
        api.onAccount = { settled = deletion.settleLost() }
        assertEquals(DeletionOutcome.Deleted(emptySet()), run())
        assertEquals(emptyMap<String, DeletionOutcome.Deleted>(), settled)
        assertEquals(listOf("receipt u1", "erase u1 -", "account u1"), allCalls())
    }

    @Test
    fun `an attempt waits for a settling that is under way, and finds it done`() = runTest {
        records.records.value = listOf(lost())
        val gate = CompletableDeferred<Unit>()
        api.onStatus = { gate.await() }
        api.statuses += isDeleted
        val settling = async { deletion.settleLost() }
        runCurrent()
        device.tokenFor = { if ("end" in log) null else tokenOf("u1") }
        val attempt = async { run() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(setOf("u1"), settling.await().keys)
        assertEquals(DeletionOutcome.OtherAccount(ErasedSoFar.NOTHING), attempt.await())
        assertEquals(listOf("status r0"), allCalls())
    }

    private fun calls() = log.filter { it.startsWith("erase") || it.startsWith("account") }

    private fun record(stage: DeletionStage = DeletionStage.STARTED, token: String? = null, leftover: Set<Leftover> = emptySet()) =
        DeletionRecord("u1", "u1@example.org", stage, token, leftover, startedAtMs = 1)

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"ES256"}""", payload).joinToString(".") { enc.encodeToString(it.toByteArray()) } + ".sig"
    }

    private fun tokenOf(userId: String) =
        jwt("""{"sub":"user:3f2a9c8b10294e1f","properties":{"id":"$userId"},"auth_time":${device.authTime},"iat":${device.issuedAt}}""")

    private inner class Records : DeletionRecords {
        var failPut = false
        var failGet = false
        var failWhen: (DeletionRecord) -> Boolean = { false }
        override val records = MutableStateFlow<List<DeletionRecord>>(emptyList())
        override fun get(userId: String): DeletionRecord? {
            check(!failGet) { "unreadable" }
            return records.value.firstOrNull { it.userId == userId }
        }
        override fun put(record: DeletionRecord) {
            check(!failPut && !failWhen(record)) { "full" }
            log += "save ${record.stage} ${record.resumeToken ?: "-"}${if (record.unsure) " unsure" else ""}"
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
        var onAccount: suspend () -> Unit = {}
        var onErase: () -> Unit = {}
        var now: Long? = null
        override suspend fun erase(token: String, resumeToken: String?): DeletionAnswer {
            log += "erase ${DeletionToken.userIdOf(token)} ${resumeToken ?: "-"}"
            onErase()
            return if (erases.isEmpty()) done else erases.removeFirst() ?: throw IOException("lost")
        }
        override suspend fun deleteAccount(token: String): DeletionAnswer {
            log += "account ${DeletionToken.userIdOf(token)}"
            onAccount()
            return if (accounts.isEmpty()) gone else accounts.removeFirst() ?: throw IOException("lost")
        }
        val receipts = ArrayDeque<DeletionAnswer?>()
        val statuses = ArrayDeque<DeletionAnswer?>()
        var onStatus: suspend () -> Unit = {}
        override suspend fun deletionReceipt(token: String): DeletionAnswer {
            log += "receipt ${DeletionToken.userIdOf(token)}"
            return if (receipts.isEmpty()) {
                DeletionAnswer(200, """{"receipt":"r1","expiresAt":${Long.MAX_VALUE}}""")
            } else {
                receipts.removeFirst() ?: throw IOException("lost")
            }
        }
        override suspend fun accountStatus(receipt: String): DeletionAnswer {
            log += "status $receipt"
            onStatus()
            return if (statuses.isEmpty()) isLive else statuses.removeFirst() ?: throw IOException("lost")
        }
        override suspend fun serverNowSeconds() = now
    }

    private inner class Device : DeletionDevice {
        var authTime = now - 10
        var issuedAt = now
        var tokenFor: () -> String? = { tokenOf("u1") }
        var purgeFails = false
        var holdFails = false
        override suspend fun token(userId: String) = tokenFor()
        var onHold: () -> Unit = {}
        override suspend fun holdUploads(userId: String) {
            log += "hold"
            onHold()
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
