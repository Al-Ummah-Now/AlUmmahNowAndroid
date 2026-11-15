package net.alummahnow.core.bridge.attestation

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.CoreError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(vararg proofs: String?) : NativeProofProvider {
    private val queue = ArrayDeque(proofs.toList())
    var invalidations = 0
    override suspend fun proof(): String? = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
    override fun invalidate() {
        invalidations++
    }
}

private class ProofRequired : Exception()

class WithNativeProofTest {
    private val required: (Throwable) -> Boolean = { it is ProofRequired }

    @Test fun `reads never carry the header and never ask for a proof`() = runTest {
        val provider = FakeProvider("p1")
        val seen = withNativeProof(provider, "GET", required) { it }
        assertEquals(emptyMap<String, String>(), seen)
    }

    @Test fun `every write method carries the proof`() = runTest {
        for (m in listOf("POST", "PUT", "PATCH", "DELETE", "post")) {
            val seen = withNativeProof(FakeProvider("p1"), m, required) { it }
            assertEquals(mapOf(NATIVE_PROOF_HEADER to "p1"), seen)
        }
    }

    @Test fun `no proof means no header`() = runTest {
        assertEquals(emptyMap<String, String>(), withNativeProof(NoNativeProof, "POST", required) { it })
    }

    @Test fun `native_proof_required refreshes once and retries once`() = runTest {
        val provider = FakeProvider("old", "new")
        val calls = mutableListOf<Map<String, String>>()
        val result = withNativeProof(provider, "PUT", required) { h ->
            calls += h
            if (calls.size == 1) throw ProofRequired() else "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(mapOf(NATIVE_PROOF_HEADER to "old"), mapOf(NATIVE_PROOF_HEADER to "new")), calls)
        assertEquals(1, provider.invalidations)
    }

    @Test fun `a second refusal propagates, with no third try`() = runTest {
        var calls = 0
        val error = runCatching {
            withNativeProof(FakeProvider("a", "b"), "POST", required) {
                calls++
                throw ProofRequired()
            }
        }.exceptionOrNull()
        assertTrue(error is ProofRequired)
        assertEquals(2, calls)
    }

    @Test fun `no fresh proof after a refusal rethrows the refusal without retrying`() = runTest {
        var calls = 0
        val error = runCatching {
            withNativeProof(FakeProvider(null), "POST", required) {
                calls++
                throw ProofRequired()
            }
        }.exceptionOrNull()
        assertTrue(error is ProofRequired)
        assertEquals(1, calls)
    }

    @Test fun `other errors propagate untouched`() = runTest {
        val provider = FakeProvider("p")
        val error = runCatching { withNativeProof(provider, "POST", required) { throw CoreError.Http(403) } }.exceptionOrNull()
        assertTrue(error is CoreError.Http)
        assertEquals(0, provider.invalidations)
    }

    @Test fun `request hash is lowercase hex SHA-256 of the UTF-8 challenge`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", requestHash("abc"))
    }
}

private class FakeApi(var config: AttestationConfig, private val expiresIn: Duration, private val clock: () -> Instant) : AttestationApi {
    var configCalls = 0
    var challenges = 0
    val exchanged = mutableListOf<Triple<String, String, String>>()
    var fail = false
    override suspend fun config(): AttestationConfig {
        configCalls++
        return config
    }
    override suspend fun challenge(): Challenge {
        if (fail) throw CoreError.Http(500)
        challenges++
        return Challenge("ch$challenges", null)
    }
    override suspend fun android(token: String, challenge: String, packageName: String): Proof {
        exchanged += Triple(token, challenge, packageName)
        return Proof("proof-$challenge", clock().plus(expiresIn))
    }
}

private class FakeSource(override val available: Boolean = true) : IntegrityTokenSource {
    val requests = mutableListOf<Pair<Long, String>>()
    override suspend fun token(cloudProjectNumber: Long, requestHash: String): String {
        requests += cloudProjectNumber to requestHash
        return "token${requests.size}"
    }
}

class PlayIntegrityProofProviderTest {
    private var now = Instant.parse("2026-11-14T12:00:00Z")
    private val on = AttestationConfig(androidEnabled = true, cloudProjectNumber = 1234L)

    private fun provider(api: FakeApi, source: FakeSource = FakeSource()) = PlayIntegrityProofProvider(
        api,
        source,
        "net.alummahnow.studio"
    ) {
        now
    }

    @Test fun `off by config means no proof and no challenge`() = runTest {
        val api = FakeApi(AttestationConfig.OFF, Duration.ofHours(1)) { now }
        assertNull(provider(api).proof())
        assertEquals(0, api.challenges)
    }

    @Test fun `enabled without a project number stays off`() = runTest {
        val api = FakeApi(AttestationConfig(true, null), Duration.ofHours(1)) { now }
        assertNull(provider(api).proof())
        assertEquals(0, api.challenges)
    }

    @Test fun `no Play Store (Amazon, no GMS) is a no-op that never calls the server`() = runTest {
        val api = FakeApi(on, Duration.ofHours(1)) { now }
        assertNull(provider(api, FakeSource(available = false)).proof())
        assertEquals(0, api.configCalls)
    }

    @Test fun `enabled fetches a proof bound to the challenge and caches it`() = runTest {
        val api = FakeApi(on, Duration.ofHours(1)) { now }
        val source = FakeSource()
        val p = provider(api, source)
        assertEquals("proof-ch1", p.proof())
        assertEquals("proof-ch1", p.proof())
        assertEquals(1, api.challenges)
        assertEquals(listOf(1234L to requestHash("ch1")), source.requests)
        assertEquals(Triple("token1", "ch1", "net.alummahnow.studio"), api.exchanged.single())
    }

    @Test fun `a proof is renewed 5 minutes before it expires`() = runTest {
        val api = FakeApi(on, Duration.ofMinutes(30)) { now }
        val p = provider(api)
        p.proof()
        now = now.plus(Duration.ofMinutes(24))
        assertEquals("proof-ch1", p.proof())
        now = now.plus(Duration.ofMinutes(2)) // 26 min: inside the 5-minute margin
        assertEquals("proof-ch2", p.proof())
    }

    @Test fun `invalidate forces a new proof`() = runTest {
        val api = FakeApi(on, Duration.ofHours(1)) { now }
        val p = provider(api)
        p.proof()
        p.invalidate()
        assertEquals("proof-ch2", p.proof())
    }

    @Test fun `config is cached for an hour`() = runTest {
        val api = FakeApi(AttestationConfig.OFF, Duration.ofHours(1)) { now }
        val p = provider(api)
        p.proof()
        api.config = on
        assertNull(p.proof())
        now = now.plus(Duration.ofMinutes(61))
        assertEquals("proof-ch1", p.proof())
        assertEquals(2, api.configCalls)
    }

    @Test fun `errors are swallowed into no proof, never thrown`() = runTest {
        val api = FakeApi(on, Duration.ofHours(1)) { now }.apply { fail = true }
        assertNull(provider(api).proof())
        assertTrue(api.exchanged.isEmpty())
    }
}
