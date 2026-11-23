package net.alummahnow.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore

/**
 * Browse tree (Android Auto, other media controllers), voice search, playback resumption, and
 * late resolution of play URLs.
 *
 * Android Auto surfaces (root, tabs, voice search, resumption) stay completely hidden while
 * `listenMode` is off (`GET /api/me/features`). When on, Downloads play strictly offline via
 * `aun-offline://` and online items use the audio-only rendition from `GET /api/v1/videos/:id/audio`.
 */
@UnstableApi
internal class LibraryCallback(
    private val context: Context,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
    private val downloadStore: OfflineDownloadStore? = null,
    private val resumeStore: PlaybackResumeStore? = null,
    private val listenModeEnabled: () -> Boolean = { false },
    private val activeUserId: () -> String = { "default" }
) : MediaLibrarySession.Callback {

    private val tree =
        BrowseTree(catalog, context.getString(R.string.aun_browse_root)) { kind -> context.getString(BrowseTree.titleRes(kind)) }

    private val carTree = CarBrowseTree(
        catalog = catalog,
        downloadStore = downloadStore,
        listenModeEnabled = listenModeEnabled,
        activeUserId = activeUserId,
        rootTitle = context.getString(R.string.aun_browse_root),
        tabTitle = { tab -> context.getString(CarBrowseTree.titleRes(tab)) }
    )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        if (isAndroidAutoController(browser.packageName)) {
            if (!listenModeEnabled()) {
                return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
            }
            return@future LibraryResult.ofItem(carTree.root(), params)
        }
        LibraryResult.ofItem(tree.root(), params)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        runCatching {
            val isCar = isAndroidAutoController(browser.packageName) || parentId.startsWith(CarBrowseTree.PREFIX)
            val items = if (isCar) carTree.children(parentId, locale()) else tree.children(parentId, locale())
            paginate(items, page, pageSize)
        }.fold(
            onSuccess = { LibraryResult.ofItemList(ImmutableList.copyOf(it), params) },
            onFailure = {
                AunLog.w("player", "browse failed", "parent" to parentId, "error" to it.message)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        )
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        if (!listenModeEnabled()) {
            session.notifySearchResultChanged(browser, query, 0, params)
            return@future LibraryResult.ofVoid(params)
        }
        val results = runCatching { carTree.search(query, locale()) }.getOrDefault(emptyList())
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        if (!listenModeEnabled()) {
            return@future LibraryResult.ofItemList(ImmutableList.of(), params)
        }
        runCatching { paginate(carTree.search(query, locale()), page, pageSize) }.fold(
            onSuccess = { LibraryResult.ofItemList(ImmutableList.copyOf(it), params) },
            onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) }
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val isCar = isAndroidAutoController(browser.packageName)
        if (isCar && !listenModeEnabled()) {
            return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
        }
        runCatching { carTree.resolvePlayable(mediaId, audioOnly = isCar, locale = locale()) }
            .fold({ LibraryResult.ofItem(it, null) }, { LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) })
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val trusted = acceptsPrebuiltItems(controller.packageName, context.packageName)
        val isCar = isAndroidAutoController(controller.packageName)
        if (isCar && !listenModeEnabled()) {
            return@future mutableListOf()
        }
        mediaItems.map { item ->
            if (trusted && item.localConfiguration != null) {
                item
            } else {
                carTree.resolvePlayable(item.mediaId, audioOnly = isCar, locale = locale())
            }
        }.toMutableList()
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val isCar = isAndroidAutoController(controller.packageName)
        if (isCar && !listenModeEnabled()) {
            return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        val point = resumeStore?.load()
            ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        val resolved = runCatching { carTree.resolvePlayable(point.videoId, audioOnly = isCar, locale = locale()) }
            .getOrNull()
            ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        MediaSession.MediaItemsWithStartPosition(listOf(resolved), 0, point.positionMs)
    }

    private fun locale(): Locale = context.resources.configuration.locales[0]
}

