package ani.sanin.media.anime

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import androidx.annotation.OptIn
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.Rpdb
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbExtras
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ItemTmdbServerRowBinding
import ani.sanin.databinding.ItemTmdbStreamsExtraBinding
import ani.sanin.getThemeColor
import ani.sanin.limitedAsyncMap
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.media.anime.getEpisode
import ani.sanin.media.anime.getEpisodeKey
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.HAnimeSources
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import ani.sanin.parsers.WatchSources
import ani.sanin.settings.StremioAddon
import ani.sanin.settings.StremioAddonManager
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

// ---------------------------------------------------------------------
// Shared state models (process-lifetime, safe to hold in static caches)
// ---------------------------------------------------------------------

private sealed class AnimeGroupState {
    object Loading : AnimeGroupState()
    data class Failed(val reason: String) : AnimeGroupState()
    data class Ready(val servers: List<VideoServer>) : AnimeGroupState()
}

private sealed class TmdbGroupState {
    object Loading : TmdbGroupState()
    data class Failed(val reason: String) : TmdbGroupState()
    data class Ready(
        val links: List<TmdbStreamResolver.StreamResult.PlayableLink>,
        val recommendations: List<TmdbMedia> = emptyList(),
        val infos: List<TmdbStreamResolver.StreamResult.PlayableLink> = emptyList(),
    ) : TmdbGroupState()
}

internal class AnimeEntry(
    val servers: List<VideoServer>,
    val extractors: List<VideoExtractor>,
    val failedReason: String?,
    val extractionDone: Boolean,
    val episode: Episode?,
)

private class TmdbEntry(val state: TmdbGroupState, val done: Boolean)

internal object AnimeRailCache {
    fun key(mediaId: Int, epKey: String?, sourceIndex: Int) =
        RailRepo.animeKey(mediaId, epKey, sourceIndex)

    fun seed(key: String, entry: AnimeEntry) = RailRepo.seedAnime(key, entry)

    fun fail(key: String, reason: String) = RailRepo.failAnime(key, reason)
}

// ---------------------------------------------------------------------
// RailRepo: process-level resolution + cache.
// Jobs are NOT tied to any activity, so they survive player restarts
// (restartWithServer) and results are cached so reopening the rail
// never re-resolves a source that already succeeded.
// ---------------------------------------------------------------------

