package net.alummahnow.core.bridge.links

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * An in-app destination for a link to the videos site (Android App Links) or a custom-scheme link.
 *
 * The site paths each app claims are fixed by the server's `assetlinks.json` table (NAT-REL contract):
 * viewer: `/app/auth/callback`, `/{en,ar}/video/…`, `/shorts/…`, `/live/…`, `/playlist/…`, `/post/…`, `/@…` (each also
 * without the locale prefix); Studio: `/app/studio/…` and `/{en,ar}/studio/…`.
 */
sealed interface AppLink {
    /** `en` or `ar` when the link carried a locale prefix, else null. */
    val lang: String?

    data class Video(val id: String, override val lang: String? = null) : AppLink
    data class Short(val id: String, override val lang: String? = null) : AppLink
    data class Live(val id: String, override val lang: String? = null) : AppLink
    data class Playlist(val id: String, override val lang: String? = null) : AppLink
    data class Post(val id: String, override val lang: String? = null) : AppLink

    /** A channel page, `/@handle` (the handle without `@`, percent-decoded: handles may be Arabic). */
    data class Channel(val handle: String, override val lang: String? = null) : AppLink

    /** `/app/auth/callback?…`: the sign-in redirect over https. [query] is the raw query string. */
    data class AuthCallback(val query: String?) : AppLink {
        override val lang: String? get() = null
    }

    /** A Studio page: optional channel, section and video (to edit), mirroring the web Studio's routes. */
    data class Studio(
        val channelId: String? = null,
        val section: StudioSection? = null,
        val videoId: String? = null,
        override val lang: String? = null
    ) : AppLink
}

enum class StudioSection { DASHBOARD, CONTENT, ANALYTICS }

/** Parses links into [AppLink]s. Pure JVM (no android.net.Uri), so it is unit-tested directly. */
object AppLinkParser {
    private val LOCALES = setOf("en", "ar")

    /**
     * Parses [link] if it is `https://[webHost]/…` (case-insensitive host) or one of the app's custom schemes
     * (`alummahnow://watch/{id}`). Anything else, including other hosts and plain http, is null.
     */
    fun parse(link: String, webHost: String): AppLink? {
        val uri = try {
            URI(link.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> if (uri.host.equals(webHost, ignoreCase = true)) parsePath(uri.rawPath.orEmpty(), uri.rawQuery) else null
            CUSTOM_SCHEME -> parseCustom(uri)
            else -> null
        }
    }

    private fun parseCustom(uri: URI): AppLink? {
        if (uri.host != "watch") return null
        val id = segments(uri.rawPath.orEmpty()).firstOrNull()?.takeIf(::isId) ?: return null
        return AppLink.Video(id)
    }

    private fun parsePath(rawPath: String, rawQuery: String?): AppLink? {
        val all = segments(rawPath)
        if (all.size >= 2 && all[0] == "app") {
            return when {
                all[1] == "auth" && all.getOrNull(2) == "callback" && all.size == 3 -> AppLink.AuthCallback(rawQuery)
                all[1] == "studio" -> studio(all.drop(2), lang = null)
                else -> null
            }
        }
        val lang = all.firstOrNull()?.takeIf { it in LOCALES }
        val rest = if (lang != null) all.drop(1) else all
        val head = rest.firstOrNull() ?: return null
        val id = rest.getOrNull(1)?.takeIf(::isId)
        return when {
            head.startsWith("@") -> head.substring(1).takeIf(::isHandle)?.let { AppLink.Channel(it, lang) }
            head == "studio" && lang != null -> studio(rest.drop(1), lang)
            id == null -> null
            head == "video" -> AppLink.Video(id, lang)
            head == "shorts" -> AppLink.Short(id, lang)
            head == "live" -> AppLink.Live(id, lang)
            head == "playlist" -> AppLink.Playlist(id, lang)
            head == "post" -> AppLink.Post(id, lang)
            else -> null
        }
    }

    /**
     * The web Studio's routes: `/studio`, `/studio/video/{id}/{action}`, `/studio/go/…`, `/studio/{channel}`,
     * `/studio/{channel}/{dashboard|analytics}`, `/studio/{channel}/content/videos[/{id}/edit]`.
     */
    private fun studio(s: List<String>, lang: String?): AppLink.Studio {
        val first = s.firstOrNull()?.takeIf(::isId) ?: return AppLink.Studio(lang = lang)
        return when (first) {
            "video" -> AppLink.Studio(videoId = s.getOrNull(1)?.takeIf(::isId), lang = lang)
            "go" -> AppLink.Studio(lang = lang)
            else -> when (s.getOrNull(1)) {
                null, "dashboard" -> AppLink.Studio(first, StudioSection.DASHBOARD, lang = lang)
                "analytics" -> AppLink.Studio(first, StudioSection.ANALYTICS, lang = lang)
                "content" -> {
                    val video = if (s.getOrNull(2) == "videos") s.getOrNull(3)?.takeIf(::isId) else null
                    AppLink.Studio(first, StudioSection.CONTENT, video, lang)
                }
                else -> AppLink.Studio(first, lang = lang)
            }
        }
    }

    /** Ids go into in-app routes: only URL-safe id characters, so a decoded `/`, `?` or `%` can't forge a route. */
    private fun isId(s: String): Boolean = ID.matches(s)

    /** Handles may be Arabic; they only ever leave the app as the original URL, but still no separators. */
    private fun isHandle(s: String): Boolean = s.length in 1..MAX_HANDLE && s.none { it in HANDLE_STOPS || it.isWhitespace() }

    private const val HANDLE_STOPS = "/?#"

    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private const val MAX_HANDLE = 100

    private fun segments(rawPath: String): List<String> =
        rawPath.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }

    const val CUSTOM_SCHEME = "alummahnow"
}