internal fun acceptsPrebuiltItems(callerPackage: String, ownPackage: String): Boolean = callerPackage == ownPackage

internal fun isAndroidAutoController(callerPackage: String): Boolean =
    callerPackage == "com.google.android.projection.gearhead" ||
        callerPackage == "com.android.car.media" ||
        callerPackage == "com.google.android.apps.automotive.templates.host"

internal fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return items
    val from = page * pageSize
    if (from >= items.size) return emptyList()
    return items.subList(from, (from + pageSize).coerceAtMost(items.size))
}

internal class BrowseTree(
    private val catalog: CatalogRepository,
    private val rootTitle: String,
    private val title: (RailKind) -> String
) {
    fun root(): MediaItem = MediaItems.folder(ROOT_ID, rootTitle)

    suspend fun children(parentId: String, locale: Locale): List<MediaItem> {
        val rails = catalog.homeRails()
        if (parentId == ROOT_ID) return rails.map { MediaItems.folder(FOLDER_PREFIX + it.kind.name, title(it.kind)) }
        val kind =
            parentId.removePrefix(FOLDER_PREFIX).let { name -> RailKind.entries.firstOrNull { it.name == name } } ?: return emptyList()
        return rails.firstOrNull { it.kind == kind }?.videos.orEmpty().map { MediaItems.browsableVideo(it, locale) }
    }

    companion object {
        const val ROOT_ID = "aun:root"
        const val FOLDER_PREFIX = "aun:rail:"

        fun titleRes(kind: RailKind): Int = when (kind) {
            RailKind.CONTINUE_WATCHING -> net.alummahnow.design.R.string.aun_rail_continue
            RailKind.TRENDING -> net.alummahnow.design.R.string.aun_rail_trending
            RailKind.RECOMMENDED -> net.alummahnow.design.R.string.aun_rail_recommended
            RailKind.POPULAR -> net.alummahnow.design.R.string.aun_rail_popular
            RailKind.RECENT -> net.alummahnow.design.R.string.aun_rail_recent
            RailKind.CATEGORY -> net.alummahnow.design.R.string.aun_rail_category
        }
    }
}

internal enum class CarTab(val id: String) {
    CONTINUE_LISTENING("aun:car:continue"),
    DOWNLOADS("aun:car:downloads"),
    SUBSCRIPTIONS("aun:car:subscriptions"),
    RECITATION("aun:car:recitation")
}

/**
 * Shallow audio-only browse tree for Android Auto (E14-S8):
 * Completely hidden while [listenModeEnabled] is false. When enabled, exposes 4 top-level tabs
 * (Continue listening, Downloads when `offlineDownloads` is on, Subscriptions, Recitation), plays
 * offline copies directly without network calls, and plays online items via our audio rendition.
 */
