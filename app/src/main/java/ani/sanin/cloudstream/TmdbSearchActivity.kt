package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.databinding.ActivityTmdbSearchBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbHistoryBinding
import ani.sanin.media.SearchFilterChipsAdapter
import ani.sanin.media.SearchSectionHeaderAdapter
import ani.sanin.snackString
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.sanin.loadImage
import ani.sanin.limitedAsyncMap
import ani.sanin.settings.StremioAddon
import ani.sanin.settings.StremioAddonClient
import ani.sanin.settings.StremioAddonManager
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.themes.ThemeManager

class TmdbSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTmdbSearchBinding
    private val adapter = TmdbSearchGridAdapter { media -> openDetails(media) }
    private val pluginAdapter = PluginSearchAdapter { item -> openPluginDetails(item) }
    private val selectedSourceIds = linkedSetOf(SOURCE_TMDB)
    private val fullSpanPositions = mutableSetOf<Int>()
    private val suggestionsAdapter = TmdbSuggestionsAdapter(
        onPick = { media ->
            closeSuggestions()
            suppressSuggestions = true
            binding.tmdbSearchInput.setText(media.displayTitle)
            openDetails(media)
        },
        onSeeAll = { query ->
            closeSuggestions()
            runSearch(query)
        },
        onClosed = { closeSuggestions() }
    )
    private val suggestionHandler = Handler(Looper.getMainLooper())
    private var suggestionJob: Job? = null
    private var lastSuggestionQuery: String? = null
    private var suppressSuggestions = false
    private var activeFilter: TmdbFilterResult? = null
    private var lastQuery: String? = null
    private val historyAdapter = HistoryAdapter { query -> runSearch(query) }
    private val filterChipsAdapter = SearchFilterChipsAdapter { chip -> onFilterChipClicked(chip) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityTmdbSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tmdbSearchGrid.adapter = adapter
        setupGrid()

        binding.tmdbSearchInput.post {
            when (TvKeyboardUtil.keyboardMode()) {
                0 -> TvKeyboardUtil.setupSystemKeyboard(binding.tmdbSearchInput)
                1 -> TvKeyboardUtil.setupEditTextWithToggle(
                    binding.tmdbSearchInput, binding.tmdbSearchKeyboardToggle
                )
                2 -> TvKeyboardUtil.setupEditTextForAlwaysVisible(binding.tmdbSearchInput)
            }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchKeyboardToggle)

        binding.tmdbSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val q = binding.tmdbSearchInput.text.toString().trim()
                if (q.isNotEmpty()) runSearch(q)
                true
            } else false
        }

        binding.tmdbSearchSuggestionsList.layoutManager = LinearLayoutManager(this)
        binding.tmdbSearchSuggestionsList.adapter = suggestionsAdapter
        binding.tmdbSearchFilterChips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.tmdbSearchFilterChips.adapter = filterChipsAdapter
        updateFilterChips()
        binding.tmdbSearchInput.doAfterTextChanged { text ->
            if (suppressSuggestions) {
                suppressSuggestions = false
                closeSuggestions()
                return@doAfterTextChanged
            }
            val q = text?.toString()?.trim().orEmpty()
            suggestionHandler.removeCallbacksAndMessages(null)
            if (q.length < 2) {
                closeSuggestions()
            } else {
                suggestionHandler.postDelayed({ fetchSuggestions(q) }, SUGGESTION_DEBOUNCE_MS)
            }
        }

        binding.tmdbSearchClearHistory.setOnClickListener {
            PrefManager.setVal(PrefName.TmdbSearchHistory, emptyList<String>())
            binding.tmdbSearchResultLayout.isVisible = false
            closeSuggestions()
            showHistory()
            snackString("Search history cleared")
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchClearHistory)
        LensButtonBackground.apply(binding.tmdbSearchClearHistory)

        binding.tmdbSearchFilter.setOnClickListener {
            val dialog = TmdbSearchFilterDialog.newInstance(
                onApply = { result -> runFiltered(result) },
                sources = selectedSourceIds.toSet(),
                onSourcesChanged = { ids ->
                    selectedSourceIds.clear()
                    if (ids.isEmpty()) selectedSourceIds.add(SOURCE_TMDB) else selectedSourceIds.addAll(ids)
                    updateFilterChips()
                }
            )
            dialog.show(supportFragmentManager, "tmdbFilter")
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchFilter)
        LensButtonBackground.apply(binding.tmdbSearchFilter)

        LensButtonBackground.apply(binding.tmdbSearchResultGrid)
        LensButtonBackground.apply(binding.tmdbSearchResultList)
        FocusEffectUtil.applyFocusListener(
            binding.tmdbSearchResultGrid,
            binding.tmdbSearchResultList
        )

        showHistory()
    }

    private fun fetchSuggestions(query: String) {
        if (query == lastSuggestionQuery && binding.tmdbSearchSuggestionsPanel.isVisible) return
        lastSuggestionQuery = query
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching { Tmdb.search(query) }.getOrNull().orEmpty()
            }
            if (binding.tmdbSearchInput.text.toString().trim() != query) return@launch
            if (results.isEmpty()) {
                closeSuggestions()
                return@launch
            }
            suggestionsAdapter.submit(results, query)
            binding.tmdbSearchSuggestionsPanel.isVisible = true
        }
    }

    private fun closeSuggestions() {
        lastSuggestionQuery = null
        if (::binding.isInitialized) binding.tmdbSearchSuggestionsPanel.isVisible = false
    }

    private fun history(): List<String> = PrefManager.getVal(PrefName.TmdbSearchHistory)

    private fun addHistory(query: String) {
        val current = history()
        val updated = (listOf(query) + current.filterNot { it.equals(query, true) }).take(10)
        PrefManager.setVal(PrefName.TmdbSearchHistory, updated)
    }

    private fun selectedPluginApis(): List<Pair<com.lagradost.cloudstream3.MainAPI, String>> {
        val ids = selectedSourceIds.filter { it != SOURCE_TMDB }
        if (ids.isEmpty()) return emptyList()
        val installed = CsRepos.installed(this)
        return ids.mapNotNull { id ->
            val source = installed.firstOrNull { it.id == id } ?: return@mapNotNull null
            val api = with(Dispatchers.IO) {
                CsRuntime.apisFor(this@TmdbSearchActivity, source).firstOrNull()
            } ?: return@mapNotNull null
            api to source.name
        }
    }

    private fun selectedStremioAddons(): List<StremioAddon> {
        val ids = selectedSourceIds.filter { it.startsWith(StremioAddonManager.SOURCE_PREFIX) }
        if (ids.isEmpty()) return emptyList()
        return searchableStremioAddons().filter { StremioAddonManager.sourceId(it) in ids }
    }

    private fun searchableStremioAddons(): List<StremioAddon> =
        runCatching { StremioAddonManager.installed() }.getOrDefault(emptyList())
            .filter { addon -> StremioAddonClient.searchCatalogs(addon).isNotEmpty() }

    private suspend fun searchStremioAddon(addon: StremioAddon, query: String): List<TmdbMedia> =
        withContext(Dispatchers.IO) {
            val cards = runCatching { StremioAddonClient.searchAddon(addon, query) }
                .getOrElse { e ->
                    android.util.Log.e("TmdbSearch", "${addon.manifest.name} search failed: ${e.message}", e)
                    emptyList()
                }
            cards.limitedAsyncMap(4) { card -> mapStremioCard(card) }.filterNotNull()
        }

    private suspend fun mapStremioCard(card: ani.sanin.settings.StremioCatalogCard): TmdbMedia? {
        val hint = if (card.type.equals("series", true) || card.type.equals("tv", true)) "tv" else "movie"
        if (card.id.startsWith("tt")) {
            runCatching { Tmdb.findByImdb(card.id, hint) }.getOrNull()?.let { return it }
        }
        val title = card.name.takeIf { it.isNotBlank() } ?: return null
        val year = card.releaseInfo?.take(4)?.toIntOrNull()
        val results = runCatching { Tmdb.search(title) }.getOrDefault(emptyList())
        return results.firstOrNull { it.type == hint && (year == null || it.year == year.toString()) }
            ?: results.firstOrNull { it.type == hint }
            ?: results.firstOrNull()
    }

    private fun showHistory() {
        val items = history()
        binding.tmdbSearchHistoryLabel.isVisible = items.isNotEmpty()
        binding.tmdbSearchHistory.isVisible = items.isNotEmpty()
        binding.tmdbSearchGrid.isVisible = items.isEmpty()
        binding.tmdbSearchEmpty.isVisible = false
        historyAdapter.submit(items)
        binding.tmdbSearchHistory.layoutManager = LinearLayoutManager(this)
        binding.tmdbSearchHistory.adapter = historyAdapter
    }

    private fun setupGrid() {
        val cols = TmdbCards.gridSpan(resources.displayMetrics.widthPixels / resources.displayMetrics.density)
        val layoutManager = GridLayoutManager(this, cols)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position in fullSpanPositions) layoutManager.spanCount else 1
            }
        }
        binding.tmdbSearchGrid.layoutManager = layoutManager
        TmdbCards.applyGridSpan(binding.tmdbSearchGrid)
    }

    private fun showSingle(adapter: RecyclerView.Adapter<out RecyclerView.ViewHolder>) {
        fullSpanPositions.clear()
        binding.tmdbSearchGrid.adapter = adapter
        setupGrid()
    }

    private suspend fun searchPlugin(
        api: com.lagradost.cloudstream3.MainAPI,
        name: String,
        query: String
    ): List<com.lagradost.cloudstream3.SearchResponse> = withContext(Dispatchers.IO) {
        runCatching {
            android.util.Log.i("TmdbSearch", "Searching '$query' on $name (${api.javaClass.simpleName})")
            val response = api.search(query, 1)
            android.util.Log.i("TmdbSearch", "$name returned ${response?.items?.size ?: 0} items")
            response?.items
        }.getOrElse { e ->
            android.util.Log.e("TmdbSearch", "$name search failed: ${e.message}", e)
            null
        }.orEmpty()
    }

    private fun runSearch(query: String) {
        closeSuggestions()
        addHistory(query)
        lastQuery = query
        activeFilter = null
        updateFilterChips()
        binding.tmdbSearchHistoryLabel.isVisible = false
        binding.tmdbSearchHistory.isVisible = false
        binding.tmdbSearchGrid.isVisible = true
        binding.tmdbSearchResultLayout.isVisible = true
        binding.tmdbSearchProgress.isVisible = true
        binding.tmdbSearchEmpty.isVisible = false
        lifecycleScope.launch {
            val selectedPlugins = selectedPluginApis()
            val selectedStremio = selectedStremioAddons()
            val includeTmdb = SOURCE_TMDB in selectedSourceIds
            val sourceCount = selectedPlugins.size + selectedStremio.size + if (includeTmdb) 1 else 0

            if (sourceCount <= 1) {
                val results = when {
                    selectedPlugins.isNotEmpty() -> {
                        val (api, name) = selectedPlugins.first()
                        searchPlugin(api, name, query)
                    }
                    selectedStremio.isNotEmpty() -> searchStremioAddon(selectedStremio.first(), query)
                    else -> Tmdb.search(query)
                }
                binding.tmdbSearchProgress.isVisible = false
                if (selectedPlugins.isNotEmpty()) {
                    showSingle(pluginAdapter)
                    pluginAdapter.submit(results as List<com.lagradost.cloudstream3.SearchResponse>)
                } else {
                    showSingle(adapter)
                    adapter.submit(results as List<TmdbMedia>)
                }
                binding.tmdbSearchEmpty.isVisible = results.isEmpty()
                if (results.isEmpty()) snackString("No results for '$query'")
                return@launch
            }

            val sections = ArrayList<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
            fullSpanPositions.clear()
            var position = 0
            if (includeTmdb) {
                val results = Tmdb.search(query)
                if (results.isNotEmpty()) {
                    sections.add(SearchSectionHeaderAdapter("TMDB", results.size))
                    fullSpanPositions.add(position)
                    position++
                    val groupAdapter = TmdbSearchGridAdapter { media -> openDetails(media) }
                    groupAdapter.submit(results)
                    sections.add(groupAdapter)
                    position += results.size
                }
            }
            for ((api, name) in selectedPlugins) {
                val results = searchPlugin(api, name, query)
                if (results.isEmpty()) continue
                sections.add(SearchSectionHeaderAdapter(name, results.size))
                fullSpanPositions.add(position)
                position++
                val groupAdapter = PluginSearchAdapter { item -> openPluginDetails(item) }
                groupAdapter.submit(results)
                sections.add(groupAdapter)
                position += results.size
            }
            for (addon in selectedStremio) {
                val results = searchStremioAddon(addon, query)
                if (results.isEmpty()) continue
                sections.add(SearchSectionHeaderAdapter(addon.manifest.name, results.size))
                fullSpanPositions.add(position)
                position++
                val groupAdapter = TmdbSearchGridAdapter { media -> openDetails(media) }
                groupAdapter.submit(results)
                sections.add(groupAdapter)
                position += results.size
            }
            binding.tmdbSearchProgress.isVisible = false
            if (sections.isEmpty()) {
                showSingle(adapter)
                adapter.submit(emptyList())
                binding.tmdbSearchEmpty.isVisible = true
                snackString("No results for '$query'")
            } else {
                binding.tmdbSearchGrid.adapter = ConcatAdapter(*sections.toTypedArray())
                setupGrid()
                binding.tmdbSearchEmpty.isVisible = false
            }
        }
    }

    private fun runFiltered(filter: TmdbFilterResult) {
        if (SOURCE_TMDB !in selectedSourceIds) {
            snackString("Select TMDB to use filters")
            return
        }
        activeFilter = filter
        lastQuery = null
        updateFilterChips()
        closeSuggestions()
        binding.tmdbSearchHistory.isVisible = false
        binding.tmdbSearchGrid.isVisible = true
        binding.tmdbSearchProgress.isVisible = true
        binding.tmdbSearchEmpty.isVisible = false
        lifecycleScope.launch {
            val extras = buildList {
                filter.status?.let { add("with_status" to it.toString()) }
                filter.tvType?.let { add("with_type" to it.toString()) }
                filter.releaseType?.let { add("with_release_type" to it.toString()) }
                filter.country?.let { add("with_origin_country" to it) }
                filter.language?.let { add("with_original_language" to it) }
                filter.minRating?.let { add("vote_average.gte" to it.toString()) }
                if (filter.includeAdult) add("include_adult" to "true")
            }
            val results = Tmdb.discover(
                mediaType = filter.mediaType,
                genres = filter.genres.joinToString(",").ifBlank { null },
                keywords = filter.keywords.joinToString(",").ifBlank { null },
                year = filter.year,
                sort = filter.sort,
                extra = extras
            )
            binding.tmdbSearchProgress.isVisible = false
            showSingle(adapter)
            adapter.submit(results)
            binding.tmdbSearchEmpty.isVisible = results.isEmpty()
            if (results.isEmpty()) snackString("No results with this filter")
        }
    }

    private fun updateFilterChips() {
        val chips = ArrayList<SearchFilterChipsAdapter.ChipSpec>()
        val installed = runCatching { CsRepos.installed(this) }.getOrNull().orEmpty()
            .associateBy { it.id }
        selectedSourceIds.forEach { id ->
            val label = when {
                id == SOURCE_TMDB -> "TMDB"
                id.startsWith(StremioAddonManager.SOURCE_PREFIX) ->
                    StremioAddonManager.addonForSourceId(id)?.manifest?.name ?: "Stremio addon"
                else -> installed[id]?.name ?: id
            }
            chips.add(SearchFilterChipsAdapter.ChipSpec("src:$id", label))
        }
        activeFilter?.let { filter ->
            filter.sort?.let {
                chips.add(SearchFilterChipsAdapter.ChipSpec("sort", sortChipLabel(it)))
            }
            filter.year?.let {
                chips.add(SearchFilterChipsAdapter.ChipSpec("year", it.toString()))
            }
            filter.country?.let {
                chips.add(SearchFilterChipsAdapter.ChipSpec("country", it))
            }
            filter.language?.let {
                chips.add(SearchFilterChipsAdapter.ChipSpec("language", it))
            }
            filter.minRating?.let {
                chips.add(SearchFilterChipsAdapter.ChipSpec("rating", "$it+"))
            }
            if (filter.includeAdult) {
                chips.add(SearchFilterChipsAdapter.ChipSpec("adult", "Adult"))
            }
            val format = if (filter.mediaType == "tv") filter.tvType else filter.releaseType
            if (format != null) {
                val options =
                    if (filter.mediaType == "tv") TMDB_TV_TYPE_OPTIONS else TMDB_MOVIE_RELEASE_OPTIONS
                options.firstOrNull { it.value == format }?.let {
                    chips.add(SearchFilterChipsAdapter.ChipSpec("format", it.label))
                }
            }
            if (filter.mediaType == "tv") {
                filter.status?.let { status ->
                    TMDB_TV_STATUS_OPTIONS.firstOrNull { it.value == status }?.let {
                        chips.add(SearchFilterChipsAdapter.ChipSpec("status", it.label))
                    }
                }
            }
            filter.genreNames.forEach {
                chips.add(SearchFilterChipsAdapter.ChipSpec("genre:$it", it))
            }
            filter.keywordNames.forEach {
                chips.add(SearchFilterChipsAdapter.ChipSpec("keyword:$it", it))
            }
        }
        filterChipsAdapter.submit(chips)
        binding.tmdbSearchFilterChips.isVisible = chips.isNotEmpty()
    }

    private fun sortChipLabel(sort: String): String = when (sort) {
        "popularity.desc" -> "Popular"
        "first_air_date.desc", "primary_release_date.desc" -> "Recent"
        "vote_average.desc" -> "Rating"
        "name.asc", "original_title.asc" -> "A-Z"
        else -> "Sort"
    }

    private fun onFilterChipClicked(chip: SearchFilterChipsAdapter.ChipSpec) {
        val key = chip.key
        if (key.startsWith("src:")) {
            val id = key.removePrefix("src:")
            selectedSourceIds.remove(id)
            if (selectedSourceIds.isEmpty()) selectedSourceIds.add(SOURCE_TMDB)
            updateFilterChips()
            val query = lastQuery
            if (query != null) runSearch(query) else activeFilter?.let { runFiltered(it) }
            return
        }
        val filter = activeFilter ?: return
        val updated = when {
            key == "sort" -> filter.copy(sort = null)
            key == "year" -> filter.copy(year = null)
            key == "country" -> filter.copy(country = null)
            key == "language" -> filter.copy(language = null)
            key == "rating" -> filter.copy(minRating = null)
            key == "adult" -> filter.copy(includeAdult = false)
            key == "format" -> filter.copy(tvType = null, releaseType = null)
            key == "status" -> filter.copy(status = null)
            key.startsWith("genre:") -> {
                val index = filter.genreNames.indexOf(key.removePrefix("genre:"))
                if (index < 0) filter else filter.copy(
                    genres = filter.genres.filterIndexed { i, _ -> i != index },
                    genreNames = filter.genreNames.filterIndexed { i, _ -> i != index }
                )
            }
            key.startsWith("keyword:") -> {
                val index = filter.keywordNames.indexOf(key.removePrefix("keyword:"))
                if (index < 0) filter else filter.copy(
                    keywords = filter.keywords.filterIndexed { i, _ -> i != index },
                    keywordNames = filter.keywordNames.filterIndexed { i, _ -> i != index }
                )
            }
            else -> filter
        }
        activeFilter = updated
        updateFilterChips()
        runFiltered(updated)
    }

    private fun openDetails(media: TmdbMedia) {
        startActivity(
            Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, media.type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun openPluginDetails(item: com.lagradost.cloudstream3.SearchResponse) {

        val apiName = item.apiName
        val installed = CsRepos.installed(this)
        var matchedSourceId: String? = null
        for (source in installed) {
            val apis = with(Dispatchers.IO) { CsRuntime.apisFor(this@TmdbSearchActivity, source) }
            if (apis.any { it.name == apiName }) {
                matchedSourceId = source.id
                break
            }
        }
        val sourceId = matchedSourceId ?: return
        startActivity(
            Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, sourceId)
                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.url)
        )
    }

    class PluginSearchAdapter(
        private val onClick: (com.lagradost.cloudstream3.SearchResponse) -> Unit
    ) : RecyclerView.Adapter<PluginSearchAdapter.VH>() {
        private var items: List<com.lagradost.cloudstream3.SearchResponse> = emptyList()
        private val cardScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        fun submit(list: List<com.lagradost.cloudstream3.SearchResponse>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false); return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding; val landscape = TmdbCards.isLandscapeOrientation(); val size = TmdbCards.cardSize()
            val (w, h) = if (landscape) (260f*size).toInt() to (148f*size).toInt() else (102f*size).toInt() to (154f*size).toInt()
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> { width = w; height = h }
            b.tmdbCard.radius = TmdbCards.roundness()

            b.tmdbCardLogo.isVisible = false
            b.tmdbCardOverlayTitle.isVisible = false

            val titlePos = PrefManager.getVal<Int>(PrefName.CardTitlePosition)
            if (landscape) {
                when (titlePos) {
                    0 -> {
                        b.tmdbCardGradient.isVisible = true
                        b.tmdbCardGradient.updateLayoutParams<ViewGroup.LayoutParams> { width = w; height = h }
                        TmdbCards.setCardGradient(b.tmdbCardGradient)
                        b.tmdbCardOverlayTitle.isVisible = true
                        b.tmdbCardOverlayTitle.text = item.name
                        b.tmdbCardOverlayTitle.updateLayoutParams<ViewGroup.LayoutParams> { width = w }
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    2 -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    else -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardTitle.isVisible = true
                        b.tmdbCardTitle.text = item.name
                        b.tmdbCardYear.isVisible = false
                    }
                }
            } else {

                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                if (titlePos == 2) {
                    b.tmdbCardTitle.isVisible = false
                } else {
                    b.tmdbCardTitle.isVisible = true
                    b.tmdbCardTitle.text = item.name
                }
                b.tmdbCardYear.isVisible = false
            }

            if (landscape) {
                b.tmdbCardPoster.loadImage(item.posterUrl, 780)
                b.tmdbCardPoster.tag = item.name
                cardScope.launch {
                    val name = item.name ?: return@launch
                    val results = runCatching { ani.sanin.connections.tmdb.Tmdb.search(name) }.getOrNull()
                    val match = results?.firstOrNull() ?: return@launch
                    val backdrop = match.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
                    val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) runCatching { ani.sanin.connections.tmdb.Tmdb.logoUrl(match.type, match.id) }.getOrNull() else null
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (holder.binding.tmdbCardPoster.tag != item.name) return@withContext
                        if (!backdrop.isNullOrBlank()) b.tmdbCardPoster.loadImage(backdrop)
                        if (!logoUrl.isNullOrBlank() && titlePos == 0) {
                            b.tmdbCardLogo.isVisible = true
                            val logoW = (w * 0.7f).toInt().coerceIn(60, 180)
                            com.bumptech.glide.Glide.with(b.tmdbCardLogo.context)
                                .load(logoUrl)
                                .override(logoW, (logoW * 0.4f).toInt())
                                .into(b.tmdbCardLogo)
                            b.tmdbCardOverlayTitle.isVisible = false
                            b.tmdbCardTitle.isVisible = false
                        }
                    }
                }
            } else {
                b.tmdbCardPoster.loadImage(item.posterUrl, 300)
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }
        override fun getItemCount() = items.size
        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class TmdbSearchGridAdapter(
        private val onOpen: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbSearchGridAdapter.VH>() {

        private var items: List<TmdbMedia> = emptyList()

        fun submit(list: List<TmdbMedia>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            val rv = holder.itemView.parent as? RecyclerView
            val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
            TmdbCards.applyCardStyle(holder.binding, item, cell)
            if (rv != null) {
                holder.binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = TmdbCards.gridRowGapPx(rv)
                }
            }
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroy() {
        suggestionHandler.removeCallbacksAndMessages(null)
        suggestionJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SOURCE_TMDB = "tmdb"
        private const val SUGGESTION_DEBOUNCE_MS = 250L
    }

    class HistoryAdapter(
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private var items: List<String> = emptyList()

        fun submit(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbHistoryText.text = item
            holder.binding.tmdbHistoryText.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbHistoryText)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