private object RailRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val animeCache = HashMap<String, AnimeEntry>()
    private val animeWaiters = HashMap<String, MutableList<(AnimeEntry) -> Unit>>()
    private val animeJobs = HashMap<String, Job>()
    private val animeJobTokens = HashMap<String, Any>()

    private val tmdbCache = HashMap<String, TmdbEntry>()
    private val tmdbWaiters = HashMap<String, MutableList<(TmdbEntry) -> Unit>>()
    private val tmdbJobs = HashMap<String, Job>()

    fun animeKey(mediaId: Int, epKey: String?, sourceIndex: Int) =
        "A:$mediaId|$epKey|$sourceIndex"

    fun tmdbKey(mediaId: Int, season: Int?, ep: Int?, groupKey: String) =
        "T:$mediaId|$season|$ep|$groupKey"

    /** Drop cached entries belonging to a previous episode (episode change). */
    fun prune(mediaId: Int, epKey: String?, season: Int?, ep: Int?) {
        synchronized(lock) {
            animeCache.keys.removeAll {
                it.startsWith("A:$mediaId|") && !it.startsWith("A:$mediaId|$epKey|")
            }
            tmdbCache.keys.removeAll {
                it.startsWith("T:$mediaId|") && !it.startsWith("T:$mediaId|$season|$ep|")
            }
        }
    }

    /** Refresh-all: drop cache + cancel running jobs for the current episode. */
    fun refreshAnime(mediaId: Int, epKey: String?) {
        synchronized(lock) {
            val prefix = "A:$mediaId|$epKey|"
            animeCache.keys.removeAll { it.startsWith(prefix) }
            animeJobs.keys.filter { it.startsWith(prefix) }.forEach {
                animeJobs.remove(it)?.cancel()
                animeJobTokens.remove(it)
                animeWaiters.remove(it)
            }
        }
    }

    fun refreshTmdb(mediaId: Int, season: Int?, ep: Int?) {
        synchronized(lock) {
            val prefix = "T:$mediaId|$season|$ep|"
            tmdbCache.keys.removeAll { it.startsWith(prefix) }
            tmdbJobs.keys.filter { it.startsWith(prefix) }.forEach {
                tmdbJobs.remove(it)?.cancel()
                tmdbWaiters.remove(it)
            }
        }
    }

    fun watchAnime(key: String, onUpdate: (AnimeEntry) -> Unit): AnimeEntry? {
        synchronized(lock) {
            val cached = animeCache[key] ?: return null
            if (!cached.extractionDone) {
                animeWaiters.getOrPut(key) { mutableListOf() }.add(onUpdate)
            }
            return cached
        }
    }

    fun seedAnime(key: String, entry: AnimeEntry) {
        if (entry.failedReason != null) return
        var waiters: List<(AnimeEntry) -> Unit> = emptyList()
        synchronized(lock) {
            animeJobTokens.remove(key)
            animeJobs.remove(key)?.cancel()
            animeCache[key] = entry
            waiters = if (entry.extractionDone) {
                animeWaiters.remove(key) ?: emptyList()
            } else {
                animeWaiters[key]?.toList() ?: emptyList()
            }
        }
        waiters.forEach { it(entry) }
    }

    fun failAnime(key: String, reason: String) {
        var waiters: List<(AnimeEntry) -> Unit> = emptyList()
        synchronized(lock) {
            animeCache.remove(key)
            animeJobTokens.remove(key)
            animeJobs.remove(key)?.cancel()
            waiters = animeWaiters.remove(key) ?: emptyList()
        }
        val entry = AnimeEntry(emptyList(), emptyList(), reason, true, null)
        waiters.forEach { it(entry) }
    }

    fun startAnime(
        key: String,
        media: Media,
        epKey: String?,
        sourceIndex: Int,
        currentSourceIndex: Int,
        parser: AnimeParser,
        sources: WatchSources,
        onUpdate: (AnimeEntry) -> Unit,
    ) {
        synchronized(lock) {
            animeCache[key]?.takeIf { it.extractionDone }?.let {
                onUpdate(it)
                return
            }
            animeWaiters.getOrPut(key) { mutableListOf() }.add(onUpdate)
            if (animeJobs.containsKey(key)) return
            val token = Any()
            animeJobTokens[key] = token
            animeJobs[key] = scope.launch {
                val entry = resolveAnime(
                    key, media, epKey, sourceIndex, currentSourceIndex, parser, sources,
                )
                synchronized(lock) {
                    if (animeJobTokens[key] !== token) return@synchronized
                    animeJobTokens.remove(key)
                    animeJobs.remove(key)
                    if (entry.failedReason == null) animeCache[key] = entry
                    val waiters = animeWaiters.remove(key) ?: emptyList()
                    waiters.forEach { it(entry) }
                }
            }
        }
    }

    private suspend fun resolveAnime(
        key: String,
        media: Media,
        epKey: String?,
        sourceIndex: Int,
        currentSourceIndex: Int,
        parser: AnimeParser,
        sources: WatchSources,
    ): AnimeEntry {
        fun notify(entry: AnimeEntry) {
            val waiters = synchronized(lock) { animeWaiters[key]?.toList() ?: emptyList() }
            waiters.forEach { it(entry) }
        }
        var target: Episode? = null
        var servers: List<VideoServer> = emptyList()
        val extractors = mutableListOf<VideoExtractor>()
        var failed: String? = null
        try {
            val ep = media.anime?.episodes?.getEpisode(epKey)
            val t: Episode = if (
                sourceIndex == currentSourceIndex &&
                ep?.link != null && ep.sEpisode != null
            ) {
                ep
            } else {
                val map = withTimeoutOrNull(45_000) {
                    sources.loadEpisodesFromMedia(sourceIndex, media)
                }.orEmpty()
                val num = ep?.number?.toFloatOrNull()
                map.entries.firstOrNull { it.key.toFloatOrNull() == num }?.value
                    ?: map.entries.firstOrNull { it.key.equals(epKey, true) }?.value
                    ?: throw Exception("Episode not found in ${parser.name}")
            }
            target = t
            val preferDub = media.selected?.preferDub ?: PrefManager.getVal(PrefName.PreferDub)
            if (sourceIndex != currentSourceIndex && parser.isDubAvailableSeparately()) {
                parser.selectDub = preferDub
            }
            servers = withTimeoutOrNull(60_000) {
                t.link?.let { link ->
                    t.sEpisode?.let { sEp ->
                        parser.loadVideoServers(link, t.extra, sEp)
                    }
                }
            }.orEmpty()
            notify(AnimeEntry(servers, emptyList(), null, false, t))
            Logger.log("SERVER_RAIL: '${parser.name}' ok ${servers.size} servers")
            if (servers.isNotEmpty()) {
                val link = t.link
                val sEp = t.sEpisode
                if (link != null && sEp != null) {
                    runCatching {
                        parser.loadByVideoServers(link, t.extra, sEp, { extractor ->
                            extractors.add(extractor)
                            notify(AnimeEntry(servers, extractors.toList(), null, false, t))
                        }, servers)
                    }.onFailure {
                        Logger.log(
                            android.util.Log.ERROR,
                            "SERVER_RAIL: '${parser.name}' extractor load failed: ${it.message}",
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(android.util.Log.ERROR, "SERVER_RAIL: '${parser.name}' failed: ${e.message}")
            failed = e.message ?: "Failed"
        }
        return AnimeEntry(servers, extractors.toList(), failed, true, target)
    }

    fun getTmdb(key: String): TmdbEntry? = synchronized(lock) { tmdbCache[key] }

    fun startTmdb(
        key: String,
        context: android.content.Context,
        session: TmdbStreamResolver.SyntheticSession,
        season: Int?,
        ep: Int?,
        source: CsInstalledSource,
        addon: StremioAddon?,
        onUpdate: (TmdbEntry) -> Unit,
    ) {
        synchronized(lock) {
            tmdbCache[key]?.let {
                onUpdate(it)
                return
            }
            tmdbWaiters.getOrPut(key) { mutableListOf() }.add(onUpdate)
            if (tmdbJobs.containsKey(key)) return
            tmdbJobs[key] = scope.launch {
                val entry = resolveTmdb(context, session, season, ep, source, addon)
                synchronized(lock) {
                    tmdbJobs.remove(key)
                    tmdbCache[key] = entry
                    val waiters = tmdbWaiters.remove(key) ?: emptyList()
                    waiters.forEach { it(entry) }
                }
            }
        }
    }

    private suspend fun resolveTmdb(
        context: android.content.Context,
        session: TmdbStreamResolver.SyntheticSession,
        season: Int?,
        ep: Int?,
        source: CsInstalledSource,
        addon: StremioAddon?,
    ): TmdbEntry {
        val mediaType = session.mediaType
        val mediaId = session.mediaId
        return when {
            addon != null -> {
                val result = TmdbStreamResolver.resolveStremio(
                    addon, mediaType, mediaId, session.detail, season, ep,
                )
                when (result) {
                    is TmdbStreamResolver.StreamResult.Success -> {
                        val external = result.links.filter { it.url.isBlank() }
                        if (external.isEmpty()) {
                            TmdbStreamResolver.cacheLinks(mediaId, source.name, season, ep, result)
                            TmdbEntry(TmdbGroupState.Ready(result.links), true)
                        } else {
                            val canCard = { link: TmdbStreamResolver.StreamResult.PlayableLink ->
                                TmdbExtras.parseStremioTarget(link.externalUrl) != null ||
                                    TmdbExtras.parseExtraDescription(
                                        link.rawDetails ?: link.details,
                                    )?.isTemplateMatch == true
                            }
                            val infos = external.filter { !canCard(it) }
                            val extras = external - infos.toSet()
                            val recs = extras.limitedAsyncMap(4) { link ->
                                runCatching { TmdbExtras.resolveExtraMedia(link, mediaType) }.getOrNull()
                            }.filterNotNull()
                                .filterNot { it.id == mediaId && it.type == mediaType }
                                .distinctBy { "${it.type}:${it.id}" }
                            TmdbStreamResolver.cacheLinks(mediaId, source.name, season, ep, result)
                            TmdbEntry(TmdbGroupState.Ready(result.links, recs, infos), true)
                        }
                    }
                    is TmdbStreamResolver.StreamResult.Error ->
                        TmdbEntry(TmdbGroupState.Failed(result.message), true)
                }
            }
            else -> {
                val cached = TmdbStreamResolver.cachedLinks(mediaId, source.name, season, ep)
                if (cached != null) {
                    Logger.log("SERVER_RAIL: '${source.name}' cached ${cached.links.size} links")
                    TmdbEntry(TmdbGroupState.Ready(cached.links), true)
                } else {
                    val result = TmdbStreamResolver.resolveStreams(context, source, session.detail, season, ep)
                    when (result) {
                        is TmdbStreamResolver.StreamResult.Success -> {
                            TmdbStreamResolver.cacheLinks(mediaId, source.name, season, ep, result)
                            TmdbEntry(TmdbGroupState.Ready(result.links), true)
                        }
                        is TmdbStreamResolver.StreamResult.Error ->
                            TmdbEntry(TmdbGroupState.Failed(result.message), true)
                    }
                }
            }
        }
    }
}

/**
 * In-player Sources & Tracks rail: a mini version of the servers screen.
 * Sources tab — anime mode: source chips + per-source servers (grey containers, top rim,
 * grouped cards); TMDB mode: source chips (CS3 + Stremio) + stream/info/extras cards,
 * like the TMDB servers screen. Resolution runs process-wide (RailRepo) and is cached
 * across player restarts.
 * Tracks tab — replaces the old tracks dialog: bold white Video/Audio headers with the
 * player's video and audio tracks underneath, same container + selected-indicator look.
 */
@OptIn(UnstableApi::class)
class ServerRailController(
    private val activity: ExoplayerView,
    private val model: MediaDetailsViewModel,
    private val content: View,
    private val closeButton: ImageButton,
    private val tabs: TabLayout,
    private val strip: View,
    private val refreshAllView: View,
    private val makeDefaultCheck: android.widget.CheckBox,
    private val chipsScroll: HorizontalScrollView,
    private val chipsGroup: ChipGroup,
    private val recycler: RecyclerView,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit,
) {

    private val media: Media get() = ExoplayerView.media
    private var launching = false

    private sealed class Row {
        data class Header(val name: String, val tag: String?) : Row()
        data class TrackHeader(val name: String) : Row()
        data class Track(val entry: TrackEntry, val active: Boolean) : Row()
        data class Video(
            val group: Group,
            val extractor: VideoExtractor,
            val idx: Int,
            val isFirst: Boolean,
            val isLast: Boolean,
            val server: VideoServer?,
        ) : Row()

        data class Stream(
            val link: TmdbStreamResolver.StreamResult.PlayableLink,
            val group: TmdbGroup,
            val selected: Boolean,
        ) : Row()

        data class Info(
            val link: TmdbStreamResolver.StreamResult.PlayableLink,
            val addon: StremioAddon?,
        ) : Row()

        data class Extra(val item: TmdbMedia, val addon: StremioAddon?) : Row()
    }

    private class Group(
        val index: Int,
        val name: String,
        val parser: AnimeParser,
    ) {
        var state: AnimeGroupState = AnimeGroupState.Loading
        var rank = 2
        var arrivalSeq = Int.MAX_VALUE
        var episode: Episode? = null
        val extractors = mutableListOf<VideoExtractor>()
        var extractionDone = false
    }

    private class TmdbGroup(
        val key: String,
        val name: String,
        val iconUrl: String?,
        val source: CsInstalledSource,
        val addon: StremioAddon? = null,
    ) {
        var state: TmdbGroupState = TmdbGroupState.Loading
        var rank = 2
        var arrivalSeq = Int.MAX_VALUE
    }

    enum class Tab { SOURCES, TRACKS }

    private class TrackEntry(
        val group: Tracks.Group,
        val index: Int,
        val type: @C.TrackType Int,
        val label: String,
        val active: Boolean,
        val height: Int = 0,
    )

    private val rows = mutableListOf<Row>()
    private val groups = mutableListOf<Group>()
    private val tmdbGroups = mutableListOf<TmdbGroup>()
    private val chipByIndex = mutableListOf<Chip>()
    private var selectedIndex = 0
    private var tmdbSelectedIndex = 0
    private var currentTab: Tab = Tab.SOURCES
    private var targetEpisodeKey: String? = null
    private val adapter = RailAdapter()
    private var arrivalCounter = 0
    private val sources: WatchSources =
        if (ExoplayerView.media.isAdult) HAnimeSources else AnimeSources
    private val tmdbSession: TmdbStreamResolver.SyntheticSession? by lazy {
        TmdbStreamResolver.sessionFor(media.id)
    }

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        closeButton.nextFocusDownId = R.id.railTabs
        recycler.nextFocusUpId = R.id.railTabs
        FocusEffectUtil.applyFocusListener(closeButton)
        closeButton.setOnClickListener { close() }
        FocusEffectUtil.applyFocusListener(refreshAllView)
        refreshAllView.setOnClickListener { refreshAll() }
        refreshAllView.nextFocusUpId = tabs.id
        refreshAllView.nextFocusDownId = R.id.serverRailList
        refreshAllView.nextFocusLeftId = refreshAllView.id
        refreshAllView.nextFocusRightId = makeDefaultCheck.id
        FocusEffectUtil.applyFocusListener(makeDefaultCheck)
        makeDefaultCheck.nextFocusUpId = tabs.id
        makeDefaultCheck.nextFocusDownId = R.id.serverRailList
        makeDefaultCheck.nextFocusLeftId = refreshAllView.id
        makeDefaultCheck.nextFocusRightId = makeDefaultCheck.id
        makeDefaultCheck.isChecked = PrefManager.getVal<Boolean>(PrefName.MakeDefault)
        makeDefaultCheck.setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.MakeDefault, checked)
        }
        tabs.isFocusable = false
        tabs.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        tabs.addTab(tabs.newTab().setText(R.string.sources))
        tabs.addTab(tabs.newTab().setText(R.string.tracks))
        configureTabViews()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(if (tab.position == 1) Tab.TRACKS else Tab.SOURCES)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        applyTabVisibility()
    }

    fun openTracksTab() {
        show(Tab.TRACKS)
    }

    private fun show(tab: Tab) {
        if (currentTab != tab) {
            currentTab = tab
            tabs.getTabAt(if (tab == Tab.TRACKS) 1 else 0)?.select()
            applyTabVisibility()
        }
        if (currentTab == Tab.TRACKS) rebuildTracks() else rebuild()
        if (isOpen()) {
            focusFirst()
            return
        }
        onOpened()
        content.visibility = View.VISIBLE
        content.post {
            content.translationX = -content.width.toFloat()
            content.animate().translationX(0f).setDuration(220)
                .withEndAction { focusFirst() }
                .start()
        }
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        applyTabVisibility()
        if (tab == Tab.TRACKS) rebuildTracks() else rebuild()
    }

    private fun configureTabViews() {
        val views = (0 until tabs.tabCount).mapNotNull { tabs.getTabAt(it)?.view }
        views.forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            view.isFocusable = true
            view.isFocusableInTouchMode = false
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        views.forEachIndexed { i, view ->
            view.nextFocusUpId = closeButton.id
            view.nextFocusDownId = if (i == 0) refreshAllView.id else recycler.id
            view.nextFocusLeftId = views.getOrNull(i - 1)?.id ?: view.id
            view.nextFocusRightId = views.getOrNull(i + 1)?.id ?: closeButton.id
            view.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                val target = if (i == 1) Tab.TRACKS else Tab.SOURCES
                if (currentTab != target) tabs.getTabAt(i)?.select()
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        closeButton.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (i == views.lastIndex) {
                            closeButton.requestFocus()
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> i == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (i == views.lastIndex) {
                            focusFirst()
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
    }

    private fun applyTabVisibility() {
        val sources = currentTab == Tab.SOURCES
        strip.isVisible = sources
        chipsScroll.isVisible = sources
        val selectedTabView = tabs.getTabAt(if (sources) 0 else 1)?.view
        val selectedTabId = selectedTabView?.id ?: closeButton.id
        refreshAllView.nextFocusUpId = selectedTabId
        makeDefaultCheck.nextFocusUpId = selectedTabId
        closeButton.nextFocusDownId = selectedTabId
        closeButton.nextFocusLeftId = selectedTabId
        closeButton.nextFocusRightId = closeButton.id
        closeButton.nextFocusUpId = closeButton.id
        recycler.nextFocusUpId = selectedTabId
        val downId = if (sources) refreshAllView.id else recycler.id
        tabs.getTabAt(0)?.view?.nextFocusDownId = downId
        tabs.getTabAt(1)?.view?.nextFocusDownId = downId
    }

    /** Rebuilds the list for the Tracks tab: bold white Video header, then video tracks,
     *  then Audio header + audio tracks — replacing the old tracks dialog sheet. */
    private fun rebuildTracks() {
        val focusPosition = focusedAdapterPosition()
        val focusKey = rows.getOrNull(focusPosition)?.let { rowKey(it) }
        rows.clear()
        val tracks = activity.playerCurrentTracks()
        val videos = trackEntries(tracks, C.TRACK_TYPE_VIDEO)
            .sortedByDescending { it.height }
        if (videos.isNotEmpty()) {
            rows.add(Row.TrackHeader("Video"))
            videos.forEach { rows.add(Row.Track(it, it.active)) }
        }
        val audios = trackEntries(tracks, C.TRACK_TYPE_AUDIO)
        if (audios.isNotEmpty()) {
            rows.add(Row.TrackHeader("Audio"))
            audios.forEach { rows.add(Row.Track(it, it.active)) }
        }
        if (rows.isEmpty()) rows.add(Row.TrackHeader("No selectable tracks"))
        adapter.notifyDataSetChanged()
        restoreRowFocus(focusPosition, focusKey)
    }

    /** Called from onTracksChanged so an open Tracks tab always reflects current tracks. */
    fun notifyTracksChanged() {
        if (isOpen() && currentTab == Tab.TRACKS) rebuildTracks()
    }

    private fun trackEntries(tracks: Tracks, type: @C.TrackType Int): List<TrackEntry> {
        val result = mutableListOf<TrackEntry>()
        tracks.groups.forEach { group ->
            if (group.type != type) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                when (type) {
                    C.TRACK_TYPE_VIDEO -> {
                        val height = format.height
                        if (height == Format.NO_VALUE || height <= 0) continue
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: "${format.width}x$height"
                        result.add(
                            TrackEntry(
                                group = group,
                                index = i,
                                type = type,
                                label = qualityLabel(height, label),
                                active = group.isTrackSelected(i),
                                height = height,
                            )
                        )
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        val language = languageLabel(format.language)
                        val channels = when (format.channelCount) {
                            Format.NO_VALUE, -1, 0 -> ""
                            else -> "${format.channelCount}ch"
                        }
                        val label = listOfNotNull(language, channels.takeIf { it.isNotEmpty() })
                            .joinToString(" • ")
                            .ifBlank {
                                format.label?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}"
                            }
                        result.add(
                            TrackEntry(
                                group = group,
                                index = i,
                                type = type,
                                label = label,
                                active = group.isTrackSelected(i),
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun qualityLabel(height: Int, fallback: String): String {
        if (height in intArrayOf(2160, 1440, 1080, 720, 576, 480, 360)) {
            return "${height}p"
        }
        return fallback
    }

    private fun languageLabel(lang: String?): String {
        val code = lang ?: return ""
        if (code.isBlank()) return ""
        val lower = code.lowercase(Locale.ROOT)
        val mapped = when (lower) {
            "eng", "en", "en-us" -> "English"
            "spa", "es", "es-es", "es-419" -> "Spanish"
            "fra", "fr", "fr-fr" -> "French"
            "deu", "de", "de-de" -> "German"
            "ita", "it", "it-it" -> "Italian"
            "por", "pt", "pt-br", "pt-pt" -> "Portuguese"
            "rus", "ru", "ru-ru" -> "Russian"
            "jpn", "ja", "ja-jp" -> "Japanese"
            "zho", "chi", "zh", "zh-cn" -> "Chinese"
            "ara", "ar" -> "Arabic"
            "hin" -> "Hindi"
            "kor", "ko" -> "Korean"
            "pol", "pl" -> "Polish"
            "tur", "tr" -> "Turkish"
            "hun", "hu" -> "Hungarian"
            "ron", "ro" -> "Romanian"
            "ell", "el" -> "Greek"
            "cze", "cs" -> "Czech"
            "swe", "sv" -> "Swedish"
            "dan", "da" -> "Danish"
            "fin", "fi" -> "Finnish"
            "nor", "no" -> "Norwegian"
            "nld", "nl" -> "Dutch"
            "tha", "th" -> "Thai"
            "vie", "vi" -> "Vietnamese"
            "ind", "id" -> "Indonesian"
            "ukr", "uk" -> "Ukrainian"
            "heb", "he" -> "Hebrew"
            "bul", "bg" -> "Bulgarian"
            "hrv", "hr" -> "Croatian"
            "slk", "sk" -> "Slovak"
            "slv", "sl" -> "Slovenian"
            "mon", "mn" -> "Mongolian"
            "srp", "sr" -> "Serbian"
            "und" -> "Unknown"
            else -> code
        }
        return if (mapped == code) {
            runCatching { ani.sanin.others.LanguageMapper.getLanguageName(code) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: code
        } else {
            mapped
        }
    }

    fun open() {
        targetEpisodeKey = null
        show(Tab.SOURCES)
    }

    fun openForEpisode(epKey: String) {
        targetEpisodeKey = epKey
        show(Tab.SOURCES)
    }

    fun close() {
        if (!isOpen()) return
        content.animate().translationX(-content.width.toFloat()).setDuration(180)
            .withEndAction {
                content.visibility = View.GONE
                targetEpisodeKey = null
                onClosed()
            }
            .start()
    }

    fun isOpen(): Boolean = content.visibility == View.VISIBLE

    fun focusFirst() {
        val focusable = rows.indexOfFirst { isFocusableRow(it) }
        if (focusable >= 0) {
            recycler.post {
                recycler.scrollToPosition(focusable)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(focusable)
                    if (holder != null) holder.itemView.requestFocus()
                    else closeButton.requestFocus()
                }
            }
        } else {
            closeButton.requestFocus()
        }
    }

    private fun activeEpisodeKey(): String? =
        targetEpisodeKey ?: media.anime?.selectedEpisode

    private fun currentEpisode(): Episode? {
        val m = media
        val key = activeEpisodeKey() ?: return null
        return m.anime?.episodes?.getEpisode(key)
    }

    private fun refreshAll() {
        val m = media
        val epKey = activeEpisodeKey()
        rows.clear()
        arrivalCounter = 0
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        if (tmdbSession != null) {
            val (season, epNum) = tmdbSeasonEp()
            TmdbStreamResolver.invalidateLinks(m.id)
            RailRepo.refreshTmdb(m.id, season, epNum)
            tmdbGroups.forEach { g ->
                g.state = TmdbGroupState.Loading
                g.rank = 2
                g.arrivalSeq = Int.MAX_VALUE
            }
            buildChips()
            refreshRows()
            tmdbGroups.forEachIndexed { i, _ ->
                if (mode == 0 || i == tmdbSelectedIndex) startTmdbGroup(i)
            }
        } else {
            RailRepo.refreshAnime(m.id, epKey)
            val currentSourceIndex = m.selected?.sourceIndex ?: -1
            groups.forEach { g ->
                g.state = AnimeGroupState.Loading
                g.rank = 2
                g.arrivalSeq = Int.MAX_VALUE
                g.extractors.clear()
                g.extractionDone = false
                g.episode = null
            }
            buildChips()
            refreshRows()
            groups.forEachIndexed { i, g ->
                if (mode == 0 || i == selectedIndex) {
                    RailRepo.startAnime(
                        RailRepo.animeKey(m.id, epKey, g.index),
                        m, epKey, g.index, currentSourceIndex, g.parser, sources,
                    ) { entry ->
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            onAnimeUpdate(g, entry)
                        }
                    }
                }
            }
        }
    }

    private fun rebuild() {
        val focusPosition = focusedAdapterPosition()
        val focusKey = rows.getOrNull(focusPosition)?.let { rowKey(it) }
        val m = media
        val epKey = activeEpisodeKey()
        val ep = currentEpisode()
        val season = ep?.extra?.get("season")?.toIntOrNull()
        val epNum = ep?.extra?.get("episode")?.toIntOrNull()
        RailRepo.prune(m.id, epKey, season, epNum)
        rows.clear()
        arrivalCounter = 0
        if (tmdbSession != null) {
            buildTmdbGroups()
        } else {
            buildAnimeGroups()
        }
        adapter.notifyDataSetChanged()
        restoreRowFocus(focusPosition, focusKey)
    }

    // --- Anime mode ---

    private fun buildAnimeGroups() {
        groups.clear()
        chipByIndex.clear()
        val m = media
        val epKey = activeEpisodeKey()
        val currentSourceIndex = m.selected?.sourceIndex ?: -1
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        sources.list.forEachIndexed { index, lazier ->
            val listName = lazier.name
            if (listName.equals("Local", ignoreCase = true)) return@forEachIndexed
            val parser = runCatching { lazier.get.value as? AnimeParser }.getOrNull()
                ?: return@forEachIndexed
            groups.add(Group(index, listName.ifBlank { parser.name }, parser))
        }
        selectedIndex = groups.indexOfFirst { it.index == currentSourceIndex }
            .takeIf { it >= 0 } ?: 0
        groups.forEachIndexed { i, group ->
            val key = RailRepo.animeKey(m.id, epKey, group.index)
            val cached = RailRepo.watchAnime(key) { entry ->
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    onAnimeUpdate(group, entry)
                }
            }
            if (cached != null) {
                hydrateGroup(group, cached)
            } else if (mode == 0 || i == selectedIndex) {
                RailRepo.startAnime(
                    key, m, epKey, group.index, currentSourceIndex, group.parser, sources,
                ) { entry ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        onAnimeUpdate(group, entry)
                    }
                }
            } else {
                group.state = AnimeGroupState.Loading
            }
        }
        buildChips()
        refreshRows()
    }

    private fun hydrateGroup(group: Group, entry: AnimeEntry) {
        group.episode = entry.episode
        group.extractors.clear()
        group.extractors.addAll(entry.extractors)
        group.extractionDone = entry.extractionDone
        group.state = when {
            entry.failedReason != null -> AnimeGroupState.Failed(entry.failedReason)
            else -> AnimeGroupState.Ready(entry.servers)
        }
    }

    private fun onAnimeUpdate(group: Group, entry: AnimeEntry) {
        hydrateGroup(group, entry)
        if (entry.extractionDone) {
            val ready = entry.failedReason == null && entry.servers.isNotEmpty()
            group.rank = when {
                ready -> 0
                entry.failedReason != null -> 1
                else -> 2
            }
            if (group.rank == 0) group.arrivalSeq = arrivalCounter++
            val selectedKey = groups.getOrNull(selectedIndex)?.index
            groups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }))
            selectedIndex = groups.indexOfFirst { it.index == selectedKey }
                .takeIf { it >= 0 } ?: selectedIndex
            buildChips()
        } else {
            renderChipStates()
        }
        refreshRows()
    }

    private fun selectGroup(index: Int) {
        if (index !in groups.indices) return
        val changed = selectedIndex != index
        selectedIndex = index
        val m = media
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault)) {
            m.selected?.sourceIndex = groups[index].index
            model.saveSelected(m.id, m.selected!!)
        }
        buildChips()
        refreshRows()
        if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1) {
            if (changed) {
                groups.forEachIndexed { i, g -> if (i != index) g.state = AnimeGroupState.Loading }
                buildChips()
            }
            val group = groups[index]
            val epKey = activeEpisodeKey()
            val key = RailRepo.animeKey(m.id, epKey, group.index)
            if (group.state is AnimeGroupState.Loading) {
                RailRepo.startAnime(
                    key, m, epKey, group.index,
                    m.selected?.sourceIndex ?: -1, group.parser, sources,
                ) { entry ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        onAnimeUpdate(group, entry)
                    }
                }
            }
        }
    }

    private fun refreshAnimeRows() {
        val group = groups.getOrNull(selectedIndex)
        if (group == null) return
        when (val state = group.state) {
            is AnimeGroupState.Ready -> {
                if (state.servers.isEmpty()) return
                if (PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
                    state.servers.forEach { server ->
                        val ex = group.extractors.firstOrNull { it.server.name == server.name }
                        val hasVideos = ex?.videos?.isNotEmpty() == true
                        if (group.extractionDone && !hasVideos) {
                            return@forEach
                        }
                        val (_, _, parsedAudio) = splitServerTitle(server.name)
                        rows.add(
                            Row.Header(
                                cleanServerName(server.name),
                                parsedAudio?.uppercase()
                                    ?: server.extraData?.get("audio")?.takeIf { it.isNotBlank() }
                                        ?.uppercase(),
                            )
                        )
                        if (hasVideos) {
                            ex.videos.forEachIndexed { idx, _ ->
                                rows.add(
                                    Row.Video(
                                        group, ex, idx,
                                        isFirst = idx == 0,
                                        isLast = idx == ex.videos.lastIndex,
                                        server = server,
                                    )
                                )
                            }
                        }
                    }
                } else {
                    state.servers.forEach { server ->
                        val ex = group.extractors.firstOrNull { it.server.name == server.name }
                            ?: return@forEach
                        if (group.extractionDone && ex.videos.isEmpty()) {
                            return@forEach
                        }
                        ex.videos.forEachIndexed { idx, _ ->
                            rows.add(
                                Row.Video(
                                    group, ex, idx,
                                    isFirst = idx == 0,
                                    isLast = idx == ex.videos.lastIndex,
                                    server = server,
                                )
                            )
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // --- TMDB mode ---

    private fun tmdbSeasonEp(): Pair<Int?, Int?> {
        val ep = currentEpisode()
        val season = ep?.extra?.get("season")?.toIntOrNull()
        val epNum = ep?.extra?.get("episode")?.toIntOrNull()
        return season to epNum
    }

    private fun buildTmdbGroups() {
        tmdbGroups.clear()
        chipByIndex.clear()
        val session = tmdbSession ?: return
        CsRepos.installed(activity).forEach { source ->
            tmdbGroups.add(TmdbGroup(source.id, source.name, source.iconUrl, source))
        }
        StremioAddonManager.installed().forEach { addon ->
            val synthetic = CsInstalledSource(
                id = StremioAddonManager.sourceId(addon),
                name = addon.manifest.name,
                version = 1,
                type = "movie",
                lang = "en",
                url = addon.transportUrl,
                repoUrl = "",
                iconUrl = addon.manifest.logo,
            )
            tmdbGroups.add(
                TmdbGroup(
                    synthetic.id,
                    synthetic.name,
                    synthetic.iconUrl,
                    synthetic,
                    addon = addon,
                )
            )
        }
        val savedId = PrefManager.getNullableCustomVal(
            "tmdb_source_${session.mediaId}", null, String::class.java,
        )
        tmdbSelectedIndex = tmdbGroups.indexOfFirst { it.source.id == session.source.id }
            .takeIf { it >= 0 }
            ?: tmdbGroups.indexOfFirst { it.key.equals(savedId ?: "", ignoreCase = true) }
                .takeIf { it >= 0 }
            ?: 0
        val (season, epNum) = tmdbSeasonEp()
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        tmdbGroups.forEachIndexed { i, group ->
            val key = RailRepo.tmdbKey(session.mediaId, season, epNum, group.key)
            val cached = RailRepo.getTmdb(key)
            if (cached != null) {
                group.state = cached.state
            } else if (mode == 0 || i == tmdbSelectedIndex) {
                startTmdbGroup(i)
            } else {
                group.state = TmdbGroupState.Loading
            }
        }
        buildChips()
        refreshRows()
    }

    private fun startTmdbGroup(index: Int) {
        val session = tmdbSession ?: return
        val group = tmdbGroups.getOrNull(index) ?: return
        val (season, epNum) = tmdbSeasonEp()
        val key = RailRepo.tmdbKey(session.mediaId, season, epNum, group.key)
        RailRepo.startTmdb(key, activity, session, season, epNum, group.source, group.addon) { entry ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                onTmdbUpdate(group, entry)
            }
        }
    }

    private fun onTmdbUpdate(group: TmdbGroup, entry: TmdbEntry) {
        group.state = entry.state
        if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1 &&
            tmdbGroups.getOrNull(tmdbSelectedIndex) !== group
        ) {
            return
        }
        if (entry.state is TmdbGroupState.Ready) {
            group.rank = 0
            group.arrivalSeq = arrivalCounter++
        } else if (entry.state is TmdbGroupState.Failed) {
            group.rank = 1
        }
        val selectedKey = tmdbGroups.getOrNull(tmdbSelectedIndex)?.key
        tmdbGroups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }))
        tmdbSelectedIndex = tmdbGroups.indexOfFirst { it.key == selectedKey }
            .takeIf { it >= 0 } ?: tmdbSelectedIndex
        buildChips()
        refreshRows()
    }

    private fun selectTmdbGroup(index: Int) {
        if (index !in tmdbGroups.indices) return
        val changed = tmdbSelectedIndex != index
        tmdbSelectedIndex = index
        val session = tmdbSession
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault) && session != null) {
            PrefManager.setCustomVal("tmdb_source_${session.mediaId}", tmdbGroups[index].key)
        }
        buildChips()
        refreshRows()
        if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1) {
            if (changed) {
                tmdbGroups.forEachIndexed { i, g ->
                    if (i != index) g.state = TmdbGroupState.Loading
                }
                buildChips()
            }
            if (tmdbGroups[index].state is TmdbGroupState.Loading) {
                startTmdbGroup(index)
            }
        }
    }

    private fun refreshTmdbRows() {
        val group = tmdbGroups.getOrNull(tmdbSelectedIndex) ?: return
        when (val state = group.state) {
            is TmdbGroupState.Ready -> {
                val playable = state.links.filter { it.url.isNotBlank() }
                state.recommendations.forEach { rows.add(Row.Extra(it, group.addon)) }
                state.infos.forEach { rows.add(Row.Info(it, group.addon)) }
                if (playable.isNotEmpty()) {
                    val extractorNames = tmdbExtractorNames(state.links)
                    val playingEp = currentEpisode()
                    if (PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags) &&
                        playable.size > 1
                    ) {
                        val byHost = LinkedHashMap<String, MutableList<TmdbStreamResolver.StreamResult.PlayableLink>>()
                        playable.forEach { link ->
                            byHost.getOrPut(link.host.ifBlank { link.label }) { mutableListOf() }.add(link)
                        }
                        if (byHost.size <= 1) {
                            playable.forEach { link ->
                                rows.add(Row.Stream(link, group, isTmdbRowSelected(playingEp, extractorNames[link])))
                            }
                        } else {
                            byHost.forEach { (host, links) ->
                                rows.add(Row.Header(host, null))
                                links.forEach { link ->
                                    rows.add(Row.Stream(link, group, isTmdbRowSelected(playingEp, extractorNames[link])))
                                }
                            }
                        }
                    } else {
                        playable.forEach { link ->
                            rows.add(Row.Stream(link, group, isTmdbRowSelected(playingEp, extractorNames[link])))
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** Mirror of TmdbStreamResolver.buildExtractors naming, to map links back to
     *  (extractor name, video index) so the playing row can be highlighted. */
    private fun tmdbExtractorNames(
        links: List<TmdbStreamResolver.StreamResult.PlayableLink>,
    ): Map<TmdbStreamResolver.StreamResult.PlayableLink, Pair<String, Int>> {
        val result = HashMap<TmdbStreamResolver.StreamResult.PlayableLink, Pair<String, Int>>()
        if (!PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
            val usedNames = HashSet<String>()
            links.forEachIndexed { index, link ->
                if (link.url.isBlank()) return@forEachIndexed
                var name = link.label.ifBlank { "Server ${index + 1}" }
                if (!usedNames.add(name.lowercase())) name = "$name ${usedNames.size + 1}"
                result[link] = name to 0
            }
        } else {
            val usedNames = HashSet<String>()
            links.filter { it.url.isNotBlank() }
                .groupBy { it.host.ifBlank { it.label }.ifBlank { "Server" } }
                .forEach { (_, groupLinks) ->
                    var name = groupLinks.first().host
                        .ifBlank { groupLinks.first().label }
                        .ifBlank { "Server" }
                    if (!usedNames.add(name.lowercase())) name = "$name ${usedNames.size + 1}"
                    groupLinks.forEachIndexed { idx, link ->
                        if (link.url.isNotBlank()) result[link] = name to idx
                    }
                }
        }
        return result
    }

    private fun isTmdbRowSelected(
        ep: Episode?,
        nameAndIdx: Pair<String, Int>?,
    ): Boolean {
        if (ep == null || nameAndIdx == null) return false
        return ep.selectedExtractor == nameAndIdx.first && ep.selectedVideo == nameAndIdx.second
    }

    // --- chips ---

    private fun buildChips() {
        val focusedIndex = chipByIndex.indexOfFirst { it.hasFocus() }
        chipsGroup.removeAllViews()
        chipByIndex.clear()
        chipsScroll.isVisible = currentTab == Tab.SOURCES
        val inflater = LayoutInflater.from(activity)
        val listCount = if (tmdbSession != null) tmdbGroups.size else groups.size
        for (index in 0 until listCount) {
            val chip = inflater.inflate(
                R.layout.item_tmdb_chip, chipsGroup, false,
            ) as Chip
            chip.id = View.generateViewId()
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(activity.resources.getColorStateList(R.color.chip_text_color))
            chip.isChecked = index == if (tmdbSession != null) tmdbSelectedIndex else selectedIndex
            chip.setOnClickListener {
                if (tmdbSession != null) selectTmdbGroup(index) else selectGroup(index)
            }
            FocusEffectUtil.applyFocusListener(chip)
            chipsGroup.addView(chip)
            chipByIndex.add(chip)
        }
        chipByIndex.forEachIndexed { index, chip ->
            chip.nextFocusUpId = refreshAllView.id
            chip.nextFocusDownId = R.id.serverRailList
            chip.nextFocusLeftId = if (index == 0) chip.id else chipByIndex[index - 1].id
            chip.nextFocusRightId =
                if (index == chipByIndex.lastIndex) chip.id else chipByIndex[index + 1].id
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirst()
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0 -> true
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        index == chipByIndex.lastIndex -> true
                    else -> false
                }
            }
        }
        val selected = if (tmdbSession != null) tmdbSelectedIndex else selectedIndex
        val selectedChipId = chipByIndex.getOrNull(selected)?.id ?: R.id.serverRailList
        refreshAllView.nextFocusDownId = selectedChipId
        makeDefaultCheck.nextFocusDownId = selectedChipId
        if (focusedIndex >= 0) {
            val target = chipByIndex.getOrNull(focusedIndex)
            if (target != null && !target.requestFocus()) {
                chipsGroup.post {
                    val current = activity.currentFocus
                    if (current == null || current === target) target.requestFocus()
                }
            }
        }
        renderChipStates()
    }

    private fun renderChipStates() {
        chipByIndex.forEachIndexed { index, chip ->
            if (tmdbSession != null) {
                val group = tmdbGroups.getOrNull(index) ?: return@forEachIndexed
                chip.text = when (val st = group.state) {
                    is TmdbGroupState.Loading -> "${group.name} ·"
                    is TmdbGroupState.Failed -> "${group.name} (failed)"
                    is TmdbGroupState.Ready -> {
                        val playable = st.links.count { it.url.isNotBlank() } +
                            st.recommendations.size + st.infos.size
                        if (playable > 0) group.name else "${group.name} ·"
                    }
                }
            } else {
                val group = groups.getOrNull(index) ?: return@forEachIndexed
                chip.text = when (val st = group.state) {
                    is AnimeGroupState.Loading -> "${group.name} ·"
                    is AnimeGroupState.Failed -> "${group.name} (failed)"
                    is AnimeGroupState.Ready -> if (st.servers.isNotEmpty()) group.name else "${group.name} ·"
                }
            }
        }
    }

    // --- row refresh ---

    private fun refreshRows() {
        if (currentTab != Tab.SOURCES) return
        val focusPosition = focusedAdapterPosition()
        val focusKey = rows.getOrNull(focusPosition)?.let { rowKey(it) }
        rows.clear()
        if (tmdbSession != null) {
            refreshTmdbRows()
        } else {
            refreshAnimeRows()
        }
        adapter.notifyDataSetChanged()
        restoreRowFocus(focusPosition, focusKey)
    }

    // --- text helpers (parity with the servers screen) ---

    private fun splitServerTitle(name: String): Triple<String, String?, String?> {
        val audioRx = Regex("\\b(sub|dub|softsub|hardsub)\\b", RegexOption.IGNORE_CASE)
        val qualityRx = Regex("\\b(\\d{3,4}\\s*p|auto|unknown)\\b", RegexOption.IGNORE_CASE)
        val audio = audioRx.find(name)?.groupValues?.getOrNull(1)
        val quality = qualityRx.find(name)?.groupValues?.getOrNull(1)
        var cleaned = name
            .replace(audioRx, " ")
            .replace(qualityRx, " ")
            .replace(Regex("[()\\[\\]]"), " ")
            .replace(Regex("\\s*[-–·|]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '·', '|', ' ')
        if (cleaned.isBlank()) cleaned = name
        return Triple(cleaned, quality, audio)
    }

    private fun cleanServerName(name: String): String = splitServerTitle(name).first

    private fun splitStreamLabel(label: String): Pair<String, String?> {
        val idx = label.indexOf(" · ")
        return if (idx > 0) {
            label.substring(0, idx) to label.substring(idx + 3)
        } else {
            label to null
        }
    }

    private fun multiLineNote(text: String?): String? {
        val cleaned = text?.replace('\u200b', ' ')?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return null
        val lines = cleaned.split(Regex("\n|\\s*[·•|]\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    // --- actions ---

    private fun switchServer(group: Group, extractor: VideoExtractor, videoIdx: Int) {
        if (launching) return
        val m = media
        val pickingNextEpisode = targetEpisodeKey != null
        val epKey = activeEpisodeKey() ?: return
        val ep = m.anime?.episodes?.getEpisode(epKey) ?: group.episode ?: return
        launching = true
        if (ep.extractorsSource != group.index) {
            ep.extractors = mutableListOf()
            ep.extractorsSource = group.index
            ep.allStreams = false
        }
        ep.extractors = (ep.extractors?.filterNotNull() ?: mutableListOf()).toMutableList().also { list ->
            if (list.none { it.server.name == extractor.server.name }) list.add(extractor)
        }
        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = videoIdx
        val key = m.anime?.episodes?.getEpisodeKey(epKey) ?: epKey
        m.anime?.selectedEpisode = key
        m.anime?.episodes?.get(key)?.let { mapped ->
            if (mapped !== ep) {
                mapped.extractors = ep.extractors
                mapped.extractorsSource = ep.extractorsSource
                mapped.selectedExtractor = ep.selectedExtractor
                mapped.selectedVideo = ep.selectedVideo
                m.anime?.episodes?.set(key, mapped)
            }
        }
        m.selected?.sourceIndex = group.index
        m.selected?.server = extractor.server.name
        m.selected?.video = videoIdx
        model.saveSelected(m.id, m.selected!!)
        Logger.log(
            "SERVER_RAIL: switch '${m.name}' ep=${ep.number} via ${group.name}/" +
                "${extractor.server.name} video=$videoIdx"
        )
        if (pickingNextEpisode) {
            targetEpisodeKey = null
            activity.restartWithServer(savePosition = false)
        } else {
            activity.restartWithServer()
        }
    }

    private fun playTmdbStream(link: TmdbStreamResolver.StreamResult.PlayableLink, group: TmdbGroup) {
        if (launching) return
        val state = group.state as? TmdbGroupState.Ready ?: return
        val session = tmdbSession ?: return
        val playable = state.links.filter { it.url.isNotBlank() }
        if (playable.isEmpty()) return
        launching = true
        val (season, epNum) = tmdbSeasonEp()
        Logger.log("SERVER_RAIL: tmdb play '${session.detail.displayTitle}' via ${group.name} label='${link.label}'")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val launched = runCatching {
                TmdbStreamResolver.launchPlayer(
                    activity,
                    session.mediaId,
                    session.mediaType,
                    session.detail,
                    group.source,
                    season,
                    epNum,
                    playable,
                    link.label,
                    stremioAddon = group.addon,
                )
            }.getOrElse {
                Logger.log(android.util.Log.ERROR, "SERVER_RAIL: tmdb play failed: ${it.message}")
                false
            }
            if (!launched) activity.runOnUiThread { launching = false }
        }
    }

    private fun openInfoExternal(link: TmdbStreamResolver.StreamResult.PlayableLink, addon: StremioAddon?) {
        val url = link.externalUrl ?: return
        if (!url.startsWith("stremio://", ignoreCase = true)) {
            runCatching {
                activity.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    )
                )
            }.onFailure {
                Logger.log("SERVER_RAIL: external open failed url=$url error=${it.message}")
            }
            return
        }
        val target = TmdbExtras.parseStremioTarget(url) ?: run {
            Logger.log("SERVER_RAIL: invalid stremio link url=$url")
            return
        }
        val (typeHint, id) = target
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { Tmdb.findByImdb(id, typeHint) }.getOrNull()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (found != null) {
                activity.startActivity(
                    android.content.Intent(activity, TmdbDetailsActivity::class.java)
                        .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, found.type)
                        .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, found.id)
                )
            } else {
                activity.startActivity(
                    android.content.Intent(activity, TmdbDetailsActivity::class.java)
                        .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, typeHint)
                        .putExtra(TmdbDetailsActivity.ARG_IMDB_ID, id)
                        .putExtra(TmdbDetailsActivity.ARG_TITLE, link.label.trim().takeIf { it.isNotBlank() })
                        .apply {
                            addon?.let {
                                putExtra(TmdbDetailsActivity.ARG_SOURCE_ADDON, it.transportUrl)
                            }
                        }
                )
            }
            activity.finish()
        }
    }

    // --- focus helpers ---

    private fun focusedAdapterPosition(): Int {
        val focused = recycler.findFocus() ?: return RecyclerView.NO_POSITION
        var view: View? = focused
        while (view != null && view.parent !== recycler) view = view.parent as? View
        return view?.let { recycler.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
    }

    private fun rowKey(row: Row): String = when (row) {
        is Row.Header -> "header|${row.name}"
        is Row.TrackHeader -> "trackheader|${row.name}"
        is Row.Track -> "track|${row.entry.type}|${row.entry.index}|${row.entry.label}"
        is Row.Video -> "video|${row.group.name}|${row.extractor.server.name}|${row.idx}"
        is Row.Stream -> "stream|${row.group.key}|${row.link.label}|${row.link.url}"
        is Row.Info -> "info|${row.link.label}|${row.link.url}"
        is Row.Extra -> "extra|${row.item.id}"
    }

    private fun isFocusableRow(row: Row): Boolean = when (row) {
        is Row.Video, is Row.Stream, is Row.Extra, is Row.Track -> true
        is Row.Info -> row.link.externalUrl != null
        else -> false
    }

    private fun restoreRowFocus(position: Int, key: String?) {
        if (position == RecyclerView.NO_POSITION) return
        recycler.post {
            if (rows.isEmpty()) return@post
            if (activity.currentFocus != null) return@post
            val match = key?.let { k -> rows.indexOfFirst { rowKey(it) == k } } ?: -1
            var target = if (match >= 0) match else position.coerceIn(0, rows.lastIndex)
            if (!isFocusableRow(rows[target])) {
                target = (target until rows.size).firstOrNull { isFocusableRow(rows[it]) }
                    ?: (target - 1 downTo 0).firstOrNull { isFocusableRow(rows[it]) }
                    ?: return@post
            }
            val holder = recycler.findViewHolderForAdapterPosition(target)
            if (holder != null) {
                if (holder.itemView.isFocusable) holder.itemView.requestFocus()
            } else {
                recycler.post {
                    recycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                }
            }
        }
    }

    // --- adapter ---

    private inner class RailAdapter : RecyclerView.Adapter<RailViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Extra) 2 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                2 -> ItemTmdbStreamsExtraBinding.inflate(inf, parent, false).let {
                    RailViewHolder(RailViewHolderType.EXTRA, it.root, extraB = it)
                }
                else -> ItemTmdbServerRowBinding.inflate(inf, parent, false).let {
                    RailViewHolder(RailViewHolderType.ROW, it.root, rowB = it)
                }
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> holder.bindHeader(row)
                is Row.TrackHeader -> holder.bindTrackHeader(row)
                is Row.Track -> holder.bindTrack(row, position)
                is Row.Video -> holder.bindVideo(row)
                is Row.Stream -> holder.bindStream(row)
                is Row.Info -> holder.bindInfo(row)
                is Row.Extra -> holder.bindExtra(row)
            }
        }
    }

    private enum class RailViewHolderType { ROW, EXTRA }

    private inner class RailViewHolder(
        val type: RailViewHolderType,
        root: View,
        private val rowB: ItemTmdbServerRowBinding? = null,
        private val extraB: ItemTmdbStreamsExtraBinding? = null,
    ) : RecyclerView.ViewHolder(root) {

        init {
            rowB?.let {
                (it.root as CardView).apply {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    cardElevation = 0f
                }
            }
            extraB?.let {
                (it.root as CardView).apply {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    cardElevation = 0f
                }
            }
        }

        private fun setRowMargins(topDp: Int, bottomDp: Int) {
            val lp = itemView.layoutParams as? RecyclerView.LayoutParams ?: return
            val d = itemView.resources.displayMetrics.density
            lp.topMargin = (topDp * d).toInt()
            lp.bottomMargin = (bottomDp * d).toInt()
            itemView.layoutParams = lp
        }

        private fun focusCorners() {
            val d = itemView.resources.displayMetrics.density
            FocusEffectUtil.applyFocusListener(
                itemView,
                floatArrayOf(16f, 16f, 16f, 16f, 16f, 16f, 16f, 16f).map { it * d }
                    .toFloatArray(),
            )
        }

        /** SubHead-style header above a merged grey card (or host grouping in TMDB mode). */
        fun bindHeader(row: Row.Header) {
            val b = rowB ?: return
            b.root.background = null
            b.urlQuality.text = row.name
            b.urlQuality.textSize = 14f
            b.urlQuality.alpha = 0.6f
            b.urlQuality.setTypeface(null, android.graphics.Typeface.NORMAL)
            b.urlQuality.setTextColor(
                b.root.context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
            )
            b.urlNote.isVisible = false
            b.urlAudio.isVisible = false
            b.urlTag.text = row.tag
            b.urlTag.isVisible = !row.tag.isNullOrBlank()
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener(null)
            itemView.isClickable = false
            itemView.isFocusable = false
            setRowMargins(12, 4)
        }

        /** Bold white header for the Tracks tab (Video / Audio sections). */
        fun bindTrackHeader(row: Row.TrackHeader) {
            val b = rowB ?: return
            b.root.background = null
            b.urlQuality.text = row.name
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTypeface(null, android.graphics.Typeface.BOLD)
            b.urlQuality.setTextColor(Color.WHITE)
            b.urlNote.isVisible = false
            b.urlAudio.isVisible = false
            b.urlTag.isVisible = false
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener(null)
            itemView.isClickable = false
            itemView.isFocusable = false
            setRowMargins(12, 4)
        }

        fun bindTrack(row: Row.Track, position: Int) {
            val b = rowB ?: return
            GlassPill.applyServerRowCard(
                b.root,
                cornerRadiusDp = 14f,
                showTopRim = true,
                selected = row.active,
            )
            b.urlQuality.text = row.entry.label
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTypeface(null, android.graphics.Typeface.NORMAL)
            b.urlQuality.setTextColor(
                b.root.context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
            )
            b.urlNote.isVisible = false
            b.urlAudio.isVisible = false
            if (row.active) {
                b.urlTag.text = "▶ Playing"
                b.urlTag.setTextColor(
                    b.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                )
                b.urlTag.alpha = 1f
                b.urlTag.isVisible = true
            } else {
                b.urlTag.isVisible = false
            }
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener {
                activity.onSetTrackGroupOverride(
                    row.entry.group,
                    row.entry.type,
                    row.entry.index,
                )
                rebuildTracks()
                recycler.post {
                    recycler.scrollToPosition(position)
                    recycler.post {
                        recycler.findViewHolderForAdapterPosition(position)
                            ?.itemView?.requestFocus()
                    }
                }
            }
            itemView.isFocusable = true
            focusCorners()
            setRowMargins(6, 0)
        }

        fun bindVideo(row: Row.Video) {
            val b = rowB ?: return
            val video = row.extractor.videos.getOrNull(row.idx)
            val m = media
            val selected = m.selected?.sourceIndex == row.group.index &&
                m.selected?.server == row.extractor.server.name &&
                m.selected?.video == row.idx
            GlassPill.applyServerRowCard(
                b.root,
                cornerRadiusDp = 14f,
                topRounded = row.isFirst,
                bottomRounded = row.isLast,
                showTopRim = row.isFirst,
                selected = selected,
            )
            b.urlQuality.text =
                video?.quality?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTypeface(null, android.graphics.Typeface.NORMAL)
            b.urlQuality.setTextColor(
                b.root.context.getThemeColor(com.google.android.material.R.attr.colorSecondary)
            )
            val note = listOfNotNull(
                when (video?.format) {
                    VideoType.M3U8 -> "M3U8"
                    VideoType.DASH -> "DASH"
                    else -> null
                },
                video?.extraNote?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (note.isBlank()) {
                b.urlNote.isVisible = false
            } else {
                b.urlNote.text = note
                b.urlNote.isVisible = true
            }
            if (selected) {
                b.urlTag.text = "▶ Playing"
                b.urlTag.setTextColor(
                    b.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                )
                b.urlTag.alpha = 1f
                b.urlTag.isVisible = true
            } else {
                val audio = if (row.server != null) {
                    splitServerTitle(row.server.name).third?.uppercase()
                        ?: row.server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()
                } else null
                b.urlTag.text = audio
                b.urlTag.setTextColor(
                    b.root.context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
                )
                b.urlTag.alpha = 0.6f
                b.urlTag.isVisible = !audio.isNullOrBlank()
            }
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener { switchServer(row.group, row.extractor, row.idx) }
            itemView.isFocusable = true
            setRowMargins(
                if (row.isFirst) (if (row.server != null) 2 else 0) else 1,
                0,
            )
            focusCorners()
        }

        fun bindStream(row: Row.Stream) {
            val b = rowB ?: return
            GlassPill.applyServerRowCard(
                b.root,
                cornerRadiusDp = 14f,
                showTopRim = true,
                selected = row.selected,
            )
            val (quality, hint) = splitStreamLabel(row.link.label)
            b.urlQuality.text = quality
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTypeface(null, android.graphics.Typeface.NORMAL)
            b.urlQuality.setTextColor(
                b.root.context.getThemeColor(com.google.android.material.R.attr.colorSecondary)
            )
            val note = listOfNotNull(
                row.link.host.takeIf { it.isNotBlank() },
                multiLineNote(row.link.details) ?: hint?.takeIf { it.isNotBlank() },
            ).joinToString("\n")
            if (note.isBlank()) {
                b.urlNote.isVisible = false
            } else {
                b.urlNote.text = note
                b.urlNote.isVisible = true
            }
            if (row.selected) {
                b.urlTag.text = "▶ Playing"
                b.urlTag.setTextColor(
                    b.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                )
                b.urlTag.alpha = 1f
                b.urlTag.isVisible = true
            } else {
                b.urlTag.isVisible = false
            }
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener { playTmdbStream(row.link, row.group) }
            itemView.isFocusable = true
            focusCorners()
        }

        fun bindInfo(row: Row.Info) {
            val b = rowB ?: return
            GlassPill.applyServerRowCard(b.root, cornerRadiusDp = 14f, showTopRim = true)
            b.urlQuality.text = row.link.label
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTextColor(
                b.root.context.getThemeColor(com.google.android.material.R.attr.colorSecondary)
            )
            val note = multiLineNote(row.link.details)
            if (note.isNullOrBlank()) {
                b.urlNote.isVisible = false
            } else {
                b.urlNote.text = note
                b.urlNote.isVisible = true
            }
            b.urlTag.isVisible = false
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            if (row.link.externalUrl != null) {
                b.root.setOnClickListener { openInfoExternal(row.link, row.addon) }
                itemView.isFocusable = true
            } else {
                b.root.setOnClickListener(null)
                itemView.isClickable = false
                itemView.isFocusable = false
            }
            focusCorners()
        }

        fun bindExtra(row: Row.Extra) {
            val b = extraB ?: return
            GlassPill.applyServerRowCard(b.root, cornerRadiusDp = 14f, showTopRim = true)
            b.tmdbExtraPoster.loadImage(
                Rpdb.posterUrl(row.item.type, row.item.id) ?: (
                    Tmdb.imageUrl(row.item.posterPath ?: row.item.backdropPath, 300)
                        ?: if (row.item.posterPath?.startsWith("http") == true) row.item.posterPath else null
                    )
            )
            b.tmdbExtraTitle.text = row.item.displayTitle
            val meta = listOfNotNull(
                String.format(java.util.Locale.US, "★ %.1f", row.item.voteAverage)
                    .takeIf { row.item.voteAverage > 0 },
                (row.item.releaseDate ?: row.item.firstAirDate).takeIf { !it.isNullOrBlank() },
                String.format(java.util.Locale.US, "Popularity %.1f", row.item.popularity)
                    .takeIf { row.item.popularity > 0 },
            ).joinToString("  •  ")
            if (meta.isBlank()) {
                b.tmdbExtraMeta.isVisible = false
            } else {
                b.tmdbExtraMeta.text = meta
                b.tmdbExtraMeta.isVisible = true
            }
            val overview = row.item.overview?.takeIf { it.isNotBlank() }
            if (overview == null) {
                b.tmdbExtraOverview.isVisible = false
            } else {
                b.tmdbExtraOverview.text = overview
                b.tmdbExtraOverview.isVisible = true
            }
            b.root.setOnClickListener { openExtra(row.item, row.addon) }
            itemView.isFocusable = true
            focusCorners()
        }

        private fun openExtra(item: TmdbMedia, addon: StremioAddon?) {
            val type = if (item.type == "tv") "tv" else "movie"
            val intent = when {
                item.id > 0 -> android.content.Intent(activity, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, item.id)
                !item.imdbId.isNullOrBlank() -> android.content.Intent(activity, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                    .putExtra(TmdbDetailsActivity.ARG_IMDB_ID, item.imdbId)
                    .putExtra(TmdbDetailsActivity.ARG_TITLE, item.displayTitle)
                    .apply {
                        addon?.let { putExtra(TmdbDetailsActivity.ARG_SOURCE_ADDON, it.transportUrl) }
                    }
                else -> null
            } ?: return
            activity.startActivity(intent)
            activity.finish()
        }
    }
}
