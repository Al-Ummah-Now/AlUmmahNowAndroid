package net.alummahnow.core.bridge.links

import java.net.URI
import java.net.URISyntaxException

object ShortLinks {
    enum class Kind(val prefix: String) {
        VIDEO("v/"),
        SHORT("s/"),
        CHANNEL("c/"),
        HANDLE("@"),
        POST("p/"),
        PLAYLIST("l/")
    }

    data class Target(val kind: Kind, val id: String) {
        companion object {
            fun channel(id: String, handle: String?): Target =
                if (handle.isNullOrEmpty()) Target(Kind.CHANNEL, id) else Target(Kind.HANDLE, handle)
        }
    }

    /** Supply playlist context only when the playlist is public. */
    data class PublicPlaylist(val id: String, val index: Int? = null)

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val handlePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,28}[A-Za-z0-9]")
    private val previewLabel = Regex("pr-?\\d+")

    fun shortLinkOriginFor(siteHostOrOrigin: String): String? {
        val host = try {
            if ("://" in siteHostOrOrigin) URI(siteHostOrOrigin).host.orEmpty() else siteHostOrOrigin.substringBefore(':')
        } catch (_: URISyntaxException) {
            return null
        }.lowercase()
        if (host == "alummahnow.link" || host.endsWith(".alummahnow.link")) return "https://$host"
        if (host != "alummahnow.net" && !host.endsWith(".alummahnow.net")) return null

        val labels = host.split('.')
        return when {
            "qa" in labels -> "https://qa.alummahnow.link"
            "stage" in labels -> "https://stage.alummahnow.link"
            labels.any { it == "preview" || it == "dev" || previewLabel.matches(it) } -> null
            else -> "https://alummahnow.link"
        }
    }

    fun shareUrl(
        target: Target,
        siteHostOrOrigin: String,
        fallback: String,
        language: String,
        isPrivate: Boolean,
        startSeconds: Long? = null,
        playlist: PublicPlaylist? = null
    ): String? {
        if (isPrivate) return null
        val origin = shortLinkOriginFor(siteHostOrOrigin) ?: return fallback
        val id = validatedId(target) ?: return fallback
        val query = buildList {
            if (target.kind == Kind.VIDEO) {
                startSeconds?.takeIf { it > 0 }?.let { add("t=$it") }
                playlist?.takeIf { idPattern.matches(it.id) }?.let {
                    add("list=${it.id}")
                    it.index?.takeIf { index -> index >= 0 }?.let { index -> add("index=$index") }
                }
            }
            if (language == "ar") add("lang=ar")
        }
        val url = "$origin/${target.kind.prefix}$id"
        return if (query.isEmpty()) url else query.joinToString("&", prefix = "$url?")
    }

    private fun validatedId(target: Target): String? =
        if (target.kind == Kind.HANDLE) {
            target.id.removePrefix("@").takeIf(handlePattern::matches)
        } else {
            target.id.takeIf(idPattern::matches)
        }
}
