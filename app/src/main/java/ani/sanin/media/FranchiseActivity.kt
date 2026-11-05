package ani.sanin.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbListDialogFragment
import ani.sanin.connections.anilist.AnilistFranchiseRanks
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.LogoApi
import ani.sanin.databinding.ActivityFranchiseBinding
import ani.sanin.databinding.ItemFranchiseEntryBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.toPx
import ani.sanin.ui.LensButtonBackground
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

class FranchiseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFranchiseBinding

    private var franchise: Franchise? = null

    private val availableTypes = LinkedHashSet<FranchiseType>()

    private var selectedType: FranchiseType? = null

    private var preselectType: FranchiseType? = null

    private var totalEntries = 0

    private var pillTabs = mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var pillSelected = mutableIntStateOf(0)

    private val anilistStatuses = HashMap<Int, String>()

    private val simklStatuses = HashMap<Int, String>()

    private val rowBindings = ArrayList<ItemFranchiseEntryBinding>()
    private var shownEntries: List<FranchiseEntry> = emptyList()

    private var lastStatusLoadAt = 0L

    override fun onResume() {
        super.onResume()

        if (lastStatusLoadAt == 0L) return
        if (shownEntries.isEmpty()) return
        if (System.currentTimeMillis() - lastStatusLoadAt < STATUS_REFRESH_DEBOUNCE_MS) return
        loadListStatuses(shownEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        val card = intent.getFranchiseExtra(EXTRA_FRANCHISE)
        if (card == null) {

            snackString(getString(R.string.franchise_load_failed))
            finish()
            return
        }
        franchise = card

        preselectType = intent.getStringExtra(EXTRA_PRESELECT_TYPE)?.let { name ->
            FranchiseType.entries.firstOrNull { it.name == name }
        }

        binding = ActivityFranchiseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.franchiseName.text = card.name
        binding.franchiseBack.setSafeOnClickListener { finish() }
        applyWindowInsets()

        val ordered = orderedEntries(card)
        totalEntries = ordered.size
        binding.franchiseEntryCount.text =
            getString(R.string.franchise_entries_count, totalEntries)

        ordered.mapNotNull { it.type }.forEach { availableTypes.add(it) }

        binding.franchiseFilterPill.bind(
            tabs = pillTabs.value,
            selectedIndex = pillSelected.value,
            onTabSelected = { selectFilter(it) },
        )
        rebuildPillTabs()
        applyPreselect()
        buildRows(ordered)

        if (ordered.any { it.synopsis.isNullOrBlank() || it.type == null } &&
            ordered.any { it.anilistId != null }
        ) {

            enrichFromAnilist()
        } else {
            loadListStatuses(ordered)
        }
        loadLogoArt(card)
    }

    private fun loadLogoArt(card: Franchise) {
        if (!PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) return
        val logo = card.logoUrl
        if (logo != null) {
            showLogo(logo)
            return
        }

        val id = card.sortOrder.lastOrNull { it.anilistId != null }?.anilistId ?: return
        lifecycleScope.launch {
            val art = withContext(Dispatchers.IO) { LogoApi.getClearartUrl(id) }
            showLogo(art)
        }
    }

    private fun showLogo(url: String?) {
        val logo = binding.franchiseLogo
        if (url.isNullOrBlank()) {
            logo.isVisible = false
            return
        }
        logo.isVisible = true
        logo.loadImage(url)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.franchiseRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.franchiseHeader.updatePadding(top = bars.top + 8.toPx)
            binding.franchiseRows.updatePadding(bottom = bars.bottom + 24.toPx)
            insets
        }
    }

    private fun orderedEntries(card: Franchise): List<FranchiseEntry> {

        val curated: Boolean = PrefManager.getVal(PrefName.KitsuCuratedOrder)
        return if (curated) card.entries else card.sortOrder
    }

    private fun rebuildPillTabs() {
        val card = franchise ?: return
        val all = orderedEntries(card)
        val tabs = ArrayList<LibraryStatusTab>()
        tabs.add(LibraryStatusTab(getString(R.string.franchise_filter_all), all.size))
        availableTypes.forEach { type ->
            tabs.add(LibraryStatusTab(getString(type.labelRes()), all.count { it.type == type }))
        }
        pillTabs.value = tabs
        if (pillSelected.value > tabs.lastIndex) pillSelected.value = 0
        selectedType = availableTypes.toList().getOrNull(pillSelected.value - 1)
        binding.franchiseFilterPill.bind(
            tabs = pillTabs.value,
            selectedIndex = pillSelected.value,
            onTabSelected = { selectFilter(it) },
        )
    }

    private fun selectFilter(index: Int) {
        selectedType = availableTypes.toList().getOrNull(index - 1)
        pillSelected.value = index
        refilter()
    }

    private fun applyPreselect() {
        val wanted = preselectType ?: return
        val index = availableTypes.indexOf(wanted)
        if (index < 0) return
        selectFilter(index + 1)
    }

    private fun refilter() {
        val all = franchise?.let { orderedEntries(it) } ?: return
        buildRows(all)
    }

    private fun buildRows(ordered: List<FranchiseEntry>) {
        val container = binding.franchiseRows
        container.removeAllViews()
        rowBindings.clear()

        val shown = ordered.filter { matches(it) }
        shownEntries = shown
        binding.franchiseEmpty.isVisible = shown.isEmpty()
        binding.franchiseProgress.isVisible = false

        binding.franchiseEntryCount.text = entryCountText(shown)

        shown.forEachIndexed { index, entry ->
            val row = ItemFranchiseEntryBinding.inflate(layoutInflater, container, false)
            bindRow(row, entry, index)
            container.addView(row.root)
            rowBindings.add(row)
        }
    }

    private fun matches(entry: FranchiseEntry) =
        selectedType == null || entry.type == selectedType

    private fun entryCountText(shown: List<FranchiseEntry>): String {
        val total = shown.size
        val head = if (selectedType == null) {
            resources.getQuantityString(R.plurals.franchise_count_total, total, total)
        } else {
            resources.getQuantityString(
                R.plurals.franchise_count_total_filtered,
                total,
                total,
                totalEntries,
            )
        }

        val parts = shown.mapNotNull { it.type }
            .groupingBy { it }
            .eachCount()
            .map { (type, count) -> typeCountPart(type, count) }

        return parts.joinToString(separator = "", prefix = head.toString())
    }

    private fun typeCountPart(type: FranchiseType, count: Int): String {
        return getString(
            R.string.franchise_count_part,
            resources.getQuantityString(type.countRes(), count, count),
        )
    }

    private fun bindRow(row: ItemFranchiseEntryBinding, entry: FranchiseEntry, index: Int) {
        val cardOnLeft = index % 2 == 0

        val parent = row.franchiseRow
        if (cardOnLeft) {
            if (parent.getChildAt(0) !== row.franchiseRowCardHolder) {
                parent.removeView(row.franchiseRowCardHolder)
                parent.addView(row.franchiseRowCardHolder, 0)
            }
            if (parent.getChildAt(1) !== row.franchiseRowSynopsisHolder) {
                parent.removeView(row.franchiseRowSynopsisHolder)
                parent.addView(row.franchiseRowSynopsisHolder, 1)
            }
        } else {
            if (parent.getChildAt(0) !== row.franchiseRowSynopsisHolder) {
                parent.removeView(row.franchiseRowSynopsisHolder)
                parent.addView(row.franchiseRowSynopsisHolder, 0)
            }
            if (parent.getChildAt(1) !== row.franchiseRowCardHolder) {
                parent.removeView(row.franchiseRowCardHolder)
                parent.addView(row.franchiseRowCardHolder, 1)
            }
        }

        row.franchiseRowBackdrop.loadImage(entry.posterUrl ?: entry.backdropUrl)

        row.franchiseRowScore.text = entry.score?.let {
            getString(R.string.franchise_score, it)
        }
        row.franchiseRowScore.isVisible = entry.score != null

        row.franchiseRowYear.text = entry.year
        row.franchiseRowYear.isVisible = entry.year.isNotBlank()

        row.franchiseRowTitle.text = entry.title

        row.franchiseRowSynopsis.text = entry.synopsis
            ?: getString(R.string.franchise_empty_synopsis)

        clampSynopsisToPoster(row)

        bindListStatus(row, entry)
        bindInfo(row, entry)

        row.franchiseRowCard.setSafeOnClickListener {
            openEntry(entry, MediaDetailsActivity.INFO_TAB)
        }
    }

    private fun clampSynopsisToPoster(row: ItemFranchiseEntryBinding) {
        val poster = row.franchiseRowCard
        val title = row.franchiseRowTitle
        val text = row.franchiseRowSynopsis
        val gap = resources.getDimensionPixelSize(R.dimen.franchise_synopsis_gap)

        text.post firstPass@{
            if (!text.isAttachedToWindow || poster.height <= 0) return@firstPass

            val lineHeight = text.lineHeight
            if (lineHeight <= 0) return@firstPass

            val previous = text.maxLines
            val budget = poster.height - title.height - gap
            val fits = (budget / lineHeight).coerceAtLeast(1)

            text.maxLines = Int.MAX_VALUE
            text.requestLayout()
            text.post {
                if (!text.isAttachedToWindow) return@post

                val needed = text.lineCount
                val chosen = when {
                    needed <= fits -> fits

                    fits > 1 -> fits - 1
                    else -> 1
                }
                text.maxLines = chosen
                if (text.maxLines != previous) text.requestLayout()
            }
        }
    }

    private fun bindListStatus(row: ItemFranchiseEntryBinding, entry: FranchiseEntry) {
        row.franchiseRowListStatus.text = statusLabel(entry) ?: getString(R.string.add_list)
        row.franchiseRowListStatus.setSafeOnClickListener {
            when {
                entry.anilistId != null -> showListEditor(entry.anilistId)
                entry.tmdbId != null -> showSimklListEditor(entry)
                else -> snackString(getString(R.string.franchise_load_failed))
            }
        }
    }

    private fun statusLabel(entry: FranchiseEntry): String? {
        val raw = when {
            entry.anilistId != null -> anilistStatuses[entry.anilistId] ?: entry.listStatus
            entry.tmdbId != null -> simklStatuses[entry.tmdbId]
            else -> entry.listStatus
        } ?: return null

        val labels = resources.getStringArray(R.array.status_anime)
        val index = if (entry.anilistId != null) {
            resources.getStringArray(R.array.status).indexOf(raw)
        } else {
            TmdbListDialogFragment.simklStatusToIndex(raw)
        }
        return labels.getOrNull(index)
    }

    private fun loadListStatuses(entries: List<FranchiseEntry>) {
        lastStatusLoadAt = System.currentTimeMillis()

        val anilistIds = entries.mapNotNull { it.anilistId }.distinct()
        if (anilistIds.isNotEmpty()) {
            lifecycleScope.launch {
                val statuses = withContext(Dispatchers.IO) {
                    AnilistFranchiseRanks.franchiseListStatuses(anilistIds)
                } ?: return@launch
                anilistStatuses.clear()
                anilistStatuses.putAll(statuses)
                refreshStatusChips()
            }
        }

        val tmdbIds = entries.filter { it.anilistId == null }
            .mapNotNull { it.tmdbId }
            .distinct()
        if (tmdbIds.isNotEmpty()) {
            lifecycleScope.launch {
                val statuses = withContext(Dispatchers.IO) {
                    runCatching {
                        val items = Simkl.getMovieLibrary() + Simkl.getShowLibrary()
                        val map = HashMap<Int, String>()
                        items.forEach { item ->
                            val ids = item.ids ?: return@forEach
                            val status = item.status ?: return@forEach
                            ids.tmdb?.let { map[it] = status }
                        }
                        map
                    }.getOrNull()
                } ?: return@launch
                simklStatuses.clear()
                simklStatuses.putAll(statuses)
                refreshStatusChips()
            }
        }
    }

    private fun refreshStatusChips() {
        rowBindings.forEachIndexed { index, row ->
            val entry = shownEntries.getOrNull(index) ?: return@forEachIndexed
            bindListStatus(row, entry)
        }
    }

    private fun bindInfo(row: ItemFranchiseEntryBinding, entry: FranchiseEntry) {
        row.franchiseRowInfo.setSafeOnClickListener {
            openEntry(entry, MediaDetailsActivity.INFO_TAB)
        }
        LensButtonBackground.apply(row.franchiseRowInfo)
        FocusEffectUtil.applyFocusListener(
            row.franchiseRowInfo,
            row.franchiseRowInfo,
            isCircular = true,
        )
    }

    private fun showListEditor(anilistId: Int) {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(LIST_DIALOG_TAG) != null) return

        val model: MediaDetailsViewModel by viewModels()
        model.loadMedia(
            Media(
                id = anilistId,
                name = "",
                nameRomaji = "",
                userPreferredName = "",
                isAdult = false,
            )
        )
        MediaListDialogFragment().apply {
            onSaved = { loadListStatuses(shownEntries) }
        }.show(fm, LIST_DIALOG_TAG)
    }

    private fun showSimklListEditor(entry: FranchiseEntry) {
        val tmdbId = entry.tmdbId ?: return
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(LIST_DIALOG_TAG) != null) return

        TmdbListDialogFragment.newInstance(
            type = entry.tmdbType ?: "movie",
            tmdbId = tmdbId,
            imdbId = null,
            anilistId = null,
            title = entry.title,
            year = entry.sortYear,
            cover = entry.posterUrl,
            totalEpisodes = null,
        ).apply {
            onSaved = { loadListStatuses(shownEntries) }
        }.show(fm, LIST_DIALOG_TAG)
    }

    private fun openEntry(entry: FranchiseEntry, tab: Int = MediaDetailsActivity.INFO_TAB) {
        entry.anilistId?.let { id ->
            startActivity(
                Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("mediaId", id)
                    putExtra(MediaDetailsActivity.TAB_TO_OPEN, tab)
                }
            )
            return
        }

        entry.tmdbId?.let { id ->

            startActivity(
                Intent(this, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, entry.tmdbType ?: "movie")
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
            )
            return
        }

        val url = entry.traktId?.let { "https://trakt.tv/movies/$it" }
        if (url == null) {
            snackString(getString(R.string.franchise_load_failed))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    private fun enrichFromAnilist() {
        val card = franchise ?: return
        binding.franchiseProgress.isVisible = true
        lifecycleScope.launch {
            val enriched = withContext(Dispatchers.IO) {
                AnilistFranchiseRanks.enrich(listOf(card))
            }

            val resolved = enriched.firstOrNull() ?: card
            franchise = resolved

            val updated = orderedEntries(resolved)
            binding.franchiseProgress.isVisible = false

            availableTypes.clear()
            updated.mapNotNull { it.type }.forEach { availableTypes.add(it) }
            rebuildPillTabs()
            buildRows(updated)
            loadListStatuses(updated)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getFranchiseExtra(name: String): Franchise? =
        getSerializableExtra(name) as? Franchise

    companion object {

        const val EXTRA_FRANCHISE = "franchise"

        const val EXTRA_PRESELECT_TYPE = "franchise_preselect_type"

        private const val LIST_DIALOG_TAG = "franchise_list_dialog"

        private const val STATUS_REFRESH_DEBOUNCE_MS = 1500L

        fun intent(context: Context, franchise: Franchise, preselect: FranchiseType? = null) =
            Intent(context, FranchiseActivity::class.java)
                .putExtra(EXTRA_FRANCHISE, franchise as Serializable)
                .putExtra(EXTRA_PRESELECT_TYPE, preselect?.name)
    }
}