@UnstableApi
internal class CarBrowseTree(
    private val catalog: CatalogRepository,
    private val downloadStore: OfflineDownloadStore?,
    private val listenModeEnabled: () -> Boolean = { false },
    private val activeUserId: () -> String = { "default" },
    private val rootTitle: String,
    private val tabTitle: (CarTab) -> String
) {
    fun root(): MediaItem = MediaItems.folder(ROOT_ID, rootTitle)

    suspend fun children(parentId: String, locale: Locale): List<MediaItem> {
        if (!listenModeEnabled()) return emptyList()
        if (parentId == ROOT_ID) {
            val tabs = CarTab.entries.filter { tab ->
                tab != CarTab.DOWNLOADS || (downloadStore?.offlineDownloadsEnabled == true)
            }
            return tabs.map { MediaItems.folder(it.id, tabTitle(it)) }
        }
        val tab = CarTab.entries.firstOrNull { it.id == parentId } ?: return emptyList()
        return when (tab) {
            CarTab.DOWNLOADS ->
                downloadStore?.playableDownloads(activeUserId()).orEmpty().map { MediaItems.browsableDownload(it, locale) }

            CarTab.CONTINUE_LISTENING -> {
                val rails = runCatching { catalog.homeRails() }.getOrDefault(emptyList())
                val videos = rails.firstOrNull { it.kind == RailKind.CONTINUE_WATCHING }?.videos
                    ?: rails.firstOrNull()?.videos.orEmpty()
                videos.map { MediaItems.browsableVideo(it, locale) }
            }

            CarTab.SUBSCRIPTIONS -> {
                val rails = runCatching { catalog.homeRails() }.getOrDefault(emptyList())
                val videos = rails.firstOrNull { it.kind == RailKind.RECENT }?.videos
                    ?: rails.firstOrNull { it.kind == RailKind.RECOMMENDED }?.videos.orEmpty()
                videos.map { MediaItems.browsableVideo(it, locale) }
            }

            CarTab.RECITATION -> {
                val rails = runCatching { catalog.homeRails() }.getOrDefault(emptyList())
                val all = rails.flatMap { it.videos }
                val recitations = all.filter(::isRecitationCandidate).ifEmpty {
                    rails.firstOrNull { it.kind == RailKind.CATEGORY }?.videos
                        ?: rails.firstOrNull { it.kind == RailKind.TRENDING }?.videos.orEmpty()
                }
                recitations.distinctBy { it.id }.map { MediaItems.browsableVideo(it, locale) }
            }
        }
    }

    suspend fun search(query: String, locale: Locale): List<MediaItem> {
        if (!listenModeEnabled()) return emptyList()
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val offlineMatches = downloadStore?.playableDownloads(activeUserId()).orEmpty()
            .filter {
                it.title.contains(needle, ignoreCase = true) ||
                    (it.titleAr?.contains(needle, ignoreCase = true) == true) ||
                    (it.channelName?.contains(needle, ignoreCase = true) == true)
            }
            .map { MediaItems.browsableDownload(it, locale) }
        val onlineMatches = runCatching { catalog.search(needle, limit = 15) }
            .getOrDefault(emptyList())
            .map { MediaItems.browsableVideo(it, locale) }
        return (offlineMatches + onlineMatches).distinctBy { it.mediaId }
    }

    /**
     * Resolves a mediaId to a playable [MediaItem].
     * Always prefers a valid local encrypted offline download (zero network calls) so the Car
     * Downloads tab works offline; otherwise resolves the audio-only rendition when [audioOnly].
     */
    suspend fun resolvePlayable(mediaId: String, audioOnly: Boolean, locale: Locale): MediaItem {
        val offline = downloadStore?.findPlayableByVideoId(activeUserId(), mediaId)
        if (offline != null) {
            return MediaItems.playableOffline(offline, locale)
        }
        val video = catalog.video(mediaId)
        if (audioOnly) {
            val audioUrl = runCatching { catalog.audioUrl(mediaId) }.getOrNull()
            return MediaItems.playableAudio(video, audioUrl = audioUrl, locale = locale)
        }
        return MediaItems.playable(video, locale)
    }

    private fun isRecitationCandidate(video: VideoSummary): Boolean {
        val cat = video.category?.lowercase(Locale.ROOT).orEmpty()
        if (cat.contains("quran") || cat.contains("recitation") || cat.contains("تلاوة")) return true
        val t = video.title.fallback.lowercase(Locale.ROOT)
        return t.contains("quran") || t.contains("surah") || t.contains("recitation") || t.contains("قرآن") || t.contains("سورة")
    }

    companion object {
        const val PREFIX = "aun:car:"
        const val ROOT_ID = "aun:car:root"

        fun titleRes(tab: CarTab): Int = when (tab) {
            CarTab.CONTINUE_LISTENING -> R.string.aun_car_continue
            CarTab.DOWNLOADS -> R.string.aun_car_downloads
            CarTab.SUBSCRIPTIONS -> R.string.aun_car_subscriptions
            CarTab.RECITATION -> R.string.aun_car_recitation
        }
    }
}
