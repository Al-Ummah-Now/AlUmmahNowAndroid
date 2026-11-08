package net.alummahnow.core.bridge

/** Errors every core call can raise. The UI maps these to localised messages, never raw text. */
sealed class CoreError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : CoreError("network", cause)
    class Http(val code: Int) : CoreError("http $code")
    class NotFound(val what: String) : CoreError("not found: $what")
    class Decode(cause: Throwable) : CoreError("decode", cause)
    class NotSignedIn : CoreError("not signed in")

    /** The feature depends on a backend piece that is not live yet (e.g. native OAuth client, Epic 9.1). */
    class Unavailable(val reason: String) : CoreError("unavailable: $reason")
}
