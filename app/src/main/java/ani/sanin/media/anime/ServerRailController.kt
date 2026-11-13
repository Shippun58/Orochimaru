package ani.sanin.media.anime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.cloudstream.Rpdb
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.cloudstream.TmdbExtras
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.databinding.ItemTmdbServerRowBinding
import ani.sanin.databinding.ItemTmdbStreamsExtraBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
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
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-player server rail: a mini version of the servers screen.
 * Anime mode: source chips + per-source servers (grey containers, top rim, grouped cards).
 * TMDB mode: server chips + quality rows + extras, mirroring the TMDB servers screen.
 */
class ServerRailController(
    private val activity: ExoplayerView,
    private val model: MediaDetailsViewModel,
    private val content: View,
    private val closeButton: ImageButton,
    private val chipsScroll: HorizontalScrollView,
    private val chipsGroup: ChipGroup,
    private val recycler: RecyclerView,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit,
) {

    private val media: Media get() = ExoplayerView.media

    private sealed class Row {
        data class Label(val text: String) : Row()
        data class Header(val name: String, val tag: String?) : Row()
        data class Video(
            val group: Group,
            val extractor: VideoExtractor,
            val idx: Int,
            val isFirst: Boolean,
            val isLast: Boolean,
            val server: VideoServer?,
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

    private var tmdbExtractors: List<VideoExtractor> = emptyList()
    private var tmdbSelectedExtractor = 0
    private var tmdbExtrasResolved = false

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

    fun close() {
        if (!isOpen()) return
        content.animate().translationX(-content.width.toFloat()).setDuration(180)
            .withEndAction {
                content.visibility = View.GONE
                onClosed()
            }
            .start()
    }

    fun isOpen(): Boolean = content.visibility == View.VISIBLE

    fun focusFirst() {
        val focusable = rows.indexOfFirst {
            it is Row.Video || it is Row.TmdbStream || it is Row.Extra
        }
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
        val ep = currentEpisode()
        tmdbExtractors = ep?.extractors.orEmpty().filterNotNull()
        tmdbSelectedExtractor = 0
        buildChips()
        refreshRows()
        val session = tmdbSession ?: return
        if (session.stremioAddon == null) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val recs = resolveTmdbExtras(session)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                tmdbExtrasResolved = true
                tmdbExtras = recs
                refreshRows()
            }
        }
    }

    private var tmdbExtras: List<TmdbMedia> = emptyList()

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
                                renderChipStates()
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
        if (tmdbSession != null) {
            chipsScroll.isVisible = true
            tmdbExtractors.forEachIndexed { index, extractor ->
                val chip = inflater.inflate(
                    R.layout.item_tmdb_chip, chipsGroup, false,
                ) as Chip
                chip.text = cleanServerName(extractor.server.name)
                chip.isCheckable = true
                chip.isClickable = true
                chip.isFocusable = true
                chip.setTextColor(activity.resources.getColorStateList(R.color.chip_text_color))
                chip.isChecked = index == tmdbSelectedExtractor
                chip.setOnClickListener { selectTmdbExtractor(index) }
                FocusEffectUtil.applyFocusListener(chip)
                chipsGroup.addView(chip)
                chipByIndex.add(chip)
            }
        } else {
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
            renderChipStates()
        }
    }

    private fun renderChipStates() {
        chipByIndex.forEachIndexed { index, chip ->
            val group = groups.getOrNull(index) ?: return@forEachIndexed
            chip.text = when (group.state) {
                is GroupState.Loading -> "${group.name} ·"
                is GroupState.Failed -> "${group.name} (failed)"
                is GroupState.Ready -> group.name
            }
        }
    }

    private fun selectTmdbExtractor(index: Int) {
        if (index !in tmdbExtractors.indices) return
        tmdbSelectedExtractor = index
        buildChips()
        refreshRows()
    }

    private fun refreshRows() {
        rows.clear()
        if (tmdbSession != null) {
            refreshTmdbRows()
        } else {
            refreshAnimeRows()
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshAnimeRows() {
        val group = groups.getOrNull(selectedIndex)
        if (group == null) {
            adapter.notifyDataSetChanged()
            return
        }
        when (val state = group.state) {
            is GroupState.Loading -> {}
            is GroupState.Failed -> rows.add(Row.Label(state.reason))
            is GroupState.Ready -> {
                if (state.servers.isEmpty()) {
                    rows.add(Row.Label("Nothing found in ${group.name}"))
                } else if (PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
                    state.servers.forEach { server ->
                        val ex = group.extractors.firstOrNull { it.server.name == server.name }
                        val hasVideos = ex?.videos?.isNotEmpty() == true
                        if (group.extractionDone && !hasVideos) {
                            return@forEach
                        }
                        val (_, _, parsedAudio) = splitServerTitle(server.name)
                        rows.add(Row.Header(cleanServerName(server.name), parsedAudio?.uppercase()
                            ?: server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()))
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
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshTmdbRows() {
        val extractor = tmdbExtractors.getOrNull(tmdbSelectedExtractor)
        if (extractor != null) {
            extractor.videos.forEachIndexed { idx, _ ->
                rows.add(Row.TmdbStream(extractor, idx))
            }
        }
        if (tmdbExtrasResolved && tmdbExtras.isNotEmpty()) {
            val session = tmdbSession
            tmdbExtras.sortedByDescending { it.popularity }.take(12).forEach { rec ->
                rows.add(Row.Extra(rec, session?.stremioAddon))
            }
        }
    }

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

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Extra -> 2
            is Row.Label -> 0
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> RailViewHolder(RailViewHolderType.TEXT, textB = ItemTmdbStreamsTextBinding.inflate(inf, parent, false))
                2 -> RailViewHolder(RailViewHolderType.EXTRA, extraB = ItemTmdbStreamsExtraBinding.inflate(inf, parent, false))
                else -> RailViewHolder(RailViewHolderType.ROW, rowB = ItemTmdbServerRowBinding.inflate(inf, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Label -> holder.bindLabel(row)
                is Row.Header -> holder.bindHeader(row)
                is Row.Video -> holder.bindVideo(row)
                is Row.TmdbStream -> holder.bindTmdbStream(row)
                is Row.Extra -> holder.bindExtra(row)
            }
        }
    }

    private enum class RailViewHolderType { TEXT, ROW, EXTRA }

    private inner class RailViewHolder(
        val type: RailViewHolderType,
        private val textB: ItemTmdbStreamsTextBinding? = null,
        private val rowB: ItemTmdbServerRowBinding? = null,
        private val extraB: ItemTmdbStreamsExtraBinding? = null,
    ) : RecyclerView.ViewHolder(
        textB?.root ?: rowB?.root ?: extraB?.root ?: View(itemViewContext())
    ) {
        private fun itemViewContext(): android.content.Context = activity

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

        fun bindLabel(row: Row.Label) {
            textB?.tmdbStreamsText?.text = row.text
            itemView.isClickable = false
            itemView.isFocusable = false
        }

        /** SubHead-style header above a merged grey card. */
        fun bindHeader(row: Row.Header) {
            val b = rowB ?: return
            b.root.background = null
            b.urlQuality.text = row.name
            b.urlQuality.textSize = 14f
            b.urlQuality.alpha = 0.6f
            b.urlQuality.setTextColor(b.root.context.getThemeColor(
                com.google.android.material.R.attr.colorOnSurface))
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

        fun bindVideo(row: Row.Video) {
            val b = rowB ?: return
            val video = row.extractor.videos.getOrNull(row.idx)
            GlassPill.applyServerRowCard(
                b.root,
                cornerRadiusDp = 14f,
                topRounded = row.isFirst,
                bottomRounded = row.isLast,
                showTopRim = row.isFirst,
            )
            b.urlQuality.text =
                video?.quality?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTextColor(b.root.context.getThemeColor(
                com.google.android.material.R.attr.colorSecondary))
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
            val audio = if (row.server != null) {
                splitServerTitle(row.server.name).third?.uppercase()
                    ?: row.server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()
            } else null
            b.urlTag.text = audio
            b.urlTag.isVisible = !audio.isNullOrBlank()
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener { switchServer(row.group, row.extractor, row.idx) }
            itemView.isFocusable = true
            setRowMargins(
                if (row.isFirst) (if (row.server != null) 6 else 0) else 1,
                0,
            )
            FocusEffectUtil.applyFocusListener(
                itemView,
                floatArrayOf(16f, 16f, 16f, 16f, 16f, 16f, 16f, 16f).map { it * itemView.resources.displayMetrics.density }
                    .toFloatArray(),
            )
        }

        fun bindTmdbStream(row: Row.TmdbStream) {
            val b = rowB ?: return
            val video = row.extractor.videos.getOrNull(row.idx)
            GlassPill.applyServerRowCard(b.root, cornerRadiusDp = 14f, showTopRim = true)
            val quality = video?.quality?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
            b.urlQuality.text = quality
            b.urlQuality.textSize = 15f
            b.urlQuality.alpha = 1f
            b.urlQuality.setTextColor(b.root.context.getThemeColor(
                com.google.android.material.R.attr.colorSecondary))
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
            b.urlTag.isVisible = false
            b.urlSize.isVisible = false
            b.urlSub.isVisible = false
            b.urlDownload.isVisible = false
            b.root.setOnClickListener { switchTmdbStream(row.extractor, row.idx) }
            itemView.isFocusable = true
            FocusEffectUtil.applyFocusListener(
                itemView,
                floatArrayOf(16f, 16f, 16f, 16f, 16f, 16f, 16f, 16f).map { it * itemView.resources.displayMetrics.density }
                    .toFloatArray(),
            )
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
            FocusEffectUtil.applyFocusListener(
                itemView,
                floatArrayOf(16f, 16f, 16f, 16f, 16f, 16f, 16f, 16f).map { it * itemView.resources.displayMetrics.density }
                    .toFloatArray(),
            )
        }
    }
}
