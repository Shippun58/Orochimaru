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
import ani.sanin.databinding.ActivityTmdbServersBinding
import ani.sanin.databinding.ItemTmdbStreamsGroupBinding
import ani.sanin.databinding.ItemTmdbStreamsDetailCardBinding
import ani.sanin.getThemeColor
import ani.sanin.databinding.ItemTmdbStreamsRailBinding
import ani.sanin.databinding.ItemTmdbStreamsSubheadBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
import ani.sanin.databinding.ItemTmdbServerRowBinding
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
import android.widget.PopupMenu

class TmdbServersActivity : androidx.appcompat.app.AppCompatActivity() {

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
        var pluginLoad: LoadResponse? = null,
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

    private lateinit var serversBinding: ActivityTmdbServersBinding
    private var detail: TmdbDetail? = null

    private val groups = mutableListOf<Group>()
    private val chipByIndex = mutableListOf<Chip>()
    private val rows = mutableListOf<Row>()
    private var selectedIndex = 0

    private val rowsAdapter = RowsAdapter()
    private var headerCollapseMax = 0
    private val groupJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

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
            "TMDB_SERVERS: open mediaId=$mediaId type=$mediaType S$season E$epNumber " +
                "plugin=${pluginUrl != null}"
        )

        serversBinding = ActivityTmdbServersBinding.inflate(layoutInflater)
        setContentView(serversBinding.root)
        serversBinding.tmdbStreamsRows.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        serversBinding.tmdbStreamsRows.isNestedScrollingEnabled = false
        serversBinding.tmdbStreamsRows.adapter = rowsAdapter
        serversBinding.tmdbStreamsBack.setOnClickListener { finish() }
        serversBinding.tmdbServersRefreshAll.setOnClickListener { refreshAll() }
        serversBinding.tmdbServersMenu.setOnClickListener { showLoadModeMenu(it) }
        FocusEffectUtil.applyFocusListener(serversBinding.tmdbStreamsBack)
        FocusEffectUtil.applyFocusListener(serversBinding.tmdbServersRefreshAll)
        FocusEffectUtil.applyFocusListener(serversBinding.tmdbServersMenu)
        serversBinding.tmdbServersMakeDefault.isChecked =
            PrefManager.getVal<Boolean>(PrefName.MakeDefault)
        serversBinding.tmdbServersMakeDefault.setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.MakeDefault, checked)
            if (!checked) PrefManager.setCustomVal("tmdb_source_$mediaId", "")
        }
        serversBinding.tmdbServersScroll.post { setupCollapse() }

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
            detail = pluginDetail ?: detail
            if (isFinishing || isDestroyed) return@launch
        bindHeader(detail!!)
        buildGroups()
        selectedIndex = applyDefaultSelection()
        buildChips()
        refreshRows()
        resolveAll()
        }
    }

    private fun setupCollapse() {
        val scroll = serversBinding.tmdbServersScroll
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateCollapse(scrollY)
        }
        serversBinding.tmdbServersScroll.post { updateCollapse(serversBinding.tmdbServersScroll.scrollY) }
    }

    private fun updateCollapse(scrollY: Int) {
        val scroll = serversBinding.tmdbServersScroll
        val sticky = serversBinding.tmdbServersStickyBlock
        val topBar = serversBinding.tmdbServersTopBar
        val backdrop = serversBinding.tmdbStreamsBackdrop
        val stickyTop = sticky.top + scroll.scrollY
        headerCollapseMax = (stickyTop - topBar.height).coerceAtLeast(0)
        val progress = if (headerCollapseMax > 0) {
            (scrollY.toFloat() / headerCollapseMax).coerceIn(0f, 1f)
        } else 0f
        val pinned = (scrollY - headerCollapseMax).coerceAtLeast(0)
        sticky.translationY = -pinned.toFloat()
        backdrop.translationY = scrollY * 0.35f
        serversBinding.tmdbServersCompactLogo.alpha = progress
        topBar.background?.alpha = (progress * 255).toInt().coerceIn(0, 255)
        val fadingViews = listOf(
            serversBinding.tmdbStreamsLogo,
            serversBinding.tmdbStreamsTitle,
            serversBinding.tmdbStreamsEpisode,
            serversBinding.tmdbStreamsRating,
            serversBinding.tmdbStreamsMeta,
            serversBinding.tmdbStreamsOverview,
        )
        fadingViews.forEach { it.alpha = 1f - progress }
    }

    private fun bindHeader(d: TmdbDetail) {
        val logo = if (PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) {
            Tmdb.logoUrl(d)
        } else null
        if (logo != null) {
            serversBinding.tmdbStreamsLogo.isVisible = true
            serversBinding.tmdbStreamsTitle.isVisible = false
            serversBinding.tmdbStreamsLogo.loadImage(logo)
            serversBinding.tmdbServersCompactLogo.loadImage(logo)
        } else {
            serversBinding.tmdbStreamsLogo.isVisible = false
            serversBinding.tmdbStreamsTitle.isVisible = true
            serversBinding.tmdbStreamsTitle.text = d.displayTitle
        }
        Tmdb.logoUrl(d)?.takeIf { logo != null }?.let { serversBinding.tmdbServersCompactLogo.loadImage(it) }
            ?: Tmdb.imageUrl(d.posterPath, 342)?.let { serversBinding.tmdbServersCompactLogo.loadImage(it) }
        applyThemeGradient()

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
                bindEpisode(ep, d)
            }
        } else {
            bindEpisode(null, d)
        }
    }

    private fun bindEpisode(ep: TmdbEpisode?, d: TmdbDetail) {
        if (ep != null) {
            val still = runCatching { Tmdb.imageUrl(ep.stillPath, 780) }.getOrNull()
            serversBinding.tmdbStreamsBackdrop.loadImage(
                still ?: Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780)
            )
            serversBinding.tmdbStreamsEpisode.isVisible = true
            serversBinding.tmdbStreamsEpisode.text =
                "Ssn${season ?: 1} Ep${epNumber ?: 1}" +
                    (ep.name?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            val rating = ep.voteAverage.takeIf { it > 0 } ?: d.voteAverage.takeIf { it > 0 }
            bindRating(rating)
            bindMeta(ep.airDate, ep.runtime ?: d.runtimeMinutes)
            val overview = ep.overview?.takeIf { it.isNotBlank() } ?: d.overview
            if (overview.isNullOrBlank()) {
                serversBinding.tmdbStreamsOverview.isVisible = false
            } else {
                serversBinding.tmdbStreamsOverview.isVisible = true
                serversBinding.tmdbStreamsOverview.text = overview
            }
        } else {
            serversBinding.tmdbStreamsBackdrop.loadImage(Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780))
            bindRating(d.voteAverage.takeIf { it > 0 })
            bindMeta(d.releaseDate ?: d.firstAirDate, d.runtimeMinutes)
            val overview = d.overview?.takeIf { it.isNotBlank() }
            if (overview == null) {
                serversBinding.tmdbStreamsOverview.isVisible = false
            } else {
                serversBinding.tmdbStreamsOverview.isVisible = true
                serversBinding.tmdbStreamsOverview.text = overview
            }
        }
        serversBinding.tmdbServersScroll.post {
            headerCollapseMax = (serversBinding.tmdbStreamsBackdrop.height.coerceAtLeast(1))
            updateCollapse(serversBinding.tmdbServersScroll.scrollY)
        }
        Logger.log(
            "TMDB_SERVERS: header ep=${ep != null} title='${serversBinding.tmdbStreamsEpisode.text}'"
        )
    }

    private fun bindRating(rating: Double?) {
        if (rating == null || rating <= 0) {
            serversBinding.tmdbStreamsRating.isVisible = false
            return
        }
        serversBinding.tmdbStreamsRating.isVisible = true
        serversBinding.tmdbStreamsRating.text = String.format(java.util.Locale.US, "★ %.1f", rating)
    }

    private fun bindMeta(airRelease: String?, runtimeMinutes: Int?) {
        val parts = mutableListOf<String>()
        airRelease?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val parsed = java.time.LocalDate.parse(it)
                String.format(
                    java.util.Locale.US,
                    "%02d/%02d/%d",
                    parsed.dayOfMonth,
                    parsed.monthValue,
                    parsed.year,
                )
            }.getOrNull() ?: it
        }?.let { parts.add(it) }
        runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
            val h = minutes / 60
            val m = minutes % 60
            parts.add(
                when {
                    h > 0 && m > 0 -> "${h}hr ${m}minutes"
                    h > 0 -> "${h}hr"
                    else -> "${m}minutes"
                }
            )
        }
        if (parts.isEmpty()) {
            serversBinding.tmdbStreamsMeta.isVisible = false
        } else {
            serversBinding.tmdbStreamsMeta.isVisible = true
            serversBinding.tmdbStreamsMeta.text = parts.joinToString("  ·  ")
        }
    }

    private fun applyThemeGradient() {
        val surface = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb(
                    (0.65f * 255).toInt(),
                    surface.redBits(), surface.greenBits(), surface.blueBits(),
                ),
                android.graphics.Color.argb(255, surface.redBits(), surface.greenBits(), surface.blueBits()),
            ),
        )
        serversBinding.tmdbStreamsScrim.background = gradient
        serversBinding.tmdbServersDivider.background = android.graphics.drawable.ColorDrawable(onSurface)
        serversBinding.tmdbServersCompactLogo.also { it.alpha = 0f }
        val topBarBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(
                android.graphics.Color.argb(
                    (0.95f * 255).toInt(),
                    surface.redBits(), surface.greenBits(), surface.blueBits(),
                ),
            )
        }
        topBarBg.alpha = 0
        serversBinding.tmdbServersTopBar.background = topBarBg
    }

    private fun Int.redBits(): Int = (this shr 16) and 0xFF
    private fun Int.greenBits(): Int = (this shr 8) and 0xFF
    private fun Int.blueBits(): Int = this and 0xFF

    private fun buildGroups() {
        groups.clear()
        chipByIndex.clear()
        pluginLoad?.let { load ->
            val source = CsRepos.installed(this).firstOrNull { it.id == pluginSourceId }
            if (source != null) {
                groups.add(Group(source.id, source.name, source.iconUrl, source, pluginLoad = load))
            }
            Logger.log("TMDB_SERVERS: groups total=${groups.size} (plugin mode)")
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
            "TMDB_SERVERS: groups cs=${csSources.size} stremio=${stremio.size} total=${groups.size}"
        )
    }

    private fun buildChips() {
        serversBinding.tmdbStreamsChips.removeAllViews()
        chipByIndex.clear()
        val inflater = android.view.LayoutInflater.from(this)
        groups.forEachIndexed { index, group ->
            val chip = inflater.inflate(
                R.layout.item_tmdb_chip,
                serversBinding.tmdbStreamsChips,
                false,
            ) as Chip
            chip.text = group.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_text_color)
            )
            chip.isChecked = index == selectedIndex
            chip.setOnClickListener { selectGroup(index) }
            FocusEffectUtil.applyFocusListener(chip)
            chipByIndex.add(chip)
            serversBinding.tmdbStreamsChips.addView(chip)
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
        Logger.log("TMDB_SERVERS: chip -> '${groups[index].name}'")
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
        serversBinding.tmdbStreamsRows.scrollToPosition(0)
        Logger.log(
            "TMDB_SERVERS: rows for '${group.name}' count=${rows.size} state=${stateLabel(group.state)}"
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
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        val targets = if (mode == 1) listOf(selectedIndex) else groups.indices
        if (mode == 1) {
            groups.forEachIndexed { i, g ->
                if (i != selectedIndex) {
                    groupJobs.remove(i)?.cancel()
                    g.state = GroupState.Loading
                }
            }
        }
        Logger.log("TMDB_SERVERS: resolveAll mode=$mode targets=$targets")
        targets.forEach { launchGroupResolution(it) }
        groupJobs.values.forEach { it.join() }
    }

    private fun showLoadModeMenu(anchor: android.view.View) {
        val menu = android.widget.PopupMenu(this, anchor, android.view.Gravity.END)
        menu.menuInflater.inflate(R.menu.menu_tmdb_servers_load, menu.menu)
        val current = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        menu.menu.findItem(R.id.tmdbServersLoadModeParallel).isChecked = current == 0
        menu.menu.findItem(R.id.tmdbServersLoadModeOnSelect).isChecked = current == 1
        menu.setOnMenuItemClickListener { item ->
            val mode = when (item.itemId) {
                R.id.tmdbServersLoadModeParallel -> 0
                R.id.tmdbServersLoadModeOnSelect -> 1
                else -> return@setOnMenuItemClickListener true
            }
            if (mode != current) {
                PrefManager.setVal(PrefName.TmdbServersLoadMode, mode)
                lifecycleScope.launch { resolveAll() }
            }
            true
        }
        menu.show()
    }

    private fun launchGroupResolution(index: Int) {
        val group = groups.getOrNull(index) ?: return
        val d = detail ?: return
        groupJobs.remove(index)?.cancel()
        groupJobs[index] = lifecycleScope.launch(Dispatchers.IO) {
            when {
                group.pluginLoad != null -> resolvePluginGroup(group)
                group.addon != null -> resolveStremioGroup(group, d)
                else -> resolveCsGroup(group)
            }
        }
    }

    private fun applyDefaultSelection(): Int {
        val savedId = PrefManager.getNullableCustomVal("tmdb_source_$mediaId", null, String::class.java)
        if (!savedId.isNullOrEmpty()) {
            val idx = groups.indexOfFirst { it.key.equals(savedId, ignoreCase = true) }
            if (idx >= 0) return idx
        }
        if (pluginSourceId != null) {
            val idx = groups.indexOfFirst { it.pluginLoad != null }
            if (idx >= 0) return idx
        }
        return 0
    }

    private fun saveDefaultSelection(index: Int) {
        val group = groups.getOrNull(index) ?: return
        PrefManager.setCustomVal("tmdb_source_$mediaId", group.key)
    }

    private fun selectGroup(index: Int) {
        if (index !in groups.indices) return
        val changed = selectedIndex != index
        selectedIndex = index
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault)) saveDefaultSelection(index)
        renderChipStates()
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

    private fun refreshAll() {
        Logger.log("TMDB_SERVERS: refresh all")
        groupJobs.values.forEach { it.cancel() }
        groupJobs.clear()
        TmdbStreamResolver.invalidateLinks(mediaId)
        groups.forEach { it.state = GroupState.Loading }
        renderChipStates()
        refreshRows()
        lifecycleScope.launch {
            if (pluginUrl != null) {
                val freshLoad = withContext(Dispatchers.IO) { loadPlugin(pluginUrl!!) }
                val pluginGroup = groups.firstOrNull { it.pluginLoad != null }
                if (pluginGroup != null && freshLoad != null) {
                    pluginGroup.pluginLoad = freshLoad
                }
            }
            resolveAll()
        }
    }

    private fun refreshGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        Logger.log("TMDB_SERVERS: refresh group '${group.name}'")
        TmdbStreamResolver.invalidateLinks(mediaId, group.name)
        group.state = GroupState.Loading
        renderChipStates()
        if (selectedIndex == index) refreshRows()
        lifecycleScope.launch { launchGroupResolution(index) }
    }

    private suspend fun resolveCsGroup(group: Group) {
        val d = detail ?: return
        val cached = TmdbStreamResolver.cachedLinks(mediaId, group.source.name, season, epNumber)
        if (cached != null) {
            Logger.log("TMDB_SERVERS: '${group.name}' cached ${cached.links.size} links")
            group.state = GroupState.Ready(cached.links, emptyList(), emptyList())
            onResolved(group)
            return
        }
        val result = TmdbStreamResolver.resolveStreams(this, group.source, d, season, epNumber)
        when (result) {
            is TmdbStreamResolver.StreamResult.Success -> {
                Logger.log(
                    "TMDB_SERVERS: '${group.name}' ok ${result.links.size} links " +
                        "playable=${result.links.count { it.url.isNotBlank() }}"
                )
                TmdbStreamResolver.cacheLinks(mediaId, group.source.name, season, epNumber, result)
                group.state = GroupState.Ready(result.links, emptyList(), emptyList())
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(android.util.Log.ERROR, "TMDB_SERVERS: '${group.name}' failed: ${result.message}")
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
                        "TMDB_SERVERS: '${group.name}' ok ${result.links.size} links " +
                            "playable=${result.links.count { it.url.isNotBlank() }}"
                    )
                    TmdbStreamResolver.cacheLinks(mediaId, group.name, season, epNumber, result)
                    group.state = GroupState.Ready(result.links, emptyList(), emptyList())
                    onResolved(group)
                    return
                }
                val canCard = { link: TmdbStreamResolver.StreamResult.PlayableLink ->
                    parseStremioTarget(link.externalUrl) != null ||
                        parseExtraDescription(link.rawDetails ?: link.details).isTemplateMatch
                }
                val infos = external.filter { !canCard(it) }
                val extras = external - infos.toSet()
                val recommendations = extras.limitedAsyncMap(4) { link ->
                    runCatching { resolveExtraMedia(link, mediaType) }.getOrNull()
                }.filterNotNull()
                    .filterNot { it.id == mediaId && it.type == mediaType }
                    .distinctBy { "${it.type}:${it.id}" }
                Logger.log(
                    "TMDB_SERVERS: '${group.name}' extras recs=${recommendations.size} infos=${infos.size}"
                )
                group.state = GroupState.Ready(result.links, recommendations, infos)
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(
                    android.util.Log.ERROR,
                    "TMDB_SERVERS: '${group.name}' failed: ${result.message}"
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
                    "TMDB_SERVERS: '${group.name}' ok ${result.links.size} links " +
                        "playable=${result.links.count { it.url.isNotBlank() }}"
                )
                TmdbStreamResolver.cacheLinks(mediaId, group.name, season, epNumber, result)
                group.state = GroupState.Ready(result.links, emptyList(), emptyList())
            }
            is TmdbStreamResolver.StreamResult.Error -> {
                Logger.log(android.util.Log.ERROR, "TMDB_SERVERS: '${group.name}' failed: ${result.message}")
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
        Logger.log("TMDB_SERVERS: play '${d.displayTitle}' via ${group.name} label='${link.label}'")
        val overrideEpisodes = if (group.pluginLoad != null) {
            if (mediaType == "tv") {
                TmdbStreamResolver.pluginEpisodeShells(mediaId, pluginEpisodes)
            } else {
                TmdbStreamResolver.pluginMovieShell(mediaId, d)
            }
        } else null
        lifecycleScope.launch(Dispatchers.IO) {
            TmdbStreamResolver.launchPlayer(
                this@TmdbServersActivity,
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
            CsRuntime.apisFor(this@TmdbServersActivity, source).firstOrNull()
        } ?: run {
            snackString("Could not load ${source.name}")
            finish()
            return null
        }
        val load = withContext(Dispatchers.IO) {
            runCatching { api.load(url) }.getOrNull()
        }
        if (load == null) {
            Logger.log(android.util.Log.ERROR, "TMDB_SERVERS: plugin load null for $url")
            snackString("Plugin returned nothing for this title")
            finish()
            return null
        }
        pluginLoad = load
        Logger.log(
            "TMDB_SERVERS: plugin load -> ${load.javaClass.simpleName} '${load.name}' (api=${load.apiName})"
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
        val rawId = segments.getOrNull(2) ?: return null
        val baseId = rawId.substringBefore(':')
        return when {
            baseId.startsWith("tt") -> {
                val type = segments.getOrNull(1) ?: return null
                (if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie") to baseId
            }
            baseId.startsWith("tmdb") -> {
                val numericId = baseId.substringAfter("tmdb:", "")
                    .takeIf { it.toIntOrNull() != null }
                    ?: return null
                val type = segments.getOrNull(1) ?: return null
                (if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie") to numericId
            }
            else -> null
        }
    }

    private data class ExtraDescription(
        val releaseDate: String? = null,
        val rating: Double? = null,
        val genres: List<String> = emptyList(),
        val popularity: Double? = null,
        val overview: String? = null,
    ) {
        val isTemplateMatch: Boolean
            get() = releaseDate != null || rating != null || popularity != null
    }

    private val extraReleaseRx = Regex("Release(?:d)?\\s*:?\\s*(\\d{4}-\\d{1,2}-\\d{1,2})")
    private val extraRatingRx = Regex("Rating\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/\\s*10|%)?")
    private val extraPopularityRx = Regex("Popularity\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val extraOverviewRx = Regex("Overview\\s*:?\\s*", RegexOption.IGNORE_CASE)
    private val extraGenreSegmentRx = Regex("Genre\\s*:?\\s*([^\\n📈📖◎★]+)", RegexOption.IGNORE_CASE)

    private fun parseExtraDescription(text: String?): ExtraDescription? {
        if (matchesTorrentQualityText(text)) return null
        val release = extraReleaseRx.find(text ?: return null)?.groupValues?.getOrNull(1)
        val rating = extraRatingRx.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val popularity = extraPopularityRx.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val overview = extraOverviewRx.find(text)?.let { m ->
            text.substring(m.range.last + 1).trim().takeIf { it.isNotBlank() }
        }
        val genres = extraGenreSegmentRx.find(text)?.groupValues?.getOrNull(1)
            ?.split("|", ",")
            ?.mapNotNull { part ->
                part.replace(Regex("[^\\p{L}\\p{Nd}'\\- ]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length > 1 }
            }
            .orEmpty()
        if (release == null && rating == null && popularity == null && genres.isEmpty() && overview == null) {
            return null
        }
        return ExtraDescription(release, rating, genres, popularity, overview)
    }

    private fun matchesTorrentQualityText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val hasStructuredExtra = extraReleaseRx.containsMatchIn(text) ||
            extraRatingRx.containsMatchIn(text) ||
            extraPopularityRx.containsMatchIn(text)
        if (hasStructuredExtra) return false
        return containsStreamQualityTokens(text)
    }

    private fun containsStreamQualityTokens(text: String): Boolean = listOf(
        "HDR", "HDR10+", "DV ", "DoVi", "Blocks", "⚠️", "👤", "⚙️",
        ".mkv", ".mp4", "Torrent", "WebRip", "WEB-DL", "BluRay", "1080p", "2160p", "720p", "4k",
    ).any { token ->
        text.contains(token, ignoreCase = true)
    }

    private suspend fun resolveExtraMedia(
        link: TmdbStreamResolver.StreamResult.PlayableLink,
        fallbackType: String,
    ): TmdbMedia? {
        val desc = parseExtraDescription(link.rawDetails ?: link.details)
        val target = parseStremioTarget(link.externalUrl)
        val addonTitle = link.label.trim().takeIf { it.isNotBlank() }
        val mediaType = target?.first ?: fallbackType
        val tmdb = when {
            target != null && target.second.startsWith("tt") ->
                Tmdb.findByImdb(target.second, target.first)?.copy(mediaType = target.first)
            target != null -> resolveTmdbTargetMedia(target.first, target.second.toIntOrNull() ?: 0)
            !addonTitle.isNullOrBlank() -> {
                val results = runCatching { Tmdb.search(addonTitle) }.getOrDefault(emptyList())
                val typed = results.filter { it.type == mediaType }
                typed.ifEmpty { results }.firstOrNull()
            }
            else -> null
        }
        if (tmdb == null && addonTitle == null) return null
        return TmdbMedia(
            id = tmdb?.id ?: 0,
            title = tmdb?.title ?: (if (mediaType == "movie") addonTitle else null),
            name = tmdb?.name ?: (if (mediaType == "tv") addonTitle else null),
            posterPath = tmdb?.posterPath,
            backdropPath = tmdb?.backdropPath,
            voteAverage = desc?.rating?.takeIf { it > 0 } ?: (tmdb?.voteAverage ?: 0.0),
            popularity = desc?.popularity?.takeIf { it > 0 } ?: (tmdb?.popularity ?: 0.0),
            releaseDate = desc?.releaseDate ?: tmdb?.releaseDate,
            firstAirDate = tmdb?.firstAirDate,
            overview = desc?.overview ?: tmdb?.overview,
            genreIds = tmdb?.genreIds ?: emptyList(),
            mediaType = mediaType,
        )
    }

    private suspend fun resolveTmdbTargetMedia(type: String, id: Int): TmdbMedia? {
        if (id <= 0) return null
        val d = Tmdb.detail(type, id) ?: return null
        return TmdbMedia(
            id = d.id,
            title = if (type == "movie") d.title else null,
            name = if (type == "tv") d.name else null,
            posterPath = d.posterPath,
            backdropPath = d.backdropPath,
            voteAverage = d.voteAverage,
            popularity = d.popularity,
            releaseDate = d.releaseDate,
            firstAirDate = d.firstAirDate,
            overview = d.overview,
            mediaType = type,
        )
    }

    private fun openMedia(media: TmdbMedia) {
        val type = if (media.type == "tv") "tv" else "movie"
        if (media.id > 0) {
            Logger.log("TMDB_SERVERS: opening extras item type=$type id=${media.id} '${media.displayTitle}'")
            startActivity(
                android.content.Intent(this, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
            )
            return
        }
        val title = media.displayTitle.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching { Tmdb.search(title) }.getOrDefault(emptyList())
            }
            val match = results.firstOrNull { it.type == type } ?: results.firstOrNull()
            if (match != null) {
                Logger.log("TMDB_SERVERS: resolved extras title '$title' to tmdb=${match.id} (${match.type})")
                openMedia(match)
            } else {
                snackString("No TMDB match for $title")
            }
        }
    }

    private fun openExternal(link: TmdbStreamResolver.StreamResult.PlayableLink) {
        val url = link.externalUrl ?: return
        if (!url.startsWith("stremio://", ignoreCase = true)) {
            runCatching {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                )
            }.onFailure {
                Logger.log("TMDB_SERVERS: external open failed url=$url error=${it.message}")
                snackString("No app can open this link")
            }
            return
        }
        val target = parseStremioTarget(url) ?: run {
            Logger.log("TMDB_SERVERS: invalid stremio link url=$url")
            return
        }
        val (typeHint, imdbId) = target
        Logger.log("TMDB_SERVERS: resolving stremio link type=$typeHint imdb=$imdbId")
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { Tmdb.findByImdb(imdbId, typeHint) }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (found == null) {
                Logger.log("TMDB_SERVERS: stremio link unresolved id=$imdbId")
                snackString("Could not resolve $imdbId on TMDB")
                return@launch
            }
            startActivity(
                android.content.Intent(this@TmdbServersActivity, TmdbDetailsActivity::class.java)
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
                1 -> ItemVH(ItemTmdbServerRowBinding.inflate(inf, parent, false))
                2 -> ItemVH(ItemTmdbServerRowBinding.inflate(inf, parent, false))
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
                binding.tmdbStreamsGroupRefresh.setOnClickListener {
                    val index = groups.indexOfFirst { it === group }
                    if (index >= 0) refreshGroup(index)
                }
                FocusEffectUtil.applyFocusListener(binding.tmdbStreamsGroupRefresh)
            }
        }

        private inner class ItemVH(val binding: ItemTmdbServerRowBinding) :
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
                rail.adapter = DetailCardAdapter(media)
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

    private inner class DetailCardAdapter(
        private val items: List<TmdbMedia>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DetailCardAdapter.DetailVH>() {

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int
        ): DetailVH = DetailVH(
            ItemTmdbStreamsDetailCardBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(
            holder: DetailVH,
            position: Int
        ) {
            val m = items[position]
            holder.binding.tmdbRecsCardPoster.loadImage(
                Tmdb.imageUrl(m.posterPath ?: m.backdropPath, 300)
                    ?: if (m.posterPath?.startsWith("http") == true) m.posterPath else null
            )
            holder.binding.tmdbRecsCardTitle.text = m.displayTitle
            val meta = listOfNotNull(
                String.format(java.util.Locale.US, "★ %.1f", m.voteAverage)
                    .takeIf { m.voteAverage > 0 },
                (m.releaseDate ?: m.firstAirDate).takeIf { !it.isNullOrBlank() },
                String.format(java.util.Locale.US, "Popularity %.1f", m.popularity)
                    .takeIf { m.popularity > 0 },
            ).joinToString("  •  ")
            if (meta.isBlank()) {
                holder.binding.tmdbRecsCardMeta.isVisible = false
            } else {
                holder.binding.tmdbRecsCardMeta.text = meta
                holder.binding.tmdbRecsCardMeta.isVisible = true
            }
            val overview = m.overview?.takeIf { it.isNotBlank() }
            if (overview == null) {
                holder.binding.tmdbRecsCardOverview.isVisible = false
            } else {
                holder.binding.tmdbRecsCardOverview.text = overview
                holder.binding.tmdbRecsCardOverview.isVisible = true
            }
            holder.binding.root.setOnClickListener { openMedia(m) }
            FocusEffectUtil.applyFocusListener(holder.binding.root)
        }

        private inner class DetailVH(val binding: ItemTmdbStreamsDetailCardBinding) :
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
