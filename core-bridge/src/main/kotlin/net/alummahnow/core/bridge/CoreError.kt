package net.alummahnow.core.bridge

/** Errors every core call can raise. The UI maps these to localised messages, never raw text. */
sealed class CoreError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : CoreError("network", cause)

    /** [retryAfterMs]: when the server says to ask again (`Retry-After` on a 429 or 503). */
    class Http(val code: Int, val retryAfterMs: Long? = null) : CoreError("http $code")
    class NotFound(val what: String) : CoreError("not found: $what")
    class Decode(cause: Throwable) : CoreError("decode", cause)
    class NotSignedIn : CoreError("not signed in")

    /** The feature depends on a backend piece that is not live yet (e.g. native OAuth client, Epic 9.1). */
    class Unavailable(val reason: String) : CoreError("unavailable: $reason")
}
