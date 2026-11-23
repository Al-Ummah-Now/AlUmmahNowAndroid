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
 * late resolution of play URLs. Android Auto controllers receive a shallow, strictly audio-only
 * 4-tab tree (Continue listening, Downloads, Subscriptions, Recitation).
 */
@UnstableApi
internal class LibraryCallback(
    private val context: Context,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
    private val downloadStore: OfflineDownloadStore? = null,
    private val resumeStore: PlaybackResumeStore? = null
) : MediaLibrarySession.Callback {

    private val tree =
        BrowseTree(catalog, context.getString(R.string.aun_browse_root)) { kind -> context.getString(BrowseTree.titleRes(kind)) }

    private val carTree = CarBrowseTree(
        catalog = catalog,
        downloadStore = downloadStore,
        rootTitle = context.getString(R.string.aun_browse_root),
        tabTitle = { tab -> context.getString(CarBrowseTree.titleRes(tab)) }
    )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val root = if (isAndroidAutoController(browser.packageName)) carTree.root() else tree.root()
        LibraryResult.ofItem(root, params)
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
        runCatching { resolvePlayable(mediaId, isAndroidAutoController(browser.packageName)) }
            .fold({ LibraryResult.ofItem(it, null) }, { LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) })
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val trusted = acceptsPrebuiltItems(controller.packageName, context.packageName)
        val audioOnly = isAndroidAutoController(controller.packageName)
        mediaItems.map { item ->
            if (trusted && item.localConfiguration != null) {
                item
            } else {
                resolvePlayable(item.mediaId, audioOnly)
            }
        }.toMutableList()
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val point = resumeStore?.load()
        if (point != null) {
            val resolved = runCatching { resolvePlayable(point.videoId, audioOnly = isAndroidAutoController(controller.packageName)) }
                .getOrNull()
            if (resolved != null) {
                return@future MediaSession.MediaItemsWithStartPosition(listOf(resolved), 0, point.positionMs)
            }
        }
        val fallback = runCatching {
            catalog.homeRails().firstOrNull()?.videos?.firstOrNull()?.let {
                resolvePlayable(it.id, audioOnly = isAndroidAutoController(controller.packageName))
            }
        }.getOrNull()
        val list = listOfNotNull(fallback)
        MediaSession.MediaItemsWithStartPosition(list, 0, 0L)
    }

    private suspend fun resolvePlayable(mediaId: String, audioOnly: Boolean): MediaItem {
        val video = catalog.video(mediaId)
        return if (audioOnly) MediaItems.playableAudio(video, locale = locale()) else MediaItems.playable(video, locale())
    }

    private fun locale(): Locale = context.resources.configuration.locales[0]
}

/**
 * The session service is exported (Android Auto, system media controls), so a foreign controller could
 * otherwise hand us an item pointing at any URI. Only our own process may pass a ready-made item; every
 * other caller gets the item rebuilt from the catalog by `mediaId`.
 */
internal fun acceptsPrebuiltItems(callerPackage: String, ownPackage: String): Boolean = callerPackage == ownPackage

/** True for Android Auto and Android Automotive OS media hosts (strictly audio-only templates). */
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

/** Pure browse-tree logic, unit-tested without a session. */
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
 * 4 top-level tabs — Continue listening, Downloads, Subscriptions, Recitation — plus voice search
 * that merges online catalog results with locally downloaded items for offline car playback.
 */
internal class CarBrowseTree(
    private val catalog: CatalogRepository,
    private val downloadStore: OfflineDownloadStore?,
    private val rootTitle: String,
    private val tabTitle: (CarTab) -> String
) {
    fun root(): MediaItem = MediaItems.folder(ROOT_ID, rootTitle)

    suspend fun children(parentId: String, locale: Locale): List<MediaItem> {
        if (parentId == ROOT_ID) {
            val tabs = CarTab.entries.filter { tab ->
                tab != CarTab.DOWNLOADS || (downloadStore?.offlineDownloadsEnabled == true)
            }
            return tabs.map { MediaItems.folder(it.id, tabTitle(it)) }
        }
        val tab = CarTab.entries.firstOrNull { it.id == parentId } ?: return emptyList()
        return when (tab) {
            CarTab.DOWNLOADS ->
                downloadStore?.playableDownloads().orEmpty().map { MediaItems.browsableDownload(it, locale) }

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
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val offlineMatches = downloadStore?.playableDownloads().orEmpty()
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
