package net.alummahnow.core.bridge.attestation

import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Header carrying the server-issued attestation proof on write requests to the videos API (NAT-REL contract §2). */
const val NATIVE_PROOF_HEADER = "X-AUN-Native-Proof"

/** The server's 403 code when a write needs a (fresh) proof. */
const val NATIVE_PROOF_REQUIRED = "native_proof_required"

/**
 * Supplies the `X-AUN-Native-Proof` value for videos API writes. Attestation is off unless the videos admin turns
 * it on: then [proof] returns null and no header is sent. It never throws and never blocks the UI on a failure:
 * errors are logged and the request goes on without the header (the server decides).
 */
interface NativeProofProvider {
    /** A cached proof, a freshly fetched one, or null (attestation off, unsupported device, or an error). */
    suspend fun proof(): String?

    /** Drops the cached proof, after the server answered 403 [NATIVE_PROOF_REQUIRED]. */
    fun invalidate()
}

/** Builds without attestation: Amazon / Fire TV, TV, desktop-like targets, and tests. */
object NoNativeProof : NativeProofProvider {
    override suspend fun proof(): String? = null
    override fun invalidate() = Unit
}

private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/** POST, PUT, PATCH and DELETE carry the proof; reads never do. */
fun isWriteMethod(method: String): Boolean = method.uppercase() in WRITE_METHODS

/**
 * Runs a videos API request through [block], adding [NATIVE_PROOF_HEADER] when [method] is a write and a proof is
 * available. If the server answers 403 [NATIVE_PROOF_REQUIRED] ([isProofRequired] recognises the transport's
 * error), fetches a new proof once and retries once; otherwise the original error propagates.
 */
suspend fun <T> withNativeProof(
    provider: NativeProofProvider,
    method: String,
    isProofRequired: (Throwable) -> Boolean,
    block: suspend (headers: Map<String, String>) -> T
): T {
    if (!isWriteMethod(method)) return block(emptyMap())
    val first = runCatching { block(headersFor(provider.proof())) }
    val error = first.exceptionOrNull() ?: return first.getOrThrow()
    if (error is CancellationException || !isProofRequired(error)) throw error
    provider.invalidate()
    val fresh = provider.proof() ?: throw error
    return block(headersFor(fresh))
}

private fun headersFor(proof: String?): Map<String, String> = proof?.let { mapOf(NATIVE_PROOF_HEADER to it) }.orEmpty()

/** Play Integrity's `requestHash`: lowercase hex SHA-256 of the UTF-8 challenge (contract §2). */
fun requestHash(challenge: String): String =
    // display-format: not shown (hash)
    MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
