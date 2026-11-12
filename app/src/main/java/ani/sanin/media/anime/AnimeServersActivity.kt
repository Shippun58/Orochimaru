package ani.sanin.media.anime

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.util.FocusEffectUtil
import ani.sanin.themes.ThemeManager
import ani.sanin.databinding.ActivityTmdbServersBinding
import ani.sanin.databinding.ItemTmdbServerRowBinding
import ani.sanin.databinding.ItemTmdbStreamsGroupBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.parsers.AnimeParser
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.HAnimeSources
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import ani.sanin.parsers.WatchSources
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.Logger
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AnimeServersActivity : AppCompatActivity() {

    companion object {
        var media: Media? = null
        var initialized = false
        const val ARG_EPISODE = "episode"
        const val ARG_PREV = "prevEp"
        const val ARG_LAUNCH = "launch"
        const val ARG_SERVER = "savedServer"
    }

    private lateinit var serversBinding: ActivityTmdbServersBinding
    private val model: MediaDetailsViewModel by activityViewModels()

    private var epKey: String = ""
    private var actualEpKey: String? = null
    private var launchFlag = true
    private var prevEp: String? = null
    private var savedServerArg: String? = null
    private var sources: WatchSources = AnimeSources

    private sealed class Row {
        data class Head(val group: Group) : Row()
        data class Stream(val server: VideoServer, val group: Group) : Row()
        data class Text(val text: String) : Row()
    }

    private sealed class GroupState {
        object Loading : GroupState()
        data class Failed(val reason: String) : GroupState()
        data class Ready(val servers: List<VideoServer>) : GroupState()
    }

    private class Group(
        val index: Int,
        val name: String,
        val logoUrl: String?,
        val parser: AnimeParser,
    ) {
        var state: GroupState = GroupState.Loading
        var rank = 2
        var arrivalSeq = Int.MAX_VALUE
        var episode: Episode? = null
    }

    private val groups = mutableListOf<Group>()
    private val rows = mutableListOf<Row>()
    private val chipByIndex = mutableListOf<Chip>()
    private var selectedIndex = 0
    private val rowsAdapter = RowsAdapter()
    private val groupJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private var arrivalCounter = 0
    private var pendingAutoPlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        epKey = intent.getStringExtra(ARG_EPISODE) ?: ""
        launchFlag = intent.getBooleanExtra(ARG_LAUNCH, true)
        prevEp = intent.getStringExtra(ARG_PREV)
        savedServerArg = intent.getStringExtra(ARG_SERVER)
        sources = if (media?.isAdult == true) HAnimeSources else AnimeSources

        Logger.log(
            "ANIME_SERVERS: open episode='$epKey' launch=$launchFlag " +
                "sourceIdx=${media?.selected?.sourceIndex} title='${media?.name}'"
        )

        if (!initialized || media == null || epKey.isBlank()) {
            snackString("Could not open servers")
            finish()
            return
        }

        serversBinding = ActivityTmdbServersBinding.inflate(layoutInflater)
        setContentView(serversBinding.root)
        val topBarBasePadding = (6 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(serversBinding.tmdbServersTopBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, topBarBasePadding + top, v.paddingRight, v.paddingBottom)
            insets
        }
        serversBinding.tmdbStreamsRows.layoutManager = LinearLayoutManager(this)
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
        }
        serversBinding.tmdbServersScroll.post { setupCollapse() }

        val m = media!!
        actualEpKey = m.anime?.episodes?.getEpisodeKey(epKey) ?: epKey
        m.anime?.selectedEpisode = actualEpKey

        bindHeader()
        buildGroups()
        selectedIndex = groups.indexOfFirst { it.index == (m.selected?.sourceIndex ?: -1) }
            .takeIf { it >= 0 } ?: 0
        buildChips()
        refreshRows()
        pendingAutoPlay = launchFlag && savedServerArg != null
        resolveAll()
    }

    private fun setupCollapse() {
        val scroll = serversBinding.tmdbServersScroll
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateCollapse(scrollY)
        }
        scroll.post { updateCollapse(scroll.scrollY) }
    }

    private fun updateCollapse(scrollY: Int) {
        val scroll = serversBinding.tmdbServersScroll
        val sticky = serversBinding.tmdbServersStickyBlock
        val topBar = serversBinding.tmdbServersTopBar
        val backdrop = serversBinding.tmdbStreamsBackdrop
        val stickyTop = sticky.top + scroll.scrollY
        val headerCollapseMax = (stickyTop - topBar.height).coerceAtLeast(0)
        val progress = if (headerCollapseMax > 0) {
            (scrollY.toFloat() / headerCollapseMax).coerceIn(0f, 1f)
        } else 0f
        val pinned = (scrollY - headerCollapseMax).coerceAtLeast(0)
        sticky.translationY = -pinned.toFloat()
        backdrop.translationY = scrollY * 0.35f
        topBar.background?.alpha = (progress * 255).toInt().coerceIn(0, 255)
    }

    private fun bindHeader() {
        val m = media ?: return
        serversBinding.tmdbServersCompactLogo.isVisible = false
        serversBinding.tmdbServersCompactTitle.isVisible = true
        serversBinding.tmdbServersCompactTitle.text = m.name
        applyThemeGradient()

        val ep = m.anime?.episodes?.getEpisode(epKey)
        serversBinding.tmdbStreamsBackdrop.loadImage(m.banner ?: m.cover)
        serversBinding.tmdbStreamsEpisode.isVisible = ep != null
        serversBinding.tmdbStreamsEpisode.text = ep?.let {
            "Ep ${it.number}" + (it.title?.takeIf { t -> t.isNotBlank() }
                ?.let { t -> ": $t" } ?: "")
        }
        val score = m.meanScore
        if (score != null && score > 0) {
            serversBinding.tmdbStreamsRating.isVisible = true
            serversBinding.tmdbStreamsRating.text =
                String.format(java.util.Locale.US, "★ %.1f", score / 10.0)
        } else {
            serversBinding.tmdbStreamsRating.isVisible = false
        }
        val overview = ep?.desc?.takeIf { it.isNotBlank() } ?: m.description
        if (overview.isNullOrBlank()) {
            serversBinding.tmdbStreamsOverview.isVisible = false
        } else {
            serversBinding.tmdbStreamsOverview.isVisible = true
            serversBinding.tmdbStreamsOverview.text = overview
        }
        val parts = mutableListOf<String>()
        m.anime?.seasonYear?.takeIf { it > 0 }?.let { parts.add(it.toString()) }
        m.anime?.season?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        m.anime?.episodeDuration?.takeIf { it > 0 }?.let { parts.add("${it}min") }
        if (parts.isEmpty()) {
            serversBinding.tmdbStreamsMeta.isVisible = false
        } else {
            serversBinding.tmdbStreamsMeta.isVisible = true
            serversBinding.tmdbStreamsMeta.text = parts.joinToString("  ·  ")
        }
        serversBinding.tmdbServersScroll.post {
            updateCollapse(serversBinding.tmdbServersScroll.scrollY)
        }
        Logger.log(
            "ANIME_SERVERS: header ep=${ep != null} title='${serversBinding.tmdbStreamsEpisode.text}'"
        )
    }

    private fun applyThemeGradient() {
        val surface = getThemeColor(android.R.attr.colorBackground)
        val divider = getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
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
        serversBinding.tmdbServersDivider.background =
            android.graphics.drawable.ColorDrawable(divider)
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
        arrivalCounter = 0
        sources.list.forEachIndexed { index, lazier ->
            val listName = lazier.name
            if (listName.equals("Local", ignoreCase = true)) return@forEachIndexed
            val parser = runCatching { lazier.get.value as? AnimeParser }.getOrNull() ?: return@forEachIndexed
            groups.add(Group(index, listName.ifBlank { parser.name }, parser.iconUrl, parser))
        }
        Logger.log(
            "ANIME_SERVERS: groups=${groups.map { it.name }} " +
                "selectedSourceIdx=${media?.selected?.sourceIndex}"
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
            chip.setTextColor(resources.getColorStateList(R.color.chip_text_color))
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

    private fun refreshRows() {
        rows.clear()
        val group = groups.getOrNull(selectedIndex)
        if (group == null) {
            rowsAdapter.notifyDataSetChanged()
            return
        }
        rows.add(Row.Head(group))
        when (val state = group.state) {
            is GroupState.Loading -> rows.add(Row.Text("Loading ${group.name}…"))
            is GroupState.Failed -> rows.add(Row.Text(state.reason))
            is GroupState.Ready -> {
                if (state.servers.isEmpty()) {
                    rows.add(Row.Text("Nothing found in ${group.name}"))
                } else {
                    state.servers.forEach { rows.add(Row.Stream(it, group)) }
                }
            }
        }
        rowsAdapter.notifyDataSetChanged()
        serversBinding.tmdbStreamsRows.scrollToPosition(0)
        Logger.log(
            "ANIME_SERVERS: rows for '${group.name}' count=${rows.size} state=${stateLabel(group.state)}"
        )
    }

    private fun stateLabel(state: GroupState): String = when (state) {
        is GroupState.Loading -> "loading"
        is GroupState.Failed -> "failed"
        is GroupState.Ready -> if (state.servers.isNotEmpty()) "ready" else "empty"
    }

    private suspend fun resolveAll() {
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
            renderChipStates()
            refreshRows()
        }
        Logger.log("ANIME_SERVERS: resolveAll mode=$mode targets=$targets")
        targets.forEach { launchGroupResolution(it) }
        groupJobs.values.toList().forEach { it.join() }
    }

    private fun showLoadModeMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor, android.view.Gravity.END)
        menu.menuInflater.inflate(R.menu.menu_tmdb_servers_load, menu.menu)
        val parallelItem = menu.menu.findItem(R.id.tmdbServersLoadModeParallel)
        val onSelectItem = menu.menu.findItem(R.id.tmdbServersLoadModeOnSelect)
        parallelItem.isCheckable = true
        onSelectItem.isCheckable = true
        fun syncChecks() {
            val current = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
            parallelItem.isChecked = current == 0
            onSelectItem.isChecked = current == 1
        }
        syncChecks()
        menu.setOnMenuItemClickListener { item ->
            val mode = when (item.itemId) {
                R.id.tmdbServersLoadModeParallel -> 0
                R.id.tmdbServersLoadModeOnSelect -> 1
                else -> return@setOnMenuItemClickListener true
            }
            PrefManager.setVal(PrefName.TmdbServersLoadMode, mode)
            syncChecks()
            lifecycleScope.launch { resolveAll() }
            true
        }
        menu.show()
    }

    private fun launchGroupResolution(index: Int) {
        val group = groups.getOrNull(index) ?: return
        groupJobs.remove(index)?.cancel()
        groupJobs[index] = lifecycleScope.launch(Dispatchers.IO) {
            resolveGroup(group)
        }
    }

    private suspend fun resolveGroup(group: Group) {
        val m = media ?: return
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
            val preferDub = m.selected?.preferDub
                ?: PrefManager.getVal(PrefName.PreferDub)
            if (group.index != (m.selected?.sourceIndex ?: -1) &&
                group.parser.isDubAvailableSeparately()
            ) {
                Logger.log("ANIME_SERVERS: '${group.name}' dub=$preferDub applied to parser")
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
            Logger.log("ANIME_SERVERS: '${group.name}' ok ${servers.size} servers")
        } catch (e: Exception) {
            Logger.log(android.util.Log.ERROR, "ANIME_SERVERS: '${group.name}' failed: ${e.message}")
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
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val selectedKey = groups.getOrNull(selectedIndex)?.index
            groups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }))
            selectedIndex = groups.indexOfFirst { it.index == selectedKey }.takeIf { it >= 0 } ?: selectedIndex
            buildChips()
            refreshRows()
            attemptAutoPlay(group)
        }
    }

    private fun attemptAutoPlay(group: Group) {
        if (!pendingAutoPlay) return
        val m = media ?: return
        val savedServer = savedServerArg ?: run { pendingAutoPlay = false; return }
        if (group.index != (m.selected?.sourceIndex ?: -1)) return
        val state = group.state as? GroupState.Ready ?: return
        val server = state.servers.firstOrNull { it.name == savedServer } ?: run {
            Logger.log("ANIME_SERVERS: saved server '$savedServer' not found in ${group.name}")
            pendingAutoPlay = false
            m.selected?.server = null
            model.saveSelected(m.id, m.selected!!)
            snackString("Saved server '$savedServer' unavailable, pick another")
            return
        }
        pendingAutoPlay = false
        Logger.log("ANIME_SERVERS: auto-play saved server '$savedServer'")
        lifecycleScope.launch {
            val extractor = withContext(Dispatchers.IO) {
                runCatching { loadSingleExtractor(group, server) }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (extractor == null || extractor.videos.isEmpty()) {
                Logger.log("ANIME_SERVERS: saved server '$savedServer' failed, falling back to list")
                m.selected?.server = null
                model.saveSelected(m.id, m.selected!!)
                snackString("Could not load $savedServer, pick another server")
                return@launch
            }
            launchPlayback(group, extractor, autoVideo = true)
        }
    }

    private fun selectGroup(index: Int) {
        if (index !in groups.indices) return
        val changed = selectedIndex != index
        selectedIndex = index
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault)) {
            media?.let { m ->
                m.selected?.sourceIndex = groups[index].index
                model.saveSelected(m.id, m.selected!!)
            }
        }
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
        Logger.log("ANIME_SERVERS: refresh all")
        groupJobs.values.toList().forEach { it.cancel() }
        groupJobs.clear()
        groups.forEach { it.state = GroupState.Loading }
        renderChipStates()
        refreshRows()
        snackString(R.string.tmdb_servers_refreshed_all)
        lifecycleScope.launch { resolveAll() }
    }

    private fun refreshGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        Logger.log("ANIME_SERVERS: refresh group '${group.name}'")
        group.state = GroupState.Loading
        renderChipStates()
        if (selectedIndex == index) refreshRows()
        lifecycleScope.launch { launchGroupResolution(index) }
    }

    // --- playback ---

    private fun play(server: VideoServer, group: Group) {
        val m = media ?: return
        if (PrefManager.getVal<Boolean>(PrefName.MakeDefault)) {
            m.selected?.sourceIndex = group.index
            model.saveSelected(m.id, m.selected!!)
        }
        lifecycleScope.launch {
            val extractor = withContext(Dispatchers.IO) {
                runCatching { loadSingleExtractor(group, server) }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (extractor == null || extractor.videos.isEmpty()) {
                Logger.log(
                    android.util.Log.ERROR,
                    "ANIME_SERVERS: no video from '${server.name}' in ${group.name}"
                )
                snackString("No video found in ${server.name}")
                return@launch
            }
            var videoIdx = extractor.videos.indexOfFirst { it.preferred }
            if (videoIdx < 0) videoIdx = 0
            if (extractor.videos.size > 1) {
                videoIdx = pickVideo(extractor, videoIdx) ?: return@launch
            }
            launchPlayback(group, extractor, autoVideo = false, forcedVideo = videoIdx)
        }
    }

    private fun launchPlayback(
        group: Group,
        extractor: VideoExtractor,
        autoVideo: Boolean,
        forcedVideo: Int? = null,
    ) {
        val m = media ?: return
        val ep = m.anime?.episodes?.getEpisode(epKey) ?: group.episode ?: return
        val videoIdx = forcedVideo
            ?: m.selected?.video?.takeIf { it in extractor.videos.indices && autoVideo }
            ?: extractor.videos.indexOfFirst { it.preferred }.takeIf { it >= 0 }
            ?: 0
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
        val key = m.anime?.episodes?.getEpisodeKey(epKey) ?: actualEpKey ?: epKey
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
            "ANIME_SERVERS: play '${m.name}' ep=${ep.number} via ${group.name}/${extractor.server.name} video=$videoIdx"
        )
        startExoplayer()
    }

    private suspend fun loadSingleExtractor(group: Group, server: VideoServer): VideoExtractor? {
        val ep = group.episode ?: return null
        val link = ep.link ?: return null
        val sEp = ep.sEpisode ?: return null
        return group.parser.loadSingleVideoServer(server.name, link, ep.extra, sEp, true)
    }

    private suspend fun pickVideo(extractor: VideoExtractor, defaultIdx: Int): Int? =
        suspendCoroutine { cont ->
            val videos = extractor.videos
            val options = videos.mapIndexed { i, v ->
                val base = videoLabel(v)
                if (i == defaultIdx) "$base (preferred)" else base
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(extractor.server.name)
                .setItems(options.toTypedArray()) { _, which -> cont.resume(which) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(null) }
                .show()
        }

    private fun videoLabel(v: Video): String {
        val q = v.quality?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
        return when (v.format) {
            VideoType.M3U8 -> "$q · m3u8"
            VideoType.DASH -> "$q · dash"
            else -> q
        }
    }

    private fun startExoplayer() {
        val m = media ?: return
        ExoplayerView.media = m
        ExoplayerView.initialized = true
        startActivity(Intent(this, ExoplayerView::class.java))
    }

    // --- rows RecyclerView ---

    private inner class RowsAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Head -> 0
            is Row.Stream -> 1
            is Row.Text -> 2
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder {
            val inf = android.view.LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeadVH(ItemTmdbStreamsGroupBinding.inflate(inf, parent, false))
                1 -> ItemVH(ItemTmdbServerRowBinding.inflate(inf, parent, false))
                else -> TextVH(ItemTmdbStreamsTextBinding.inflate(inf, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int
        ) {
            when (val row = rows[position]) {
                is Row.Head -> (holder as HeadVH).bind(row.group)
                is Row.Stream -> (holder as ItemVH).bind(row.server, row.group)
                is Row.Text -> (holder as TextVH).bind(row.text)
            }
        }

        private inner class HeadVH(val binding: ItemTmdbStreamsGroupBinding) :
            RecyclerView.ViewHolder(binding.root) {

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
            RecyclerView.ViewHolder(binding.root) {

            init {
                (binding.root as androidx.cardview.widget.CardView)
                    .setCardBackgroundColor(tintedRowColor())
                itemView.isFocusable = true
                val r = 16f * itemView.resources.displayMetrics.density
                FocusEffectUtil.applyFocusListener(
                    itemView,
                    floatArrayOf(r, r, r, r, r, r, r, r),
                )
            }

            fun bind(server: VideoServer, group: Group) {
                binding.urlQuality.text = server.name
                val quality = server.extraData?.get("quality")?.takeIf { it.isNotBlank() }
                val audio = server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()
                val note = listOfNotNull(quality, audio).joinToString(" · ")
                if (note.isBlank()) {
                    binding.urlNote.isVisible = false
                } else {
                    binding.urlNote.text = note
                    binding.urlNote.isVisible = true
                }
                binding.urlSize.isVisible = false
                binding.urlSub.isVisible = false
                binding.urlDownload.isVisible = false
                binding.root.setOnClickListener { play(server, group) }
            }
        }

        private inner class TextVH(val binding: ItemTmdbStreamsTextBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = false
                itemView.isClickable = false
            }

            fun bind(text: String) {
                binding.tmdbStreamsText.text = text
            }
        }
    }

    private fun stateSummary(state: GroupState.Ready): String =
        if (state.servers.isEmpty()) "Empty" else "${state.servers.size} servers"

    private fun tintedRowColor(): Int {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val base = if (dark) 0xFF1C1D21.toInt() else 0xFFFFFFFF.toInt()
        val primary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val mix = if (dark) 0.15f else 0.09f
        fun blend(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val p = (primary shr shift) and 0xFF
            return ((b * (1f - mix)) + (p * mix)).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(blend(16), blend(8), blend(0))
    }
}
