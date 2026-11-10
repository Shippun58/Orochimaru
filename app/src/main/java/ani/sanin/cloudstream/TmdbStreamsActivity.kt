package ani.sanin.cloudstream

import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbImage
import ani.sanin.connections.tmdb.TmdbImages
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbSeason
import ani.sanin.databinding.ActivityTmdbStreamsBinding
import ani.sanin.databinding.ItemTmdbStreamsGroupBinding
import ani.sanin.databinding.ItemTmdbStreamsPosterBinding
import ani.sanin.databinding.ItemTmdbStreamsRailBinding
import ani.sanin.databinding.ItemTmdbStreamsSubheadBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
import ani.sanin.databinding.ItemUrlBinding
import ani.sanin.limitedAsyncMap
import ani.sanin.loadImage
import androidx.core.view.isVisible
import ani.sanin.settings.StremioAddon
import ani.sanin.settings.StremioAddonManager
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbStreamsActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_SEASON = "season"
        const val ARG_EPISODE = "episode"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
    }

    private sealed class GroupState {
        object Loading : GroupState()
        data class Failed(val reason: String) : GroupState()
        data class Ready(
            val links: List<TmdbStreamResolver.StreamResult.PlayableLink>,
            val recommendations: List<TmdbMedia> = emptyList(),
            val infos: List<TmdbStreamResolver.StreamResult.PlayableLink> = emptyList(),
        ) : GroupState()
    }

    private class Group(
        val key: String,
        val name: String,
        val logoUrl: String?,
        val source: CsInstalledSource,
        val addon: StremioAddon? = null,
        val pluginLoad: LoadResponse? = null,
    ) {
        var state: GroupState = GroupState.Loading
    }

    private sealed class Row {
        data class Head(val group: Group) : Row()

        data class Stream(
            val link: TmdbStreamResolver.StreamResult.PlayableLink,
            val group: Group,
        ) : Row()

        data class Info(val link: TmdbStreamResolver.StreamResult.PlayableLink) : Row()
        data class Recs(val media: List<TmdbMedia>) : Row()
        data class SubHead(val label: String) : Row()
        data class Text(val text: String) : Row()
    }

    private var mediaId = 0
    private var mediaType = "movie"
    private var season: Int? = null
    private var epNumber: Int? = null
    private var pluginSourceId: String? = null
    private var pluginUrl: String? = null
    private var pluginLoad: LoadResponse? = null
    private val pluginEpisodes = mutableMapOf<Int, List<TmdbEpisode>>()

    private lateinit var streamsBinding: ActivityTmdbStreamsBinding
    private var detail: TmdbDetail? = null

    private val groups = mutableListOf<Group>()
    private val chipByIndex = mutableListOf<Chip>()
    private val rows = mutableListOf<Row>()
    private var selectedIndex = 0

    private val rowsAdapter = RowsAdapter()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, 0)
        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        val seasonArg = intent.getIntExtra(ARG_SEASON, 0)
        val epArg = intent.getIntExtra(ARG_EPISODE, 0)
        pluginSourceId = intent.getStringExtra(ARG_PLUGIN_SOURCE)
        pluginUrl = intent.getStringExtra(ARG_PLUGIN_URL)
        if (seasonArg > 0) season = seasonArg
        if (epArg > 0) epNumber = epArg

        Logger.log(
            "TMDB_STREAMS: open mediaId=$mediaId type=$mediaType S$season E$epNumber " +
                "plugin=${pluginUrl != null}"
        )

        streamsBinding = ActivityTmdbStreamsBinding.inflate(layoutInflater)
        setContentView(streamsBinding.root)
        streamsBinding.tmdbStreamsRows.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        streamsBinding.tmdbStreamsRows.adapter = rowsAdapter
        streamsBinding.tmdbStreamsBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(streamsBinding.tmdbStreamsBack)

        lifecycleScope.launch {
            val pluginDetail = if (pluginUrl != null) {
                withContext(Dispatchers.IO) { loadPlugin(pluginUrl!!) }
            } else null
            if (pluginUrl != null && pluginDetail == null) {
                return@launch
            }
            if (pluginUrl == null) {
                val d = withContext(Dispatchers.IO) { Tmdb.detail(mediaType, mediaId) }
                if (d == null) {
                    snackString("Could not load details")
                    finish()
                    return@launch
                }
                detail = d
            }
            if (isFinishing || isDestroyed) return@launch
            bindHeader(pluginDetail ?: detail!!)
            buildGroups()
            buildChips()
            selectedIndex = 0
            refreshRows()
            resolveAll()
        }
    }

    private fun bindHeader(d: TmdbDetail) {
        val logo = if (PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) {
            Tmdb.logoUrl(d)
        } else null
        if (logo != null) {
            streamsBinding.tmdbStreamsLogo.isVisible = true
            streamsBinding.tmdbStreamsTitle.isVisible = false
            streamsBinding.tmdbStreamsLogo.loadImage(logo)
        } else {
            streamsBinding.tmdbStreamsLogo.isVisible = false
            streamsBinding.tmdbStreamsTitle.isVisible = true
            streamsBinding.tmdbStreamsTitle.text = d.displayTitle
        }
        streamsBinding.tmdbStreamsBackdrop.loadImage(Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780))
        val genres = d.genres.mapNotNull { it.name }.take(3).joinToString(", ")
        val meta = listOfNotNull(
            d.year.takeIf { it.isNotBlank() },
            genres.takeIf { it.isNotBlank() },
            String.format(java.util.Locale.US, "★ %.1f", d.voteAverage).takeIf { d.voteAverage > 0 },
        ).joinToString("  •  ")
        streamsBinding.tmdbStreamsMeta.text = meta
        if (mediaType == "tv") {
            val s = season ?: 1
            lifecycleScope.launch {
                val eps = withContext(Dispatchers.IO) {
                    when {
                        pluginLoad != null -> pluginEpisodes[s].orEmpty()
                        else -> runCatching { Tmdb.episodes("tv", mediaId, s) }.getOrNull().orEmpty()
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                val ep = eps.firstOrNull { it.episodeNumber == epNumber }
                streamsBinding.tmdbStreamsEpisode.text =
                    "S$s · E${epNumber ?: 1}" +
                        (ep?.name?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            }
        } else {
            streamsBinding.tmdbStreamsEpisode.isVisible = false
        }
    }

    private fun buildGroups() {
        groups.clear()
        chipByIndex.clear()
        pluginLoad?.let { load ->
            val source = CsRepos.installed(this).firstOrNull { it.id == pluginSourceId }
            if (source != null) {
                groups.add(Group(source.id, source.name, source.iconUrl, source, pluginLoad = load))
            }
            Logger.log("TMDB_STREAMS: groups total=${groups.size} (plugin mode)")
            return
        }
        val csSources = CsRepos.installed(this)
        csSources.forEach { source ->
            groups.add(Group(source.id, source.name, source.iconUrl, source))
        }
        val stremio = StremioAddonManager.installed()
        stremio.forEach { addon ->
            groups.add(
                Group(
                    StremioAddonManager.sourceId(addon),
                    addon.manifest.name,
                    addon.manifest.logo,
                    stremioCsSource(addon),
                    addon = addon,
                )
            )
        }
        Logger.log(
            "TMDB_STREAMS: groups cs=${csSources.size} stremio=${stremio.size} total=${groups.size}"
        )
    }

    private fun buildChips() {
        streamsBinding.tmdbStreamsChips.removeAllViews()
        chipByIndex.clear()
        val inflater = android.view.LayoutInflater.from(this)
        groups.forEachIndexed { index, group ->
            val chip = inflater.inflate(
                R.layout.item_tmdb_chip,
                streamsBinding.tmdbStreamsChips,
                false,
            ) as Chip
            chip.text = group.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.isChecked = index == selectedIndex
            chip.setOnClickListener { selectGroup(index) }
            chipByIndex.add(chip)
            streamsBinding.tmdbStreamsChips.addView(chip)
        }
        renderChipStates()
    }

    private fun renderChipStates() {
        chipByIndex.forEachIndexed { index, chip ->
            val group = groups.getOrNull(index) ?: return@forEachIndexed
            chip.isChecked = index == selectedIndex
            chip.text = when (group.state) {
                is GroupState.Loading -> "${group.name} ·"
                is GroupState.Failed -> "${group.name} (failed)"
                is GroupState.Ready -> group.name
            }
        }
    }

    private fun selectGroup(index: Int) {
        if (index !in groups.indices) return
        selectedIndex = index
        Logger.log("TMDB_STREAMS: chip -> '${groups[index].name}'")
        renderChipStates()
        refreshRows()
    }

    private fun refreshRows() {
        rows.clear()
        val group = groups.getOrNull(selectedIndex)
        if (group == null) {
            rowsAdapter.notifyDataSetChanged()
            return
        }
        val groupServersPref =
            PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)
        rows.add(Row.Head(group))
        when (val state = group.state) {
            is GroupState.Loading -> rows.add(Row.Text("Loading ${group.name}…"))
            is GroupState.Failed -> rows.add(Row.Text(state.reason))
            is GroupState.Ready -> {
                val playable = state.links.filter { it.url.isNotBlank() }
                if (state.recommendations.isNotEmpty()) rows.add(Row.Recs(state.recommendations))
                state.infos.forEach { rows.add(Row.Info(it)) }
                if (playable.isNotEmpty()) {
                    if (groupServersPref && playable.size > 1) {
                        val byHost = LinkedHashMap<String, MutableList<TmdbStreamResolver.StreamResult.PlayableLink>>()
                        playable.forEach { link ->
                            byHost.getOrPut(link.host.ifBlank { link.label }) { mutableListOf() }.add(link)
                        }
                        if (byHost.size <= 1) {
                            playable.forEach { rows.add(Row.Stream(it, group)) }
                        } else {
                            byHost.forEach { (host, links) ->
                                rows.add(Row.SubHead(host))
                                links.forEach { rows.add(Row.Stream(it, group)) }
                            }
                        }
                    } else {
                        playable.forEach { rows.add(Row.Stream(it, group)) }
                    }
                }
                if (state.recommendations.isEmpty() && state.infos.isEmpty() && playable.isEmpty()) {
                    rows.add(Row.Text("Nothing found in ${group.name}"))
                }
            }
        }
        rowsAdapter.notifyDataSetChanged()
        streamsBinding.tmdbStreamsRows.scrollToPosition(0)
        Logger.log(
            "TMDB_STREAMS: rows for '${group.name}' count=${rows.size} state=${stateLabel(group.state)}"
        )
    }

    private fun stateLabel(state: GroupState): String = when (state) {
        is GroupState.Loading -> "loading"
        is GroupState.Failed -> "failed"
        is GroupState.Ready -> {
            if (state.links.any { it.url.isNotBlank() }) "ready" else "extras"
        }
    }

    private suspend fun resolveAll() {
        val d = detail ?: return
        val csGroups = groups.filter { it.addon == null && it.pluginLoad == null }
        val stremioGroups = groups.filter { it.addon != null }
        val stremioStreamGroups = stremioGroups.filter { it.addon?.extrasOnly != true }
        val stremioExtrasGroups = stremioGroups.filter { it.addon?.extrasOnly == true }
        val pluginGroups = groups.filter { it.pluginLoad != null }
        Logger.log(
            "TMDB_STREAMS: resolveAll cs=${csGroups.size} stremioStream=${stremioStreamGroups.size} " +
                "stremioExtras=${stremioExtrasGroups.size} plugin=${pluginGroups.size}"
        )
        val stage1 = pluginGroups.map { g ->
            lifecycleScope.launch(Dispatchers.IO) { resolvePluginGroup(g) }
        } + stremioStreamGroups.map { g ->
            lifecycleScope.launch(Dispatchers.IO) { resolveStremioGroup(g, d) }
        }
        stage1.forEach { it.join() }
        for (group in csGroups) {
            if (isFinishing || isDestroyed) return
            resolveCsGroup(group)
        }
        val stage3 = stremioExtrasGroups.map { g ->
            lifecycleScope.launch(Dispatchers.IO) { resolveStremioGroup(g, d) }
        }
        stage3.forEach { it.join() }
    }

    private suspend fun resolveCsGroup(group: Group) {
        val d = detail ?: return
        val cached = TmdbStreamResolver.cachedLinks(mediaId, group.source.name, season, epNumber)
        if (cached != null) {
            Logger.log("TMDB_STREAMS: '${group.name}' cached ${cached.links.size} links")
            group.state = GroupState.Ready(cached.links, emptyList(), emptyList())
            onResolved(group)
            return
        }
        val result = TmdbStreamResolver.resolveStreams(this, group.source, d, season, epNumber)
        when (result) {
            is TmdbStreamResolver.StreamResult.Success -> {
                Logger.log(
                    "TMDB_STREAMS: '${group.name}' ok ${result.links.size} links " +
                        "playable=${result.links.count { it.url.isNotBlank() }}"
                )
                TmdbStreamResolver.cacheLinks(mediaId, group.source.name, season, epNumber, result)
                group.state = GroupState.Ready(result.links, emptyList(), emptyList())
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(android.util.Log.ERROR, "TMDB_STREAMS: '${group.name}' failed: ${result.message}")
                group.state = GroupState.Failed(result.message)
            }
        }
        onResolved(group)
    }

    private suspend fun resolveStremioGroup(group: Group, d: TmdbDetail) {
        val addon = group.addon ?: return
        val result = TmdbStreamResolver.resolveStremio(addon, mediaType, mediaId, d, season, epNumber)
        when (result) {
            is TmdbStreamResolver.StreamResult.Success -> {
                val external = result.links.filter { it.url.isBlank() }
                if (external.isEmpty()) {
                    Logger.log(
                        "TMDB_STREAMS: '${group.name}' ok ${result.links.size} links " +
                            "playable=${result.links.count { it.url.isNotBlank() }}"
                    )
                    TmdbStreamResolver.cacheLinks(mediaId, group.name, season, epNumber, result)
                    group.state = GroupState.Ready(result.links, emptyList(), emptyList())
                    onResolved(group)
                    return
                }
                val targets = external.mapNotNull { parseStremioTarget(it.externalUrl) }.distinct()
                val infos = external.filter { parseStremioTarget(it.externalUrl) == null }
                val recommendations = targets.limitedAsyncMap(4) { (type, imdb) ->
                    runCatching { Tmdb.findByImdb(imdb, type)?.copy(mediaType = type) }.getOrNull()
                }.filterNotNull()
                    .filterNot { it.id == mediaId && it.type == mediaType }
                    .distinctBy { "${it.type}:${it.id}" }
                Logger.log(
                    "TMDB_STREAMS: '${group.name}' extras recs=${recommendations.size} infos=${infos.size}"
                )
                group.state = GroupState.Ready(result.links, recommendations, infos)
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(
                    android.util.Log.ERROR,
                    "TMDB_STREAMS: '${group.name}' failed: ${result.message}"
                )
                group.state = GroupState.Failed(result.message)
            }
        }
        onResolved(group)
    }

    private suspend fun resolvePluginGroup(group: Group) {
        val load = group.pluginLoad ?: return
        val result = TmdbStreamResolver.resolvePluginStreams(this, group.source, load, season, epNumber)
        when (result) {
            is TmdbStreamResolver.StreamResult.Success -> {
                Logger.log(
                    "TMDB_STREAMS: '${group.name}' ok ${result.links.size} links " +
                        "playable=${result.links.count { it.url.isNotBlank() }}"
                )
                TmdbStreamResolver.cacheLinks(mediaId, group.name, season, epNumber, result)
                group.state = GroupState.Ready(result.links, emptyList(), emptyList())
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(android.util.Log.ERROR, "TMDB_STREAMS: '${group.name}' failed: ${result.message}")
                group.state = GroupState.Failed(result.message)
            }
        }
        onResolved(group)
    }

    private fun onResolved(group: Group) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            renderChipStates()
            if (groups.getOrNull(selectedIndex) === group) {
                refreshRows()
            }
        }
    }

    private fun play(link: TmdbStreamResolver.StreamResult.PlayableLink, group: Group) {
        val state = group.state as? GroupState.Ready ?: return
        val d = detail ?: return
        val playable = state.links.filter { it.url.isNotBlank() }
        if (playable.isEmpty()) return
        saveLastPlayed()
        Logger.log("TMDB_STREAMS: play '${d.displayTitle}' via ${group.name} label='${link.label}'")
        val overrideEpisodes = if (group.pluginLoad != null) {
            if (mediaType == "tv") {
                TmdbStreamResolver.pluginEpisodeShells(mediaId, pluginEpisodes)
            } else {
                TmdbStreamResolver.pluginMovieShell(mediaId, d)
            }
        } else null
        lifecycleScope.launch(Dispatchers.IO) {
            TmdbStreamResolver.launchPlayer(
                this@TmdbStreamsActivity,
                mediaId,
                mediaType,
                d,
                group.source,
                season,
                epNumber,
                playable,
                link.label,
                episodesOverride = overrideEpisodes,
                load = group.pluginLoad,
                stremioAddon = group.addon,
            )
        }
    }

    private fun saveLastPlayed() {
        PrefManager.setCustomVal("tmdb_last_${mediaId}", "${season ?: 1}:${epNumber ?: 1}")
    }

    // --- plugin loading (mirrors watch fragment) ---

    private suspend fun loadPlugin(url: String): TmdbDetail? {
        val source = CsRepos.installed(this).firstOrNull { it.id == pluginSourceId } ?: run {
            snackString("Plugin not installed anymore")
            finish()
            return null
        }
        val api = withContext(Dispatchers.IO) {
            CsRuntime.apisFor(this@TmdbStreamsActivity, source).firstOrNull()
        } ?: run {
            snackString("Could not load ${source.name}")
            finish()
            return null
        }
        val load = withContext(Dispatchers.IO) {
            runCatching { api.load(url) }.getOrNull()
        }
        if (load == null) {
            Logger.log(android.util.Log.ERROR, "TMDB_STREAMS: plugin load null for $url")
            snackString("Plugin returned nothing for this title")
            finish()
            return null
        }
        pluginLoad = load
        Logger.log(
            "TMDB_STREAMS: plugin load -> ${load.javaClass.simpleName} '${load.name}' (api=${load.apiName})"
        )

        val poster = load.posterUrl
        val backdrop = load.backgroundPosterUrl ?: load.posterUrl
        val rating = load.score?.toFloat(10)?.toDouble() ?: 0.0
        val genres = load.tags.orEmpty().map { TmdbGenre(0, it) }
        val year = load.year?.toString()
        val images = load.logoUrl?.let { TmdbImages(logos = listOf(TmdbImage(it))) }

        val d: TmdbDetail
        if (load is TvSeriesLoadResponse) {
            mediaType = "tv"
            val numbers = load.episodes.mapNotNull { it.season }.distinct().sorted().ifEmpty { listOf(1) }
            val names = load.seasonNames.orEmpty().associate { it.season to it.name }
            val seasons = numbers.map { num ->
                TmdbSeason(
                    id = num,
                    name = names[num],
                    seasonNumber = num,
                    episodeCount = load.episodes.count { (it.season ?: 1) == num }
                )
            }
            pluginEpisodes.clear()
            seasons.forEach { s ->
                pluginEpisodes[s.seasonNumber] = load.episodes
                    .filter { (it.season ?: 1) == s.seasonNumber }
                    .map { toPluginEpisode(it) }
            }
            if (season == null) season = numbers.firstOrNull()
            d = TmdbDetail(
                id = mediaId,
                name = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                firstAirDate = year?.let { "$it-01-01" },
                numberOfSeasons = seasons.size,
                genres = genres,
                images = images,
                seasons = seasons
            )
        } else {
            mediaType = "movie"
            season = null
            epNumber = null
            d = TmdbDetail(
                id = mediaId,
                title = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                releaseDate = year?.let { "$it-01-01" },
                genres = genres,
                images = images
            )
        }
        detail = d
        return d
    }

    private fun toPluginEpisode(ep: com.lagradost.cloudstream3.Episode): TmdbEpisode = TmdbEpisode(
        id = ep.data.hashCode(),
        name = ep.name,
        episodeNumber = ep.episode ?: 0,
        seasonNumber = ep.season ?: 1,
        stillPath = ep.posterUrl,
        airDate = formatEpochDate(ep.date),
        voteAverage = 0.0
    )

    private fun formatEpochDate(epoch: Long?): String? {
        if (epoch == null || epoch <= 0L) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epoch }
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    // --- shared helpers (mirror watch fragment) ---

    private fun stremioCsSource(addon: StremioAddon): CsInstalledSource = CsInstalledSource(
        id = StremioAddonManager.sourceId(addon),
        name = addon.manifest.name,
        version = 1,
        type = "movie",
        lang = "en",
        url = addon.transportUrl,
        repoUrl = "",
        iconUrl = addon.manifest.logo,
    )

    private fun parseStremioTarget(url: String?): Pair<String, String>? {
        if (url == null || !url.startsWith("stremio://", ignoreCase = true)) return null
        val segments = android.net.Uri.parse(url).pathSegments
        val rawType = segments.getOrNull(1) ?: return null
        val rawId = segments.getOrNull(2) ?: return null
        val type = if (rawType.equals("series", true) || rawType.equals("tv", true)) "tv" else "movie"
        val imdbId = rawId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        return type to imdbId
    }

    private fun openMedia(media: TmdbMedia) {
        if (media.id <= 0) return
        val type = if (media.type == "tv") "tv" else "movie"
        Logger.log("TMDB_STREAMS: opening extras item type=$type id=${media.id} '${media.displayTitle}'")
        startActivity(
            android.content.Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun openExternal(link: TmdbStreamResolver.StreamResult.PlayableLink) {
        val url = link.externalUrl ?: return
        if (!url.startsWith("stremio://", ignoreCase = true)) {
            runCatching {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                )
            }.onFailure {
                Logger.log("TMDB_STREAMS: external open failed url=$url error=${it.message}")
                snackString("No app can open this link")
            }
            return
        }
        val target = parseStremioTarget(url) ?: run {
            Logger.log("TMDB_STREAMS: invalid stremio link url=$url")
            return
        }
        val (typeHint, imdbId) = target
        Logger.log("TMDB_STREAMS: resolving stremio link type=$typeHint imdb=$imdbId")
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { Tmdb.findByImdb(imdbId, typeHint) }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (found == null) {
                Logger.log("TMDB_STREAMS: stremio link unresolved id=$imdbId")
                snackString("Could not resolve $imdbId on TMDB")
                return@launch
            }
            startActivity(
                android.content.Intent(this@TmdbStreamsActivity, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, found.type)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, found.id)
            )
        }
    }

    // --- rows RecyclerView ---

    private inner class RowsAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Head -> 0
            is Row.Stream -> 1
            is Row.Info -> 2
            is Row.Recs -> 3
            is Row.SubHead -> 4
            is Row.Text -> 5
        }

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int
        ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val inf = android.view.LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeadVH(ItemTmdbStreamsGroupBinding.inflate(inf, parent, false))
                1 -> ItemVH(ItemUrlBinding.inflate(inf, parent, false))
                2 -> ItemVH(ItemUrlBinding.inflate(inf, parent, false))
                3 -> RailVH(ItemTmdbStreamsRailBinding.inflate(inf, parent, false))
                4 -> SubHeadVH(ItemTmdbStreamsSubheadBinding.inflate(inf, parent, false))
                else -> TextVH(ItemTmdbStreamsTextBinding.inflate(inf, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            position: Int
        ) {
            when (val row = rows[position]) {
                is Row.Head -> (holder as HeadVH).bind(row.group)
                is Row.Stream -> (holder as ItemVH).bindStream(row.link, row.group)
                is Row.Info -> (holder as ItemVH).bindInfo(row.link)
                is Row.Recs -> (holder as RailVH).bind(row.media)
                is Row.SubHead -> (holder as SubHeadVH).bind(row.label)
                is Row.Text -> (holder as TextVH).bind(row.text)
            }
        }

        private inner class SubHeadVH(val binding: ItemTmdbStreamsSubheadBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = false
                itemView.isClickable = false
            }

            fun bind(label: String) {
                binding.tmdbStreamsSubHead.text = label
            }
        }

        private inner class HeadVH(val binding: ItemTmdbStreamsGroupBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = false
            }

            fun bind(group: Group) {
                binding.tmdbStreamsGroupLogo.loadImage(group.logoUrl)
                binding.tmdbStreamsGroupName.text = group.name
                binding.tmdbStreamsGroupState.text = when (val st = group.state) {
                    is GroupState.Loading -> "Loading…"
                    is GroupState.Failed -> "Failed — ${st.reason}"
                    is GroupState.Ready -> stateSummary(st)
                }
            }
        }

        private inner class ItemVH(val binding: ItemUrlBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = true
                val r = 16f * itemView.resources.displayMetrics.density
                FocusEffectUtil.applyFocusListener(
                    itemView,
                    floatArrayOf(r, r, r, r, r, r, r, r),
                )
            }

            fun bindStream(link: TmdbStreamResolver.StreamResult.PlayableLink, group: Group) {
                val (quality, hint) = splitStreamLabel(link.label)
                binding.urlQuality.text = quality
                val note = listOfNotNull(
                    link.host.takeIf { it.isNotBlank() },
                    hint,
                ).joinToString("  •  ")
                if (note.isBlank()) {
                    binding.urlNote.isVisible = false
                } else {
                    binding.urlNote.text = note
                    binding.urlNote.isVisible = true
                }
                binding.urlSize.isVisible = false
                binding.urlSub.isVisible = false
                binding.urlDownload.isVisible = false
                binding.root.setOnClickListener { play(link, group) }
            }

            fun bindInfo(link: TmdbStreamResolver.StreamResult.PlayableLink) {
                binding.urlQuality.text = link.label
                val note = link.details?.lines()?.firstOrNull()?.takeIf(String::isNotBlank)
                if (note.isNullOrBlank()) {
                    binding.urlNote.isVisible = false
                } else {
                    binding.urlNote.text = note
                    binding.urlNote.isVisible = true
                }
                binding.urlSize.isVisible = false
                binding.urlSub.isVisible = false
                binding.urlDownload.isVisible = false
                if (link.externalUrl != null) {
                    binding.root.setOnClickListener { openExternal(link) }
                } else {
                    binding.root.setOnClickListener(null)
                    itemView.isClickable = false
                }
            }
        }

        private inner class RailVH(val binding: ItemTmdbStreamsRailBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            fun bind(media: List<TmdbMedia>) {
                val rail = binding.tmdbStreamsRail
                rail.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    rail.context,
                    androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                    false,
                )
                rail.adapter = PosterAdapter(media)
            }
        }

        private inner class TextVH(val binding: ItemTmdbStreamsTextBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = false
                itemView.isClickable = false
            }

            fun bind(text: String) {
                binding.tmdbStreamsText.text = text
            }
        }
    }

    private inner class PosterAdapter(
        private val items: List<TmdbMedia>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PosterAdapter.PosterVH>() {

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int
        ): PosterVH = PosterVH(
            ItemTmdbStreamsPosterBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(
            holder: PosterVH,
            position: Int
        ) {
            val m = items[position]
            holder.binding.tmdbStreamsPoster.loadImage(Tmdb.imageUrl(m.posterPath ?: m.backdropPath, 300))
            holder.binding.tmdbStreamsPosterTitle.text = m.displayTitle
            val meta = listOfNotNull(
                m.year.takeIf { it.isNotBlank() },
                String.format(java.util.Locale.US, "%.1f", m.voteAverage).takeIf { m.voteAverage > 0 },
            ).joinToString("  •  ")
            if (meta.isBlank()) {
                holder.binding.tmdbStreamsPosterMeta.isVisible = false
            } else {
                holder.binding.tmdbStreamsPosterMeta.text = meta
                holder.binding.tmdbStreamsPosterMeta.isVisible = true
            }
            holder.binding.root.setOnClickListener { openMedia(m) }
            FocusEffectUtil.applyFocusListener(holder.binding.root)
        }

        private inner class PosterVH(val binding: ItemTmdbStreamsPosterBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
    }

    private fun stateSummary(status: GroupState.Ready): String {
        val playable = status.links.count { it.url.isNotBlank() }
        val parts = mutableListOf<String>()
        if (playable > 0) parts.add("$playable links")
        if (status.recommendations.isNotEmpty()) parts.add("${status.recommendations.size} recs")
        if (status.infos.isNotEmpty()) parts.add("${status.infos.size} info")
        return if (parts.isEmpty()) "Empty" else parts.joinToString(" · ")
    }

    private fun splitStreamLabel(label: String): Pair<String, String?> {
        val idx = label.indexOf(" · ")
        return if (idx > 0) {
            label.substring(0, idx) to label.substring(idx + 3)
        } else {
            label to null
        }
    }
}
