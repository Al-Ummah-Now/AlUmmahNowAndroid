package net.alummahnow.core.bridge.attestation

import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.alummahnow.core.bridge.AunLog

/**
 * Play Integrity **standard** request token source. The real one wraps `StandardIntegrityManager` (module
 * `:integrity-play`, Google Play builds only); tests use fakes.
 */
interface IntegrityTokenSource {
    /** False where Play Integrity can't work (no Play Store / Google Play services, e.g. Amazon devices). */
    val available: Boolean

    /** A token bound to [requestHash], from a provider prepared for [cloudProjectNumber]. */
    suspend fun token(cloudProjectNumber: Long, requestHash: String): String
}

/**
 * [NativeProofProvider] for Android (NAT-REL contract §2): reads the server config (cached [CONFIG_TTL]); when
 * Android attestation is on, gets a challenge, asks Play Integrity for a token bound to `requestHash(challenge)`,
 * exchanges it for a proof and caches the proof until [PROOF_MARGIN] before it expires.
 */
class PlayIntegrityProofProvider(
    private val api: AttestationApi,
    private val source: IntegrityTokenSource,
    private val packageName: String,
    private val now: () -> Instant = Instant::now
) : NativeProofProvider {
    private val lock = Mutex()
    private var config: AttestationConfig? = null
    private var configAt: Instant = Instant.MIN
    private var cached: Proof? = null

    override suspend fun proof(): String? {
        if (!source.available) return null
        return lock.withLock {
            val result = runCatching { cached?.takeIf { now().isBefore(it.expiresAt.minus(PROOF_MARGIN)) }?.value ?: fetch() }
            result.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                // Never block the request: the server decides what an unproven write may do.
                AunLog.w("attestation", "no proof", "type" to e.javaClass.simpleName)
            }
            result.getOrNull()
        }
    }

    override fun invalidate() {
        cached = null
    }

    private suspend fun fetch(): String? {
        val cfg = currentConfig()
        val project = cfg.cloudProjectNumber
        if (!cfg.androidEnabled || project == null) return null
        val challenge = api.challenge().value
        val token = source.token(project, requestHash(challenge))
        val proof = api.android(token, challenge, packageName)
        cached = proof
        return proof.value
    }

    private suspend fun currentConfig(): AttestationConfig {
        val known = config
        if (known != null && now().isBefore(configAt.plus(CONFIG_TTL))) return known
        return api.config().also {
            config = it
            configAt = now()
        }
    }

    companion object {
        val CONFIG_TTL: Duration = Duration.ofHours(1)
        val PROOF_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
