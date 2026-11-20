package net.alummahnow.core.bridge.studio.core

import java.io.IOException
import net.alummahnow.core.AunException
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.attestation.NATIVE_PROOF_REQUIRED
import net.alummahnow.core.bridge.studio.StudioError

/** Maps aun-core errors to the bridge's error types the UI understands. */
internal fun mapCoreError(e: Throwable): Throwable = when (e) {
    is AunException.Network -> CoreError.Network(IOException(e.detail))
    is AunException.Offline -> CoreError.Network(IOException("offline"))
    is AunException.Unauthorized -> CoreError.NotSignedIn()
    is AunException.Forbidden -> StudioError.Forbidden()
    is AunException.NotFound -> if (isFeatureOff(e)) StudioError.FeatureOff() else CoreError.NotFound(e.detail)
    is AunException.InvalidInput -> StudioError.Invalid(e.detail)
    is AunException.RateLimited -> CoreError.Http(HTTP_TOO_MANY)
    is AunException.Http -> mapHttp(e)
    is AunException -> CoreError.Http(0).also { it.initCause(e) }
    else -> e
}

private fun mapHttp(e: AunException.Http): Throwable = when (e.status.toInt()) {
    HTTP_PAYMENT_REQUIRED -> StudioError.UpgradeRequired(e.code)
    HTTP_GONE -> StudioError.RestoreExpired()
    HTTP_UNPROCESSABLE, HTTP_BAD_REQUEST -> StudioError.Invalid(e.detail)
    else -> CoreError.Http(e.status.toInt())
}

/**
 * True for a gated API's 404 `{"success":false,"code":"feature_off"}`. aun-core keeps a 404's message only; with no
 * `error`/`message` in the body the message is the body itself, so the code is in it.
 */
internal fun isFeatureOff(e: Throwable): Boolean = when (e) {
    is AunException.NotFound -> e.detail.contains(FEATURE_OFF)
    is AunException.Http -> e.code == FEATURE_OFF
    else -> false
}

internal const val FEATURE_OFF = "feature_off"

/** Runs [block], rethrowing aun-core errors as bridge errors. */
internal inline fun <T> coreCall(block: () -> T): T = try {
    block()
} catch (e: AunException) {
    throw mapCoreError(e)
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_GONE = 410
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY = 429

/**
 * True for the server's 403 `native_proof_required`. aun-core keeps only the message of a 403 (not its `code`), so
 * the server sends the code as the message too (NAT-REL contract: `{code, error: "native_proof_required"}`).
 */
internal fun isNativeProofRequired(e: Throwable): Boolean = when (e) {
    is AunException.Forbidden -> e.detail.contains(NATIVE_PROOF_REQUIRED)
    is AunException.Http -> e.code == NATIVE_PROOF_REQUIRED
    else -> false
}
