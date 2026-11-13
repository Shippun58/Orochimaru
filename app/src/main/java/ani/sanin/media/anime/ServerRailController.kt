package ani.sanin.media.anime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbMedia
import ani.sanin.cloudstream.TmdbExtras
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.databinding.ItemServerRailRowBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
import ani.sanin.limitedAsyncMap
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.media.anime.getEpisode
import ani.sanin.media.anime.getEpisodeKey
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.HAnimeSources
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.WatchSources
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-player server rail: compact version of the servers screen.
 * Anime mode: source chips + per-source servers, honoring the load-mode pref.
 * TMDB mode: current session streams + extras.
 */
class ServerRailController(
    private val activity: ExoplayerView,
    private val model: MediaDetailsViewModel,
    private val drawer: DrawerLayout,
    private val content: View,
    private val closeButton: ImageButton,
    private val chipsScroll: HorizontalScrollView,
    private val chipsGroup: ChipGroup,
    private val recycler: RecyclerView,
) {

    private val media: Media get() = ExoplayerView.media

    private sealed class Row {
        data class Label(val text: String, val dim: Boolean = true) : Row()
        object ReloadStreams : Row()
        data class ServerHead(val name: String, val tag: String?) : Row()
        data class Video(
            val group: Group,
            val extractor: VideoExtractor,
            val idx: Int,
            val quality: String?,
            val tag: String?,
        ) : Row()

        data class TmdbStream(val extractor: VideoExtractor, val idx: Int) : Row()
        data class Extra(val item: TmdbMedia, val addon: ani.sanin.settings.StremioAddon?) : Row()
    }

    private sealed class GroupState {
        object Loading : GroupState()
        data class Failed(val reason: String) : GroupState()
        data class Ready(val servers: List<VideoServer>) : GroupState()
    }

    private class Group(
        val index: Int,
        val name: String,
        val parser: AnimeParser,
    ) {
        var state: GroupState = GroupState.Loading
        var rank = 2
        var arrivalSeq = Int.MAX_VALUE
        var episode: Episode? = null
        val extractors = mutableListOf<VideoExtractor>()
        var extractionDone = false
    }

    private val rows = mutableListOf<Row>()
    private val groups = mutableListOf<Group>()
    private val chipByIndex = mutableListOf<Chip>()
    private var selectedIndex = 0
    private val adapter = RailAdapter()
    private val groupJobs = mutableMapOf<Int, Job>()
    private var arrivalCounter = 0
    private val sources: WatchSources =
        if (ExoplayerView.media.isAdult) HAnimeSources else AnimeSources
    private val tmdbSession: TmdbStreamResolver.SyntheticSession? by lazy {
        TmdbStreamResolver.sessionFor(media.id)
    }

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        closeButton.nextFocusDownId = R.id.serverRailList
        recycler.nextFocusUpId = R.id.serverDrawerClose
        FocusEffectUtil.applyFocusListener(closeButton)
        closeButton.setOnClickListener { close() }
    }

    fun open() {
        if (rows.isEmpty()) rebuild()
        if (drawer.isDrawerOpen(content)) {
            focusFirst()
        } else {
            drawer.openDrawer(content)
        }
    }

    fun close() {
        drawer.closeDrawer(content)
    }

    fun focusFirst() {
        val focusable = rows.indexOfFirst { it is Row.Video || it is Row.TmdbStream || it is Row.Extra }
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

    private fun rebuild() {
        rows.clear()
        if (tmdbSession != null) {
            buildTmdbRows()
        } else {
            buildAnimeGroups()
        }
        adapter.notifyDataSetChanged()
    }

    // --- TMDB mode ---

    private fun buildTmdbRows() {
        chipsScroll.isVisible = false
        val ep = currentEpisode()
        val extractors = ep?.extractors.orEmpty().filterNotNull()
        rows.add(Row.Label("Streams", dim = false))
        if (extractors.isEmpty()) {
            rows.add(Row.Label("Loading streams…"))
        } else {
            extractors.forEachIndexed { idx, ex ->
                rows.add(Row.TmdbStream(ex, idx))
            }
        }
        rows.add(Row.ReloadStreams)
        val session = tmdbSession ?: return
        if (session.stremioAddon == null) return
        rows.add(Row.Label("Extras", dim = false))
        rows.add(Row.Label("Loading extras…"))
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val recs = resolveTmdbExtras(session)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val idx = rows.indexOfFirst { it is Row.Label && it.text == "Loading extras…" }
                if (idx >= 0) {
                    if (recs.isEmpty()) rows[idx] = Row.Label("No extras found")
                    else {
                        rows.removeAt(idx)
                        recs.sortedByDescending { it.popularity }.take(12).forEach { rec ->
                            rows.add(Row.Extra(rec, session.stremioAddon))
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private suspend fun resolveTmdbExtras(
        session: TmdbStreamResolver.SyntheticSession,
    ): List<TmdbMedia> {
        val addon = session.stremioAddon ?: return emptyList()
        val ep = currentEpisode()
        val season = ep?.extra?.get("season")?.toIntOrNull()
        val epNum = ep?.extra?.get("episode")?.toIntOrNull()
        val result = TmdbStreamResolver.cachedLinks(session.mediaId, session.source.name, season, epNum)
            ?: ((TmdbStreamResolver.resolveStremio(
                addon, session.mediaType, session.mediaId, session.detail, season, epNum,
            ) as? TmdbStreamResolver.StreamResult.Success)
                ?.also { TmdbStreamResolver.cacheLinks(session.mediaId, session.source.name, season, epNum, it) })
            ?: return emptyList()
        val canCard = { link: TmdbStreamResolver.StreamResult.PlayableLink ->
            TmdbExtras.parseStremioTarget(link.externalUrl) != null ||
                TmdbExtras.parseExtraDescription(link.rawDetails ?: link.details)?.isTemplateMatch == true
        }
        val extras = result.links.filter { it.url.isBlank() }.filter { canCard(it) }
        return extras.limitedAsyncMap(4) { link ->
            runCatching { TmdbExtras.resolveExtraMedia(link, session.mediaType) }.getOrNull()
        }.filterNotNull()
            .filterNot { it.id == session.mediaId && it.type == session.mediaType }
            .distinctBy { "${it.type}:${it.id}" }
    }

    private fun currentEpisode(): Episode? {
        val m = media
        val key = m.anime?.selectedEpisode ?: return null
        return m.anime?.episodes?.getEpisode(key)
    }

    // --- Anime mode ---

    private fun buildAnimeGroups() {
        chipsScroll.isVisible = true
        groups.clear()
        chipByIndex.clear()
        arrivalCounter = 0
        sources.list.forEachIndexed { index, lazier ->
            val listName = lazier.name
            if (listName.equals("Local", ignoreCase = true)) return@forEachIndexed
            val parser = runCatching { lazier.get.value as? AnimeParser }.getOrNull()
                ?: return@forEachIndexed
            groups.add(Group(index, listName.ifBlank { parser.name }, parser))
        }
        selectedIndex = groups.indexOfFirst { it.index == (media.selected?.sourceIndex ?: -1) }
            .takeIf { it >= 0 } ?: 0
        buildChips()
        refreshRows()
        lifecycleResolveAll()
    }

    private fun lifecycleResolveAll() {
        activity.lifecycleScope.launch {
            val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
            val targets = if (mode == 1) listOf(selectedIndex) else groups.indices
            if (mode == 1) {
                groups.forEachIndexed { i, g ->
                    if (i != selectedIndex) {
                        groupJobs.remove(i)?.cancel()
                        g.state = GroupState.Loading
                        g.rank = 2
                        g.arrivalSeq = Int.MAX_VALUE
                    }
                }
                buildChips()
                refreshRows()
            }
            Logger.log("SERVER_RAIL: resolveAll mode=$mode targets=$targets")
            targets.forEach { launchGroupResolution(it) }
        }
    }

    private fun launchGroupResolution(index: Int) {
        val group = groups.getOrNull(index) ?: return
        groupJobs.remove(index)?.cancel()
        groupJobs[index] = activity.lifecycleScope.launch(Dispatchers.IO) {
            resolveGroup(group)
        }
    }

    private suspend fun resolveGroup(group: Group) {
        val m = media
        group.extractors.clear()
        val epKey = m.anime?.selectedEpisode ?: return
        val ep = m.anime?.episodes?.getEpisode(epKey)
        try {
            val target: Episode = if (
                group.index == (m.selected?.sourceIndex ?: -1) &&
                ep?.link != null && ep.sEpisode != null
            ) {
                ep
            } else {
                val map = withTimeoutOrNull(45_000) {
                    sources.loadEpisodesFromMedia(group.index, m)
                }.orEmpty()
                val num = ep?.number?.toFloatOrNull()
                map.entries.firstOrNull { it.key.toFloatOrNull() == num }?.value
                    ?: map.entries.firstOrNull { it.key.equals(epKey, true) }?.value
                    ?: throw Exception("Episode not found in ${group.name}")
            }
            group.episode = target
            val preferDub = m.selected?.preferDub ?: PrefManager.getVal(PrefName.PreferDub)
            if (group.index != (m.selected?.sourceIndex ?: -1) &&
                group.parser.isDubAvailableSeparately()
            ) {
                group.parser.selectDub = preferDub
            }
            val servers = withTimeoutOrNull(60_000) {
                target.link?.let { link ->
                    target.sEpisode?.let { sEp ->
                        group.parser.loadVideoServers(link, target.extra, sEp)
                    }
                }
            }.orEmpty()
            group.state = GroupState.Ready(servers)
            group.extractionDone = false
            Logger.log("SERVER_RAIL: '${group.name}' ok ${servers.size} servers")
            if (servers.isNotEmpty()) {
                val link = target.link
                val sEp = target.sEpisode
                if (link != null && sEp != null) {
                    runCatching {
                        group.parser.loadByVideoServers(link, target.extra, sEp, { extractor ->
                            group.extractors.add(extractor)
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                refreshRows()
                            }
                        }, servers)
                    }.onFailure {
                        Logger.log(
                            android.util.Log.ERROR,
                            "SERVER_RAIL: '${group.name}' extractor load failed: ${it.message}"
                        )
                    }
                }
            }
            group.extractionDone = true
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                refreshRows()
            }
        } catch (e: Exception) {
            Logger.log(android.util.Log.ERROR, "SERVER_RAIL: '${group.name}' failed: ${e.message}")
            group.state = GroupState.Failed(e.message ?: "Failed")
        }
        onResolved(group)
    }

    private fun onResolved(group: Group) {
        val ready = (group.state as? GroupState.Ready)?.servers?.isNotEmpty() == true
        group.rank = when {
            ready -> 0
            group.state is GroupState.Failed -> 1
            else -> 2
        }
        if (group.rank == 0) group.arrivalSeq = arrivalCounter++
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val selectedKey = groups.getOrNull(selectedIndex)?.index
            groups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }))
            selectedIndex = groups.indexOfFirst { it.index == selectedKey }
                .takeIf { it >= 0 } ?: selectedIndex
            buildChips()
            refreshRows()
        }
    }

    private fun selectGroup(index: Int) {
        if (index !in groups.indices) return
        val changed = selectedIndex != index
        selectedIndex = index
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault)) {
            media.selected?.sourceIndex = groups[index].index
            model.saveSelected(media.id, media.selected!!)
        }
        buildChips()
        refreshRows()
        if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1 && changed) {
            groups.forEachIndexed { i, g ->
                if (i != index) {
                    groupJobs.remove(i)?.cancel()
                    g.state = GroupState.Loading
                }
            }
            launchGroupResolution(index)
        } else if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1) {
            if (groups[index].state is GroupState.Loading) launchGroupResolution(index)
        }
    }

    private fun buildChips() {
        chipsGroup.removeAllViews()
        chipByIndex.clear()
        val inflater = LayoutInflater.from(activity)
        groups.forEachIndexed { index, group ->
            val chip = inflater.inflate(
                R.layout.item_tmdb_chip, chipsGroup, false,
            ) as Chip
            chip.text = group.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(activity.resources.getColorStateList(R.color.chip_text_color))
            chip.isChecked = index == selectedIndex
            chip.setOnClickListener { selectGroup(index) }
            FocusEffectUtil.applyFocusListener(chip)
            chipsGroup.addView(chip)
            chipByIndex.add(chip)
        }
    }

    private fun refreshRows() {
        rows.clear()
        val group = groups.getOrNull(selectedIndex)
        if (group == null) {
            adapter.notifyDataSetChanged()
            return
        }
        rows.add(Row.Label("${group.name} · servers", dim = false))
        when (val state = group.state) {
            is GroupState.Loading -> rows.add(Row.Label("Loading ${group.name}…"))
            is GroupState.Failed -> rows.add(Row.Label(state.reason))
            is GroupState.Ready -> {
                if (state.servers.isEmpty()) {
                    rows.add(Row.Label("Nothing found in ${group.name}"))
                } else {
                    state.servers.forEach { server ->
                        val ex = group.extractors.firstOrNull { it.server.name == server.name }
                        val hasVideos = ex?.videos?.isNotEmpty() == true
                        if (group.extractionDone && !hasVideos) return@forEach
                        rows.add(Row.ServerHead(cleanServerName(server.name), serverAudioTag(server)))
                        if (hasVideos) {
                            ex.videos.forEachIndexed { idx, video ->
                                rows.add(
                                    Row.Video(
                                        group, ex, idx,
                                        quality = video.quality?.takeIf { it > 0 }?.let { "${it}p" }
                                            ?: "Auto",
                                        tag = serverAudioTag(server),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun cleanServerName(name: String): String {
        val audio = Regex("\\b(sub|dub|softsub|hardsub)\\b", RegexOption.IGNORE_CASE)
        val quality = Regex("\\b(\\d{3,4}\\s*p|auto|unknown)\\b", RegexOption.IGNORE_CASE)
        var cleaned = name
            .replace(audio, " ")
            .replace(quality, " ")
            .replace(Regex("[()\\[\\]]"), " ")
            .replace(Regex("\\s*[-–·|]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '·', '|', ' ')
        if (cleaned.isBlank()) cleaned = name
        return cleaned
    }

    private fun serverAudioTag(server: VideoServer): String? {
        val parsed = Regex("\\b(sub|dub)\\b", RegexOption.IGNORE_CASE)
            .find(server.name)?.groupValues?.getOrNull(1)
        return (parsed?.uppercase()
            ?: server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase())
    }

    // --- actions ---

    private fun switchServer(group: Group, extractor: VideoExtractor, videoIdx: Int) {
        val m = media
        val epKey = m.anime?.selectedEpisode ?: return
        val ep = m.anime?.episodes?.getEpisode(epKey) ?: group.episode ?: return
        if (ep.extractorsSource != group.index) {
            ep.extractors = mutableListOf()
            ep.extractorsSource = group.index
            ep.allStreams = false
        }
        ep.extractors = (ep.extractors ?: mutableListOf()).also { list ->
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
        m.selected?.server = extractor.server.name
        m.selected?.video = videoIdx
        model.saveSelected(m.id, m.selected!!)
        Logger.log(
            "SERVER_RAIL: switch '${m.name}' ep=${ep.number} via ${group.name}/" +
                "${extractor.server.name} video=$videoIdx"
        )
        activity.restartWithServer()
    }

    private fun switchTmdbStream(extractor: VideoExtractor, videoIdx: Int) {
        val m = media
        val ep = currentEpisode() ?: return
        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = videoIdx
        m.selected?.server = extractor.server.name
        m.selected?.video = videoIdx
        model.saveSelected(m.id, m.selected!!)
        Logger.log("SERVER_RAIL: tmdb switch via ${extractor.server.name} video=$videoIdx")
        activity.restartWithServer()
    }

    private fun openExtra(item: TmdbMedia, addon: ani.sanin.settings.StremioAddon?) {
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

    // --- adapter ---

    private inner class RailAdapter : RecyclerView.Adapter<RailViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Label) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val b = ItemTmdbStreamsTextBinding.inflate(inf, parent, false)
                RailViewHolder(b.root, textB = b)
            } else {
                val b = ItemServerRailRowBinding.inflate(inf, parent, false)
                RailViewHolder(b.root, rowB = b)
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Label -> holder.bindLabel(row)
                is Row.ReloadStreams -> holder.bindReload()
                is Row.ServerHead -> holder.bindServerHead(row)
                is Row.Video -> holder.bindVideo(row)
                is Row.TmdbStream -> holder.bindTmdbStream(row)
                is Row.Extra -> holder.bindExtra(row)
            }
        }
    }

    private inner class RailViewHolder(
        root: View,
        private val textB: ItemTmdbStreamsTextBinding? = null,
        private val rowB: ItemServerRailRowBinding? = null,
    ) : RecyclerView.ViewHolder(root) {

        fun bindLabel(row: Row.Label) {
            textB?.tmdbStreamsText?.text = row.text
            itemView.isClickable = false
            itemView.isFocusable = false
        }

        fun bindReload() {
            textB?.tmdbStreamsText?.text = "Reload streams"
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.setOnClickListener {
                val m = media
                TmdbStreamResolver.invalidateLinks(m.id)
                val ep = currentEpisode()
                if (ep != null) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            TmdbStreamResolver.populateSyntheticEpisode(activity, m, ep)
                        }
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            rebuild()
                            focusFirst()
                        }
                    }
                }
            }
            FocusEffectUtil.applyFocusListener(itemView)
        }

        fun bindServerHead(row: Row.ServerHead) {
            val b = rowB ?: return
            b.serverRailRowTitle.text = row.name
            b.serverRailRowTitle.alpha = 0.6f
            val meta = row.tag
            if (meta.isNullOrBlank()) {
                b.serverRailRowMeta.isVisible = false
            } else {
                b.serverRailRowMeta.text = meta
                b.serverRailRowMeta.isVisible = true
            }
            itemView.isClickable = false
            itemView.isFocusable = false
        }

        fun bindVideo(row: Row.Video) {
            val b = rowB ?: return
            b.serverRailRowTitle.text = cleanServerName(row.extractor.server.name)
            b.serverRailRowTitle.alpha = 1f
            val meta = listOfNotNull(row.quality, row.tag).joinToString(" · ")
            if (meta.isBlank()) {
                b.serverRailRowMeta.isVisible = false
            } else {
                b.serverRailRowMeta.text = meta
                b.serverRailRowMeta.isVisible = true
            }
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.setOnClickListener {
                switchServer(row.group, row.extractor, row.idx)
            }
            FocusEffectUtil.applyFocusListener(itemView)
        }

        fun bindTmdbStream(row: Row.TmdbStream) {
            val b = rowB ?: return
            b.serverRailRowTitle.text = cleanServerName(row.extractor.server.name)
            b.serverRailRowTitle.alpha = 1f
            b.serverRailRowMeta.text = "${row.extractor.videos.size} qualities"
            b.serverRailRowMeta.isVisible = true
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.setOnClickListener { switchTmdbStream(row.extractor, row.idx) }
            FocusEffectUtil.applyFocusListener(itemView)
        }

        fun bindExtra(row: Row.Extra) {
            val b = rowB ?: return
            b.serverRailRowTitle.text = row.item.displayTitle
            b.serverRailRowTitle.alpha = 1f
            val year = row.item.releaseDate ?: row.item.firstAirDate
            val meta = listOfNotNull(
                year?.takeIf { it.isNotBlank() }?.substringBefore("-"),
                if (row.item.type == "tv") "TV" else "Movie",
            ).joinToString(" · ")
            if (meta.isBlank()) {
                b.serverRailRowMeta.isVisible = false
            } else {
                b.serverRailRowMeta.text = meta
                b.serverRailRowMeta.isVisible = true
            }
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.setOnClickListener { openExtra(row.item, row.addon) }
            FocusEffectUtil.applyFocusListener(itemView)
        }
    }
}
