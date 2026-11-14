package ani.sanin.media.anime

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.download.DownloadManager
import ani.sanin.download.enqueue
import ani.sanin.ui.GlassPill
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
import ani.sanin.parsers.NativeAnimeParser
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import ani.sanin.parsers.WatchSources
import ani.sanin.px
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AnimeServersActivity : AppCompatActivity() {

    companion object {
        var media: Media? = null
        var initialized = false
        const val ARG_EPISODE = "episode"
        const val ARG_PREV = "prevEp"
        const val ARG_LAUNCH = "launch"
        const val ARG_SERVER = "savedServer"
        const val ARG_IS_DOWNLOAD = "isDownload"
    }

    private lateinit var serversBinding: ActivityTmdbServersBinding
    private var downloadMode = false
    private val model: MediaDetailsViewModel by viewModels()

    private var epKey: String = ""
    private var actualEpKey: String? = null
    private var launchFlag = true
    private var prevEp: String? = null
    private var savedServerArg: String? = null
    private var sources: WatchSources = AnimeSources

    private sealed class Row {
        data class Head(val group: Group) : Row()
        data class Server(val group: Group, val server: VideoServer) : Row()
        data class Video(
            val group: Group,
            val extractor: VideoExtractor,
            val videoIdx: Int,
            val isFirst: Boolean,
            val isLast: Boolean,
            val server: VideoServer? = null,
        ) : Row()
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
        val icon: android.graphics.drawable.Drawable?,
        val parser: AnimeParser,
    ) {
        var state: GroupState = GroupState.Loading
        var rank = 1
        var arrivalSeq = Int.MAX_VALUE
        var episode: Episode? = null
        val extractors = mutableListOf<VideoExtractor>()
        val extractorLock = Any()
        var extractionDone = false
    }

    private val groups = mutableListOf<Group>()
    private val rows = mutableListOf<Row>()
    private val chipByIndex = mutableListOf<Chip>()
    private var selectedIndex = 0
    private val rowsAdapter = RowsAdapter()
    private val groupJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val arrivalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var pendingAutoPlay = false
    private var launchingPlayer = false
    private var initialFocusApplied = false

    override fun onResume() {
        super.onResume()
        launchingPlayer = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        epKey = intent.getStringExtra(ARG_EPISODE) ?: ""
        launchFlag = intent.getBooleanExtra(ARG_LAUNCH, true)
        prevEp = intent.getStringExtra(ARG_PREV)
        savedServerArg = intent.getStringExtra(ARG_SERVER)
        sources = if (media?.isAdult == true) HAnimeSources else AnimeSources
        downloadMode = intent.getBooleanExtra(ARG_IS_DOWNLOAD, false)

        Logger.log(
            "ANIME_SERVERS: open episode='$epKey' launch=$launchFlag download=$downloadMode " +
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
        serversBinding.tmdbServersMenu.setOnClickListener { showLoadModeMenu() }
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
        if (downloadMode) {
            serversBinding.tmdbServersMakeDefault.isVisible = false
            serversBinding.tmdbServersDownloadMode.isVisible = true
        }
        buildChips()
        refreshRows()
        pendingAutoPlay = !downloadMode && launchFlag && savedServerArg != null
        wireServerFocusChain()
        lifecycleScope.launch { resolveAll() }
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
        val epThumb = ep?.thumb
        when {
            epThumb != null -> serversBinding.tmdbStreamsBackdrop.loadImage(epThumb)
            m.banner?.isNotBlank() == true -> serversBinding.tmdbStreamsBackdrop.loadImage(m.banner)
            else -> serversBinding.tmdbStreamsBackdrop.loadImage(m.cover)
        }
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
        arrivalCounter.set(0)
        sources.list.forEachIndexed { index, lazier ->
            val listName = lazier.name
            if (listName.equals("Local", ignoreCase = true)) return@forEachIndexed
            val parser = runCatching { lazier.get.value as? AnimeParser }.getOrNull() ?: return@forEachIndexed
            groups.add(Group(index, listName.ifBlank { parser.name }, parser.iconUrl, parser.icon, parser))
        }
        Logger.log(
            "ANIME_SERVERS: groups=${groups.map { it.name }} " +
                "selectedSourceIdx=${media?.selected?.sourceIndex}"
        )
    }

    private fun buildChips(focusSource: Int? = null) {
        serversBinding.tmdbStreamsChips.removeAllViews()
        chipByIndex.clear()
        val inflater = android.view.LayoutInflater.from(this)
        groups.forEachIndexed { index, group ->
            val chip = inflater.inflate(
                R.layout.item_tmdb_chip,
                serversBinding.tmdbStreamsChips,
                false,
            ) as Chip
            chip.id = View.generateViewId()
            chip.tag = group.index
            chip.text = group.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(resources.getColorStateList(R.color.chip_text_color))
            if (group.parser is NativeAnimeParser) {
                chip.isChipIconVisible = true
                chip.chipIcon = ContextCompat.getDrawable(chip.context, R.drawable.ic_chip_dot)
                chip.chipIconSize = 5f.px.toFloat()
            }
            chip.isChecked = index == selectedIndex
            chip.setOnClickListener {
                val currentIndex = groups.indexOfFirst { it.index == group.index }
                if (currentIndex >= 0) selectGroup(currentIndex)
            }
            FocusEffectUtil.applyFocusListener(chip)
            chipByIndex.add(chip)
            serversBinding.tmdbStreamsChips.addView(chip)
        }
        applyChipFocusChain()
        if (focusSource != null) {
            val index = groups.indexOfFirst { it.index == focusSource }
            chipByIndex.getOrNull(index)?.requestFocus()
        }
        renderChipStates()
        focusInitialChip()
    }

    private fun wireServerFocusChain() {
        val back = serversBinding.tmdbStreamsBack
        val menu = serversBinding.tmdbServersMenu
        val refresh = serversBinding.tmdbServersRefreshAll
        val makeDefault = serversBinding.tmdbServersMakeDefault

        back.nextFocusUpId = back.id
        back.nextFocusLeftId = back.id
        back.nextFocusRightId = menu.id
        back.nextFocusDownId = refresh.id

        menu.nextFocusUpId = menu.id
        menu.nextFocusLeftId = back.id
        menu.nextFocusRightId = menu.id
        menu.nextFocusDownId = if (makeDefault.isVisible) makeDefault.id else refresh.id

        refresh.nextFocusUpId = back.id
        refresh.nextFocusLeftId = refresh.id
        refresh.nextFocusRightId = if (makeDefault.isVisible) makeDefault.id else refresh.id

        makeDefault.nextFocusUpId = menu.id
        makeDefault.nextFocusLeftId = refresh.id
        makeDefault.nextFocusRightId = makeDefault.id
    }

    private fun applyChipFocusChain() {
        chipByIndex.forEach { chip ->
            val previous = chip.onFocusChangeListener
            chip.setOnFocusChangeListener { v, hasFocus ->
                previous?.onFocusChange(v, hasFocus)
                if (hasFocus) {
                    serversBinding.tmdbServersRefreshAll.nextFocusDownId = chip.id
                    serversBinding.tmdbServersMakeDefault.nextFocusDownId = chip.id
                }
            }
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val index = chipByIndex.indexOf(chip)
                when {
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        rowsAdapter.focusFirstFocusableRow()
                        true
                    }
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && index == 0 -> true
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                        index == chipByIndex.lastIndex -> true
                    else -> false
                }
            }
        }
        relinkChipNeighbours()
    }

    private fun relinkChipNeighbours() {
        val refreshId = serversBinding.tmdbServersRefreshAll.id
        val defaultId = serversBinding.tmdbServersMakeDefault
            .takeIf { it.isVisible }?.id
            ?: chipByIndex.firstOrNull()?.id
            ?: View.NO_ID
        chipByIndex.forEachIndexed { index, chip ->
            chip.nextFocusUpId = refreshId
            chip.nextFocusLeftId =
                if (index == 0) defaultId else chipByIndex[index - 1].id
            chip.nextFocusRightId =
                if (index == chipByIndex.lastIndex) chip.id else chipByIndex[index + 1].id
            chip.nextFocusDownId = View.NO_ID
        }
        val selectedChipId = chipByIndex.getOrNull(selectedIndex)?.id
        if (selectedChipId != null) {
            serversBinding.tmdbServersRefreshAll.nextFocusDownId = selectedChipId
            serversBinding.tmdbServersMakeDefault.nextFocusDownId = selectedChipId
        }
    }

    private fun focusSelectedChip(): Boolean {
        val chip = chipByIndex.getOrNull(selectedIndex)
            ?: chipByIndex.firstOrNull()
            ?: return false
        return if (chip.isAttachedToWindow) {
            chip.requestFocus()
        } else {
            serversBinding.tmdbStreamsChips.post { chip.requestFocus() }
            true
        }
    }

    private fun focusInitialChip() {
        if (initialFocusApplied) return
        serversBinding.tmdbStreamsChips.post {
            if (initialFocusApplied) return@post
            if (chipByIndex.isEmpty()) return@post
            val current = currentFocus
            if (current == null || current === serversBinding.tmdbStreamsBack ||
                current === serversBinding.tmdbServersMenu ||
                current === serversBinding.tmdbServersRefreshAll ||
                current === serversBinding.tmdbServersMakeDefault
            ) {
                focusSelectedChip()
            }
            initialFocusApplied = true
        }
    }

    private fun renderChipStates() {
        chipByIndex.forEachIndexed { index, chip ->
            val group = groups.getOrNull(index) ?: return@forEachIndexed
            chip.isChecked = index == selectedIndex
            chip.text = when (val state = group.state) {
                is GroupState.Loading -> "${group.name} ·"
                is GroupState.Failed -> "${group.name} (failed)"
                is GroupState.Ready ->
                    if (state.servers.isEmpty()) "${group.name} (empty)" else group.name
            }
        }
    }

    private var rowRefreshPending = false

    private fun scheduleRowsRefresh() {
        if (rowRefreshPending) return
        rowRefreshPending = true
        serversBinding.tmdbStreamsRows.post {
            rowRefreshPending = false
            if (!isFinishing && !isDestroyed) refreshRows(scrollToTop = false)
        }
    }

    private fun refreshRows(scrollToTop: Boolean = true) {
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
                    } else if (PrefManager.getVal<Boolean>(PrefName.GroupServersByHostAndLanguageTags)) {
                        state.servers.forEach { server ->
                            val ex = group.extractors.firstOrNull { it.server.name == server.name }
                            val hasVideos = ex?.videos?.isNotEmpty() == true
                            if (group.extractionDone && !hasVideos) {
                                return@forEach
                            }
                            rows.add(Row.Server(group, server))
                            if (hasVideos) {
                                ex.videos.forEachIndexed { idx, _ ->
                                    rows.add(
                                        Row.Video(
                                            group, ex, idx,
                                            isFirst = idx == 0,
                                            isLast = idx == ex.videos.lastIndex,
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
        rowsAdapter.notifyDataSetChanged()
        if (scrollToTop) serversBinding.tmdbStreamsRows.scrollToPosition(0)
        Logger.log(
            "ANIME_SERVERS: rows for '${group.name}' count=${rows.size} state=${stateLabel(group.state)}"
        )
    }

    private val serverAudioRx = Regex("\\b(sub|dub|softsub|hardsub)\\b", RegexOption.IGNORE_CASE)
    private val serverQualityRx = Regex("\\b(\\d{3,4}\\s*p|auto|unknown)\\b", RegexOption.IGNORE_CASE)

    /** "Vidstream - Vidstream 720p (dub)" -> ("Vidstream", "720p", "dub") */
    private fun splitServerTitle(name: String): Triple<String, String?, String?> {
        var cleaned = name
        val audio = serverAudioRx.find(cleaned)?.groupValues?.getOrNull(1)?.lowercase()
        val quality = serverQualityRx.find(cleaned)?.groupValues?.getOrNull(1)
            ?.replace(" ", "")?.lowercase()
        audio?.let { cleaned = cleaned.replace(serverAudioRx, " ") }
        quality?.let { cleaned = cleaned.replace(serverQualityRx, " ") }
        cleaned = cleaned
            .replace(Regex("[()\\[\\]]"), " ")
            .replace(Regex("\\s*[-–·|]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '·', '|', ' ')
        return Triple(cleaned, quality, audio)
    }

    private fun stateLabel(state: GroupState): String = when (state) {
        is GroupState.Loading -> "loading"
        is GroupState.Failed -> "failed"
        is GroupState.Ready -> if (state.servers.isNotEmpty()) "ready" else "empty"
    }

    private suspend fun resolveAll() {
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        val targets = if (mode == 1) {
            listOf(selectedIndex)
        } else {
            groups.indices.sortedBy { if (it == selectedIndex) 0 else 1 }
        }
        if (mode == 1) {
            groups.forEachIndexed { i, g ->
                if (i != selectedIndex) {
                    groupJobs.remove(i)?.cancel()
                    markLoading(g)
                }
            }
            if (groups.getOrNull(selectedIndex)?.state is GroupState.Failed) {
                groups.getOrNull(selectedIndex)?.let { markLoading(it) }
            }
            renderChipStates()
            refreshRows()
        }
        Logger.log("ANIME_SERVERS: resolveAll mode=$mode targets=$targets")
        targets.forEach { launchGroupResolution(it) }
        groupJobs.values.toList().forEach { it.join() }
    }

    private fun markLoading(group: Group) {
        group.state = GroupState.Loading
        group.rank = 1
        group.arrivalSeq = Int.MAX_VALUE
    }

    private fun showLoadModeMenu() {
        val current = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        customAlertDialog()
            .setTitle("Server loading mode")
            .singleChoiceItems(
                arrayOf(
                    "Load servers parallel across all addons simultaneously",
                    "Load servers on source click",
                ),
                if (current == 1) 1 else 0,
            ) { which ->
                val mode = if (which == 1) 1 else 0
                PrefManager.setVal(PrefName.TmdbServersLoadMode, mode)
                Logger.log("ANIME_SERVERS: load mode set $mode")
                lifecycleScope.launch { resolveAll() }
            }
            .show()
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
        synchronized(group.extractorLock) { group.extractors.clear() }
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
            group.extractionDone = false
            Logger.log("ANIME_SERVERS: '${group.name}' ok ${servers.size} servers")
            seedRailCache(group, done = false)
            onResolved(group)
            if (servers.isNotEmpty()) {
                val link = target.link
                val sEp = target.sEpisode
                if (link != null && sEp != null) {
                    runCatching {
                        group.parser.loadByVideoServers(link, target.extra, sEp, { extractor ->
                            synchronized(group.extractorLock) { group.extractors.add(extractor) }
                            Logger.log(
                                "ANIME_SERVERS: '${group.name}' extractor '${extractor.server.name}' " +
                                    "${extractor.videos.size} videos"
                            )
                            seedRailCache(group, done = false)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                maybeAutoPlay(group, extractor)
                                if (groups.getOrNull(selectedIndex) === group) scheduleRowsRefresh()
                            }
                        }, servers)
                    }.onFailure {
                        Logger.log(android.util.Log.ERROR, "ANIME_SERVERS: '${group.name}' extractor load failed: ${it.message}")
                    }
                }
            }
            group.extractionDone = true
            seedRailCache(group, done = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (groups.getOrNull(selectedIndex) === group) scheduleRowsRefresh()
                if (pendingAutoPlay && group.index == (m.selected?.sourceIndex ?: -1)) {
                    pendingAutoPlay = false
                    m.selected?.server = null
                    model.saveSelected(m.id, m.selected!!)
                    snackString("Saved server unavailable, pick another")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                seedRailCache(group, done = true)
                throw e
            }
            Logger.log(android.util.Log.ERROR, "ANIME_SERVERS: '${group.name}' failed: ${e.message}")
            group.state = GroupState.Failed(e.message ?: "Failed")
            m.anime?.selectedEpisode?.let { key ->
                AnimeRailCache.fail(AnimeRailCache.key(m.id, key, group.index), e.message ?: "Failed")
            }
        }
        onResolved(group)
    }

    private fun seedRailCache(group: Group, done: Boolean) {
        val m = media ?: return
        val ready = group.state as? GroupState.Ready ?: return
        if (ready.servers.isEmpty()) return
        val epKey = m.anime?.selectedEpisode ?: return
        val extractors = synchronized(group.extractorLock) { group.extractors.toList() }
        AnimeRailCache.seed(
            AnimeRailCache.key(m.id, epKey, group.index),
            AnimeEntry(
                servers = ready.servers,
                extractors = extractors,
                failedReason = null,
                extractionDone = done,
                episode = group.episode,
            ),
        )
    }

    private fun onResolved(group: Group) {
        val ready = (group.state as? GroupState.Ready)?.servers?.isNotEmpty() == true
        group.rank = when {
            ready -> 0
            group.state is GroupState.Loading -> 1
            else -> 2
        }
        if (group.rank == 0 && group.arrivalSeq == Int.MAX_VALUE) {
            group.arrivalSeq = arrivalCounter.getAndIncrement()
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val selectedKey = groups.getOrNull(selectedIndex)?.index
            val focusedSource = chipByIndex.indexOfFirst { it.hasFocus() }
                .takeIf { it >= 0 }
                ?.let { groups.getOrNull(it)?.index }
            groups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }, { it.index }))
            selectedIndex = groups.indexOfFirst { it.index == selectedKey }.takeIf { it >= 0 } ?: selectedIndex
            syncChipsAfterSort(focusedSource)
            if (groups.getOrNull(selectedIndex) === group) scheduleRowsRefresh()
        }
    }

    private fun syncChipsAfterSort(focusedSource: Int? = null) {
        if (chipByIndex.size != groups.size) {
            buildChips(focusedSource)
            return
        }
        val currentOrder = chipByIndex.mapNotNull { it.tag as? Int }
        val newOrder = groups.map { it.index }
        if (currentOrder == newOrder) {
            renderChipStates()
            relinkChipNeighbours()
            return
        }
        val focusedId = currentFocus?.id
        val chipsByIndex = chipByIndex.groupBy { it.tag as? Int }
        val used = HashMap<Int?, Int>()
        val ordered = ArrayList<Chip>(chipByIndex.size)
        groups.forEach { group ->
            val list = chipsByIndex[group.index] ?: return@forEach
            val position = used[group.index] ?: 0
            if (position < list.size) {
                ordered.add(list[position])
                used[group.index] = position + 1
            }
        }
        chipByIndex.forEach { chip -> if (chip !in ordered) ordered.add(chip) }
        val parent = serversBinding.tmdbStreamsChips
        parent.removeAllViews()
        chipByIndex.clear()
        ordered.forEach { chip ->
            (chip.parent as? android.view.ViewGroup)?.removeView(chip)
            parent.addView(chip)
            chipByIndex.add(chip)
        }
        relinkChipNeighbours()
        renderChipStates()
        val targetId = if (focusedSource != null) {
            val position = groups.indexOfFirst { it.index == focusedSource }
            chipByIndex.getOrNull(position)?.id
        } else {
            focusedId
        }
        if (targetId != null) {
            chipByIndex.firstOrNull { it.id == targetId }?.requestFocus()
        }
    }

    private fun maybeAutoPlay(group: Group, extractor: VideoExtractor) {
        if (!pendingAutoPlay) return
        val m = media ?: return
        val savedServer = savedServerArg ?: run { pendingAutoPlay = false; return }
        if (group.index != (m.selected?.sourceIndex ?: -1)) return
        if (extractor.server.name != savedServer) return
        if (extractor.videos.isEmpty()) return
        pendingAutoPlay = false
        Logger.log("ANIME_SERVERS: auto-play saved server '$savedServer'")
        launchPlayback(group, extractor, autoVideo = true)
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
                    markLoading(g)
                }
            }
            if (groups[index].state is GroupState.Failed) markLoading(groups[index])
            renderChipStates()
            launchGroupResolution(index)
        } else if (PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode) == 1) {
            if (groups[index].state is GroupState.Loading) launchGroupResolution(index)
        }
    }

    private fun refreshAll() {
        Logger.log("ANIME_SERVERS: refresh all")
        groupJobs.values.toList().forEach { it.cancel() }
        groupJobs.clear()
        groups.forEach { markLoading(it) }
        renderChipStates()
        refreshRows()
        snackString(R.string.tmdb_servers_refreshed_all)
        lifecycleScope.launch { resolveAll() }
    }

    private fun refreshGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        Logger.log("ANIME_SERVERS: refresh group '${group.name}'")
        markLoading(group)
        renderChipStates()
        if (selectedIndex == index) refreshRows()
        lifecycleScope.launch { launchGroupResolution(index) }
    }

    // --- playback ---

    private fun playVideo(extractor: VideoExtractor, videoIdx: Int, group: Group) {
        val m = media ?: return
        m.selected?.sourceIndex = group.index
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            if (extractor.videos.isEmpty()) {
                Logger.log(
                    android.util.Log.ERROR,
                    "ANIME_SERVERS: no video from '${extractor.server.name}' in ${group.name}"
                )
                snackString("No video found in ${extractor.server.name}")
                return@launch
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
        if (launchingPlayer) return
        launchingPlayer = true
        val videoIdx = forcedVideo
            ?: m.selected?.video?.takeIf { it in extractor.videos.indices && autoVideo }
            ?: extractor.videos.indexOfFirst { it.preferred }.takeIf { it >= 0 }
            ?: 0
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
        m.selected?.sourceIndex = group.index
        m.selected?.server = extractor.server.name
        m.selected?.video = videoIdx
        model.saveSelected(m.id, m.selected!!)
        Logger.log(
            "ANIME_SERVERS: play '${m.name}' ep=${ep.number} via ${group.name}/${extractor.server.name} video=$videoIdx"
        )
        startExoplayer()
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
            is Row.Server -> 1
            is Row.Video -> 1
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

        fun focusFirstFocusableRow(): Boolean {
            val position = rows.indexOfFirst { isFocusableRow(it) }
            if (position < 0) return false
            fun focus(): Boolean {
                val holder = serversBinding.tmdbStreamsRows
                    .findViewHolderForAdapterPosition(position) ?: return false
                return when (holder) {
                    is HeadVH -> holder.binding.tmdbStreamsGroupRefresh.requestFocus()
                    else -> holder.itemView.requestFocus()
                }
            }
            if (focus()) return true
            serversBinding.tmdbStreamsRows.scrollToPosition(position)
            serversBinding.tmdbStreamsRows.post { focus() }
            return true
        }

        private fun isFocusableRow(row: Row): Boolean = when (row) {
            is Row.Head, is Row.Video -> true
            else -> false
        }

        private fun isLastFocusableRow(position: Int): Boolean {
            if (position < 0) return false
            for (i in position + 1 until rows.size) {
                if (isFocusableRow(rows[i])) return false
            }
            return true
        }

        private fun trapRowKeys(position: Int, keyCode: Int, event: android.view.KeyEvent): Boolean {
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
            return when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> isLastFocusableRow(position)
                else -> false
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int
        ) {
            when (val row = rows[position]) {
                is Row.Head -> (holder as HeadVH).bind(row.group)
                is Row.Server -> {
                    val sIdx = (row.group.state as? GroupState.Ready)
                        ?.servers?.indexOfFirst { it.name == row.server.name } ?: -1
                    (holder as ItemVH).bindServer(row.group, row.server, sIdx <= 0)
                }
                is Row.Video -> (holder as ItemVH).bindVideo(
                    row.group, row.extractor, row.videoIdx,
                    row.isFirst, row.isLast, row.server,
                )
                is Row.Text -> (holder as TextVH).bind(row.text)
            }
        }

        private inner class HeadVH(val binding: ItemTmdbStreamsGroupBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.isFocusable = false
                binding.tmdbStreamsGroupRefresh.setOnKeyListener { _, keyCode, event ->
                    trapRowKeys(bindingAdapterPosition, keyCode, event)
                }
            }

            fun bind(group: Group) {
                val logo = group.logoUrl?.takeIf { it.isNotBlank() }
                when {
                    logo != null -> {
                        binding.tmdbStreamsGroupLogo.isVisible = true
                        binding.tmdbStreamsGroupLogo.loadImage(logo)
                    }
                    group.icon != null -> {
                        binding.tmdbStreamsGroupLogo.isVisible = true
                        binding.tmdbStreamsGroupLogo.setImageDrawable(group.icon)
                    }
                    else -> binding.tmdbStreamsGroupLogo.isVisible = false
                }
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
                (binding.root as androidx.cardview.widget.CardView).apply {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    cardElevation = 0f
                }
                itemView.isFocusable = true
                itemView.setOnKeyListener { _, keyCode, event ->
                    trapRowKeys(bindingAdapterPosition, keyCode, event)
                }
                val r = 16f * itemView.resources.displayMetrics.density
                FocusEffectUtil.applyFocusListener(
                    itemView,
                    floatArrayOf(r, r, r, r, r, r, r, r),
                )
            }

            private fun setRowMargins(topDp: Int, bottomDp: Int) {
                val lp = itemView.layoutParams as? RecyclerView.LayoutParams ?: return
                val d = resources.displayMetrics.density
                lp.topMargin = (topDp * d).toInt()
                lp.bottomMargin = (bottomDp * d).toInt()
                itemView.layoutParams = lp
            }

            /** SubHead-style line above the merged card (TMDB spec). */
            fun bindServer(group: Group, server: VideoServer, isFirstServer: Boolean) {
                binding.root.background = null
                val (cleanName, _, parsedAudio) = splitServerTitle(server.name)
                binding.urlQuality.text = cleanName.ifBlank { server.name }
                binding.urlQuality.textSize = 14f
                binding.urlQuality.alpha = 0.6f
                binding.urlQuality.setTextColor(
                    binding.root.context.getThemeColor(
                        com.google.android.material.R.attr.colorOnSurface
                    )
                )
                val audio = parsedAudio?.uppercase()
                    ?: server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()
                binding.urlNote.isVisible = false
                binding.urlAudio.isVisible = false
                binding.urlTag.text = audio
                binding.urlTag.isVisible = !audio.isNullOrBlank()
                binding.urlSize.isVisible = false
                binding.urlSub.isVisible = false
                binding.urlDownload.isVisible = false
                binding.root.setOnClickListener(null)
                itemView.isClickable = false
                itemView.isFocusable = false
                setRowMargins(if (isFirstServer) 12 else 12, 4)
            }

            /** Segment of the merged grey card; first row carries the top rim. */
            fun bindVideo(
                group: Group,
                extractor: VideoExtractor,
                videoIdx: Int,
                isFirst: Boolean,
                isLast: Boolean,
                server: VideoServer?,
            ) {
                val video = extractor.videos.getOrNull(videoIdx)
                GlassPill.applyServerRowCard(
                    binding.root,
                    cornerRadiusDp = 14f,
                    topRounded = isFirst,
                    bottomRounded = isLast,
                    showTopRim = isFirst,
                )
                binding.urlQuality.text =
                    video?.quality?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto"
                binding.urlQuality.textSize = 15f
                binding.urlQuality.alpha = 1f
                binding.urlQuality.setTextColor(
                    binding.root.context.getThemeColor(
                        com.google.android.material.R.attr.colorSecondary
                    )
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
                    binding.urlNote.isVisible = false
                } else {
                    binding.urlNote.text = note
                    binding.urlNote.isVisible = true
                }
                val audio = if (server != null) {
                    (splitServerTitle(server.name).third?.uppercase())
                        ?: server.extraData?.get("audio")?.takeIf { it.isNotBlank() }?.uppercase()
                } else null
                binding.urlTag.text = audio
                binding.urlTag.isVisible = !audio.isNullOrBlank()
                binding.urlSub.isVisible = true
                if (downloadMode) {
                    val size = video?.size
                    if (size != null && size > 0) {
                        binding.urlSize.text = sizeLabel(size)
                        binding.urlSize.isVisible = true
                    } else {
                        binding.urlSize.isVisible = false
                    }
                    binding.urlDownload.isVisible = true
                    binding.urlDownload.setOnClickListener(null)
                    binding.root.setOnClickListener {
                        enqueueDownload(group, extractor, video)
                    }
                } else {
                    binding.urlSize.isVisible = false
                    binding.urlDownload.isVisible = false
                    binding.root.setOnClickListener { playVideo(extractor, videoIdx, group) }
                }
                itemView.isFocusable = true
                setRowMargins(
                    if (isFirst) (if (server != null) 6 else 0) else 1,
                    0,
                )
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

    private fun sizeLabel(sizeMb: Double): String =
        if (sizeMb >= 1024) {
            String.format(java.util.Locale.US, "%.2f GB", sizeMb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.2f MB", sizeMb)
        }

    private fun enqueueDownload(group: Group, extractor: VideoExtractor, video: Video?) {
        val v = video ?: return
        val m = media ?: return
        val ep = m.anime?.episodes?.getEpisode(epKey) ?: group.episode ?: return
        val queued = DownloadManager.enqueue(m, ep, extractor, v, group.name)
        if (queued != null) {
            snackString(getString(R.string.downloading))
        } else {
            snackString(getString(R.string.download_not_found))
        }
        Logger.log(
            "ANIME_SERVERS: download '${m.name}' ep=${ep.number} via ${group.name}/" +
                "${extractor.server.name} q=${v.quality} queued=${queued != null}"
        )
    }
}
