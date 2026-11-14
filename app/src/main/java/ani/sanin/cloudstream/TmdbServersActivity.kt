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
import ani.sanin.databinding.ItemTmdbStreamsExtraBinding
import ani.sanin.databinding.ItemTmdbStreamsGroupBinding
import ani.sanin.getThemeColor
import ani.sanin.databinding.ItemTmdbStreamsSubheadBinding
import ani.sanin.databinding.ItemTmdbStreamsTextBinding
import ani.sanin.databinding.ItemTmdbServerRowBinding
import ani.sanin.limitedAsyncMap
import ani.sanin.loadImage
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import ani.sanin.settings.StremioAddon
import ani.sanin.settings.StremioAddonClient
import ani.sanin.settings.StremioAddonManager
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.GlassPill
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View

class TmdbServersActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_SEASON = "season"
        const val ARG_EPISODE = "episode"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
        const val ARG_IMDB_ID = "imdbId"
        const val ARG_SOURCE_ADDON = "sourceAddon"
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
        var rank: Int = 1
        var arrivalSeq: Int = Int.MAX_VALUE
        var order: Int = 0
    }

    private sealed class Row {
        data class Head(val group: Group) : Row()

        data class Stream(
            val link: TmdbStreamResolver.StreamResult.PlayableLink,
            val group: Group,
        ) : Row()

        data class Info(val link: TmdbStreamResolver.StreamResult.PlayableLink, val addon: StremioAddon? = null) : Row()
        data class Extra(val media: TmdbMedia, val addon: StremioAddon? = null) : Row()
        data class SubHead(val label: String) : Row()
        data class Text(val text: String) : Row()
    }

    private var mediaId = 0
    private var mediaType = "movie"
    private var season: Int? = null
    private var epNumber: Int? = null
    private var pluginSourceId: String? = null
    private var pluginUrl: String? = null
    private var imdbId: String? = null
    private var sourceAddonUrl: String? = null
    private val pluginEpisodes = mutableMapOf<Int, List<TmdbEpisode>>()
    private var launchingPlayer = false
    private var initialFocusApplied = false

    private lateinit var serversBinding: ActivityTmdbServersBinding
    private var detail: TmdbDetail? = null

    private val groups = mutableListOf<Group>()
    private val chipByIndex = mutableListOf<Chip>()
    private val rows = mutableListOf<Row>()
    private var selectedIndex = 0

    private val rowsAdapter = RowsAdapter()
    private var headerCollapseMax = 0
    private var pluginLoad: LoadResponse? = null
    private val groupJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val arrivalCounter = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onResume() {
        super.onResume()
        launchingPlayer = false
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, 0)
        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        val seasonArg = intent.getIntExtra(ARG_SEASON, 0)
        val epArg = intent.getIntExtra(ARG_EPISODE, 0)
        pluginSourceId = intent.getStringExtra(ARG_PLUGIN_SOURCE)
        pluginUrl = intent.getStringExtra(ARG_PLUGIN_URL)
        imdbId = intent.getStringExtra(ARG_IMDB_ID)?.takeIf { it.isNotBlank() }
        sourceAddonUrl = intent.getStringExtra(ARG_SOURCE_ADDON)?.takeIf { it.isNotBlank() }
        if (seasonArg > 0) season = seasonArg
        if (epArg > 0) epNumber = epArg

        Logger.log(
            "TMDB_SERVERS: open mediaId=$mediaId type=$mediaType S$season E$epNumber " +
                "plugin=${pluginUrl != null}"
        )

        serversBinding = ActivityTmdbServersBinding.inflate(layoutInflater)
        setContentView(serversBinding.root)
        val topBarBasePadding = (6 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(serversBinding.tmdbServersTopBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, topBarBasePadding + top, v.paddingRight, v.paddingBottom)
            insets
        }
        serversBinding.tmdbStreamsRows.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
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
            if (!checked) PrefManager.setCustomVal("tmdb_source_$mediaId", "")
        }
        serversBinding.tmdbServersScroll.post { setupCollapse() }

        lifecycleScope.launch {
            val pluginLoadRes = if (pluginUrl != null) {
                loadPlugin(pluginUrl!!)
            } else null
            if (pluginUrl != null && pluginLoadRes == null) {
                return@launch
            }
            if (pluginUrl == null) {
                var d = withContext(Dispatchers.IO) { Tmdb.detail(mediaType, mediaId) }
                if (d == null && imdbId != null) {
                    d = ImdbMedia.loadDetail(mediaType, imdbId!!, sourceAddonUrl)
                }
                if (d == null) {
                    snackString("Could not load details")
                    finish()
                    return@launch
                }
                detail = d
            }
            detail = pluginLoadRes ?: detail
            if (isFinishing || isDestroyed) return@launch
            bindHeader(detail!!)
            buildGroups()
            selectedIndex = applyDefaultSelection()
            buildChips()
            refreshRows()
            resolveAll()
        }
        wireServerFocusChain()
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
        headerCollapseMax = (stickyTop - topBar.height).coerceAtLeast(0)
        val progress = if (headerCollapseMax > 0) {
            (scrollY.toFloat() / headerCollapseMax).coerceIn(0f, 1f)
        } else 0f
        val pinned = (scrollY - headerCollapseMax).coerceAtLeast(0)
        sticky.translationY = -pinned.toFloat()
        backdrop.translationY = scrollY * 0.35f
        topBar.background?.alpha = (progress * 255).toInt().coerceIn(0, 255)
    }

    private fun bindHeader(d: TmdbDetail) {
        val logo = if (PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) {
            Tmdb.logoUrl(d)
        } else null
        if (logo != null) {
            serversBinding.tmdbServersCompactLogo.isVisible = true
            serversBinding.tmdbServersCompactTitle.isVisible = false
            serversBinding.tmdbServersCompactLogo.loadImage(logo)
        } else {
            serversBinding.tmdbServersCompactLogo.isVisible = false
            serversBinding.tmdbServersCompactTitle.isVisible = true
            serversBinding.tmdbServersCompactTitle.text = d.displayTitle
        }
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
        serversBinding.tmdbServersDivider.background = android.graphics.drawable.ColorDrawable(divider)
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
        pluginSourceId?.let { pluginSourceId ->
            val source = CsRepos.installed(this).firstOrNull { it.id == pluginSourceId }
            if (source != null) {
                groups.add(Group(source.id, source.name, source.iconUrl, source, pluginLoad = pluginLoad))
                Logger.log("TMDB_SERVERS: groups total=1 (plugin mode)")
                return
            }
            Logger.log("TMDB_SERVERS: plugin source '$pluginSourceId' not installed")
        }
        val csSources = CsRepos.installed(this)
        csSources.forEach { source ->
            groups.add(Group(source.id, source.name, source.iconUrl, source))
        }
        val stremio = StremioAddonManager.installed()
        stremio.forEach { addon ->
            val syntheticSource = CsInstalledSource(
                id = StremioAddonManager.sourceId(addon),
                name = addon.manifest.name,
                version = 1,
                type = "movie",
                lang = "en",
                url = addon.transportUrl,
                repoUrl = "",
                iconUrl = addon.manifest.logo,
            )
            groups.add(
                Group(
                    syntheticSource.id,
                    syntheticSource.name,
                    syntheticSource.iconUrl,
                    syntheticSource,
                    addon = addon,
                )
            )
        }
        groups.forEachIndexed { index, group -> group.order = index }
    }

    private fun buildChips(focusKey: String? = null) {
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
            chip.tag = group.key
            chip.text = group.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(
                resources.getColorStateList(R.color.chip_text_color)
            )
            chip.isChecked = index == selectedIndex
            chip.setOnClickListener {
                val currentIndex = groups.indexOfFirst { it.key == group.key }
                if (currentIndex >= 0) selectGroup(currentIndex)
            }
            FocusEffectUtil.applyFocusListener(chip)
            chipByIndex.add(chip)
            serversBinding.tmdbStreamsChips.addView(chip)
        }
        applyChipFocusChain()
        if (focusKey != null) {
            val index = groups.indexOfFirst { it.key == focusKey }
            val chip = chipByIndex.getOrNull(index)
            if (chip != null) {
                if (chip.isAttachedToWindow) chip.requestFocus()
                else serversBinding.tmdbStreamsChips.post { chip.requestFocus() }
            }
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

        val downToChipsOrRows = View.OnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN ||
                keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                return@OnKeyListener false
            }
            val rect = android.graphics.Rect()
            val chipsVisible = serversBinding.tmdbStreamsChips
                .getGlobalVisibleRect(rect) && rect.height() > 0
            if (chipsVisible && focusSelectedChip()) true
            else rowsAdapter.focusFirstFocusableRow()
        }
        refresh.setOnKeyListener(downToChipsOrRows)
        makeDefault.setOnKeyListener(downToChipsOrRows)
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
        val downChipId = chipByIndex.firstOrNull { it.hasFocus() }?.id
            ?: chipByIndex.getOrNull(selectedIndex)?.id
            ?: chipByIndex.firstOrNull()?.id
        if (downChipId != null) {
            serversBinding.tmdbServersRefreshAll.nextFocusDownId = downChipId
            serversBinding.tmdbServersMakeDefault.nextFocusDownId = downChipId
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
                is GroupState.Ready -> {
                    val hasContent = state.links.any { it.url.isNotBlank() } ||
                        state.recommendations.isNotEmpty() || state.infos.isNotEmpty()
                    if (hasContent) group.name else "${group.name} (empty)"
                }
            }
        }
    }

    private fun focusedRow(): Pair<Row, Int>? {
        val focused = currentFocus ?: return null
        if (!isInsideRows(focused)) return null
        val item = serversBinding.tmdbStreamsRows.findContainingItemView(focused) ?: return null
        val position = serversBinding.tmdbStreamsRows.getChildAdapterPosition(item)
        val row = rows.getOrNull(position) ?: return null
        return row to item.top
    }

    private fun isInsideRows(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === serversBinding.tmdbStreamsRows) return true
            current = current.parent as? View
        }
        return false
    }

    private fun rowKey(row: Row): String? = when (row) {
        is Row.Head -> "h:${row.group.key}"
        is Row.Stream -> "s:${row.group.key}:${row.link.url}"
        is Row.Info -> "i:${row.link.url}"
        is Row.Extra -> "e:${row.media.type}:${row.media.id}"
        is Row.SubHead, is Row.Text -> null
    }

    private fun restoreRowFocus(key: String, offset: Int) {
        val position = rows.indexOfFirst { rowKey(it) == key }
        if (position >= 0) {
            (serversBinding.tmdbStreamsRows.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, offset)
            serversBinding.tmdbStreamsRows.doOnNextLayout {
                if (isFinishing || isDestroyed) return@doOnNextLayout
                if (!rowsAdapter.focusRowAt(position)) rowsAdapter.focusFirstFocusableRow()
            }
        } else {
            serversBinding.tmdbStreamsRows.post {
                if (!isFinishing && !isDestroyed) rowsAdapter.focusFirstFocusableRow()
            }
        }
    }

    private fun refreshRows(scrollToTop: Boolean = true) {
        val focusedRow = focusedRow()
        val restoreKey = focusedRow?.first?.let { rowKey(it) }
        val restoreOffset = focusedRow?.second ?: 0
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
                state.recommendations.forEach { rows.add(Row.Extra(it, group.addon)) }
                state.infos.forEach { rows.add(Row.Info(it, group.addon)) }
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
        if (scrollToTop) serversBinding.tmdbStreamsRows.scrollToPosition(0)
        if (restoreKey != null) restoreRowFocus(restoreKey, restoreOffset)
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
        val mode = PrefManager.getVal<Int>(PrefName.TmdbServersLoadMode)
        val targets = if (mode == 1) {
            listOf(selectedIndex)
        } else {
            groups.indices.sortedBy { if (it == selectedIndex) 0 else 1 }
        }
        if (mode == 1) {
            groups.forEachIndexed { i, g ->
                if (i != selectedIndex) {
                    groupJobs.remove(g.key)?.cancel()
                    markLoading(g)
                }
            }
            if (groups.getOrNull(selectedIndex)?.state is GroupState.Failed) {
                groups.getOrNull(selectedIndex)?.let { markLoading(it) }
            }
            renderChipStates()
            refreshRows()
        }
        Logger.log("TMDB_SERVERS: resolveAll mode=$mode targets=$targets")
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
                Logger.log("TMDB_SERVERS: load mode set $mode")
                lifecycleScope.launch { resolveAll() }
            }
            .show()
    }

    private fun launchGroupResolution(index: Int) {
        val group = groups.getOrNull(index) ?: return
        val d = detail ?: return
        groupJobs.remove(group.key)?.cancel()
        groupJobs[group.key] = lifecycleScope.launch(Dispatchers.IO) {
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
                    groupJobs.remove(g.key)?.cancel()
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
        Logger.log("TMDB_SERVERS: refresh all")
        groupJobs.values.toList().forEach { it.cancel() }
        groupJobs.clear()
        TmdbStreamResolver.invalidateLinks(mediaId)
        groups.forEach { markLoading(it) }
        renderChipStates()
        refreshRows()
        snackString(R.string.tmdb_servers_refreshed_all)
        lifecycleScope.launch { resolveAll() }
    }

    private fun refreshGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        Logger.log("TMDB_SERVERS: refresh group '${group.name}'")
        TmdbStreamResolver.invalidateLinks(mediaId, group.name)
        markLoading(group)
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
                    TmdbExtras.parseStremioTarget(link.externalUrl) != null ||
                        TmdbExtras.parseExtraDescription(link.rawDetails ?: link.details)
                            ?.isTemplateMatch == true
                }
                val infos = external.filter { !canCard(it) }
                val extras = external - infos.toSet()
                val recommendations = extras.limitedAsyncMap(4) { link ->
                    runCatching { TmdbExtras.resolveExtraMedia(link, mediaType) }.getOrNull()
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
        val playable = when (val s = group.state) {
            is GroupState.Ready ->
                s.links.count { it.url.isNotBlank() } + s.recommendations.size + s.infos.size
            else -> 0
        }
        group.rank = when {
            playable > 0 -> 0
            group.state is GroupState.Loading -> 1
            else -> 2
        }
        if (group.rank == 0 && group.arrivalSeq == Int.MAX_VALUE) {
            group.arrivalSeq = arrivalCounter.getAndIncrement()
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val selectedKey = groups.getOrNull(selectedIndex)?.key
            val focusedKey = chipByIndex.indexOfFirst { it.hasFocus() }
                .takeIf { it >= 0 }
                ?.let { groups.getOrNull(it)?.key }
            groups.sortWith(compareBy({ it.rank }, { it.arrivalSeq }, { it.order }))
            selectedIndex = groups.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: selectedIndex
            syncChipsAfterSort(focusedKey)
            if (groups.getOrNull(selectedIndex) === group) refreshRows(scrollToTop = false)
        }
    }

    private fun syncChipsAfterSort(focusedKey: String? = null) {
        if (chipByIndex.size != groups.size) {
            buildChips(focusedKey)
            return
        }
        val currentOrder = chipByIndex.mapNotNull { it.tag as? String }
        val newOrder = groups.map { it.key }
        if (currentOrder == newOrder) {
            renderChipStates()
            relinkChipNeighbours()
            return
        }
        val focusedId = currentFocus?.id
        val chipsByKey = chipByIndex.groupBy { it.tag as? String }
        val used = HashMap<String?, Int>()
        val ordered = ArrayList<Chip>(chipByIndex.size)
        groups.forEach { group ->
            val list = chipsByKey[group.key] ?: return@forEach
            val position = used[group.key] ?: 0
            if (position < list.size) {
                ordered.add(list[position])
                used[group.key] = position + 1
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
        val target = if (focusedKey != null) {
            val position = groups.indexOfFirst { it.key == focusedKey }
            chipByIndex.getOrNull(position)
        } else if (focusedId != null) {
            chipByIndex.firstOrNull { it.id == focusedId }
        } else null
        if (target != null) {
            if (target.isAttachedToWindow) target.requestFocus()
            else parent.post { target.requestFocus() }
        }
    }

    private fun play(link: TmdbStreamResolver.StreamResult.PlayableLink, group: Group) {
        if (launchingPlayer) return
        val state = group.state as? GroupState.Ready ?: return
        val d = detail ?: return
        val playable = state.links.filter { it.url.isNotBlank() }
        if (playable.isEmpty()) return
        launchingPlayer = true
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
            val launched = runCatching {
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
            }.getOrElse {
                Logger.log(android.util.Log.ERROR, "TMDB_SERVERS: launch failed: ${it.message}")
                false
            }
            if (!launched) runOnUiThread { launchingPlayer = false }
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

    private fun openMedia(media: TmdbMedia, addon: StremioAddon? = null) {
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
        if (!media.imdbId.isNullOrBlank()) {
            Logger.log("TMDB_SERVERS: extras item unresolved on tmdb, trying source addon probe type=$type imdb=${media.imdbId} title='${media.displayTitle}'")
            openImdbDetails(type, media.imdbId!!, addon, media.displayTitle)
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
                openMedia(match, addon)
                return@launch
            }
            val cinemeta = withContext(Dispatchers.IO) {
                runCatching { StremioAddonClient.searchCinemetaId(type, title) }.getOrNull()
            }
            if (cinemeta != null) {
                Logger.log("TMDB_SERVERS: resolved extras title '$title' to cinemeta ${cinemeta.first}/${cinemeta.second}")
                openImdbDetails(cinemeta.first, cinemeta.second, addon, title)
            } else {
                snackString("No TMDB match for $title")
            }
        }
    }

    private fun openImdbDetails(type: String, imdb: String, addon: StremioAddon? = null, title: String? = null) {
        Logger.log("TMDB_SERVERS: opening imdb details type=$type imdb=$imdb addon=${addon?.manifest?.name}")
        startActivity(
            android.content.Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                .putExtra(TmdbDetailsActivity.ARG_IMDB_ID, imdb)
                .putExtra(TmdbDetailsActivity.ARG_TITLE, title)
                .apply { addon?.let { putExtra(TmdbDetailsActivity.ARG_SOURCE_ADDON, it.transportUrl) } }
        )
    }

    private fun linkLabel(title: String?) = title

    private fun openExternal(link: TmdbStreamResolver.StreamResult.PlayableLink, addon: StremioAddon? = null) {
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
        val target = TmdbExtras.parseStremioTarget(url) ?: run {
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
                Logger.log("TMDB_SERVERS: stremio link unresolved on tmdb, opening cinemeta id=$imdbId")
                openImdbDetails(typeHint, imdbId, addon, link.label.trim().takeIf { it.isNotBlank() })
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
            is Row.Extra -> 3
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
                3 -> ExtraVH(ItemTmdbStreamsExtraBinding.inflate(inf, parent, false))
                4 -> SubHeadVH(ItemTmdbStreamsSubheadBinding.inflate(inf, parent, false))
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

        fun focusRowAt(position: Int): Boolean {
            if (position !in rows.indices) return false
            val holder = serversBinding.tmdbStreamsRows
                .findViewHolderForAdapterPosition(position) ?: return false
            return when (holder) {
                is HeadVH -> holder.binding.tmdbStreamsGroupRefresh.requestFocus()
                else -> holder.itemView.requestFocus()
            }
        }

        private fun nextFocusablePosition(from: Int): Int {
            for (i in from.coerceAtLeast(0) until rows.size) {
                if (isFocusableRow(rows[i])) return i
            }
            return -1
        }

        private fun isFocusableRow(row: Row): Boolean = when (row) {
            is Row.Head, is Row.Stream, is Row.Info, is Row.Extra -> true
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
            holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            position: Int
        ) {
            when (val row = rows[position]) {
                is Row.Head -> (holder as HeadVH).bind(row.group)
                is Row.Stream -> (holder as ItemVH).bindStream(row.link, row.group)
                is Row.Info -> (holder as ItemVH).bindInfo(row.link, row.addon)
                is Row.Extra -> (holder as ExtraVH).bind(row.media, row.addon)
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
                binding.tmdbStreamsGroupRefresh.setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    ) {
                        val next = nextFocusablePosition(bindingAdapterPosition + 1)
                        if (next >= 0) {
                            serversBinding.tmdbStreamsRows.scrollToPosition(next)
                            serversBinding.tmdbStreamsRows.doOnNextLayout {
                                if (isFinishing || isDestroyed) return@doOnNextLayout
                                if (!focusRowAt(next)) focusFirstFocusableRow()
                            }
                            return@setOnKeyListener true
                        }
                    }
                    trapRowKeys(bindingAdapterPosition, keyCode, event)
                }
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
                (binding.root as androidx.cardview.widget.CardView).apply {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    cardElevation = 0f
                }
                GlassPill.applyServerRowCard(binding.root, cornerRadiusDp = 14f, showTopRim = true)
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

            fun bindStream(link: TmdbStreamResolver.StreamResult.PlayableLink, group: Group) {
                val (quality, hint) = splitStreamLabel(link.label)
                binding.urlQuality.text = quality
                val note = listOfNotNull(
                    link.host.takeIf { it.isNotBlank() },
                    multiLineNote(link.details)
                        ?: hint?.takeIf { it.isNotBlank() },
                ).joinToString("\n")
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
                itemView.isFocusable = true
                itemView.isClickable = true
            }

            fun bindInfo(link: TmdbStreamResolver.StreamResult.PlayableLink, addon: StremioAddon? = null) {
                binding.urlQuality.text = link.label
                val note = multiLineNote(link.details)
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
                    binding.root.setOnClickListener { openExternal(link, addon) }
                    itemView.isFocusable = true
                    itemView.isClickable = true
                } else {
                    binding.root.setOnClickListener(null)
                    itemView.isClickable = false
                }
            }
        }

        private inner class ExtraVH(val binding: ItemTmdbStreamsExtraBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            init {
                (binding.root as androidx.cardview.widget.CardView).apply {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                    cardElevation = 0f
                }
                GlassPill.applyServerRowCard(binding.root, cornerRadiusDp = 14f, showTopRim = true)
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

            fun bind(m: TmdbMedia, addon: StremioAddon? = null) {
                binding.tmdbExtraPoster.loadImage(
                    Rpdb.posterUrl(m.type, m.id) ?: (
                        Tmdb.imageUrl(m.posterPath ?: m.backdropPath, 300)
                            ?: if (m.posterPath?.startsWith("http") == true) m.posterPath else null
                        )
                )
                binding.tmdbExtraTitle.text = m.displayTitle
                val meta = listOfNotNull(
                    String.format(java.util.Locale.US, "★ %.1f", m.voteAverage)
                        .takeIf { m.voteAverage > 0 },
                    (m.releaseDate ?: m.firstAirDate).takeIf { !it.isNullOrBlank() },
                    String.format(java.util.Locale.US, "Popularity %.1f", m.popularity)
                        .takeIf { m.popularity > 0 },
                ).joinToString("  •  ")
                if (meta.isBlank()) {
                    binding.tmdbExtraMeta.isVisible = false
                } else {
                    binding.tmdbExtraMeta.text = meta
                    binding.tmdbExtraMeta.isVisible = true
                }
                val overview = m.overview?.takeIf { it.isNotBlank() }
                if (overview == null) {
                    binding.tmdbExtraOverview.isVisible = false
                } else {
                    binding.tmdbExtraOverview.text = overview
                    binding.tmdbExtraOverview.isVisible = true
                }
                binding.root.setOnClickListener { openMedia(m, addon) }
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

    private fun multiLineNote(text: String?): String? {
        val cleaned = text?.replace('\u200b', ' ')?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return null
        val lines = cleaned.split(Regex("\n|\\s*[·•|]\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }
}
