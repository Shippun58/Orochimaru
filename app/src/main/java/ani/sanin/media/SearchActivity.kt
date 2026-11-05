package ani.sanin.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.connections.anilist.AniMangaSearchResults
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.AnilistSearch
import ani.sanin.connections.anilist.AnilistSearch.SearchType
import ani.sanin.connections.anilist.CharacterSearchResults
import ani.sanin.connections.anilist.StaffSearchResults
import ani.sanin.connections.anilist.StudioSearchResults
import ani.sanin.connections.anilist.UserSearchResults
import ani.sanin.connections.kitsu.Kitsu
import ani.sanin.connections.mal.MAL
import ani.sanin.databinding.ActivitySearchBinding
import ani.sanin.home.AnimeCatalogueHome
import ani.sanin.home.CatalogueItem
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.ShowResponse
import ani.sanin.profile.UsersAdapter
import ani.sanin.profile.User
import ani.sanin.px
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.Logger
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.IdentityHashMap
import android.os.Handler
import android.os.Looper
import android.content.Intent
import java.util.LinkedHashMap
import java.util.Timer
import java.util.TimerTask

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val scope = lifecycleScope
    val model: AnilistSearch by viewModels()

    var style: Int = 0
    lateinit var searchType: SearchType
    private var screenWidth: Float = 0f

    private lateinit var mediaAdaptor: MediaAdaptor
    private lateinit var characterAdaptor: CharacterAdapter
    private lateinit var studioAdaptor: StudioAdapter
    private lateinit var staffAdaptor: AuthorAdapter
    private lateinit var usersAdapter: UsersAdapter

    private lateinit var progressAdapter: ProgressAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var headerAdaptor: HeaderInterface

    lateinit var aniMangaResult: AniMangaSearchResults
    lateinit var characterResult: CharacterSearchResults
    lateinit var studioResult: StudioSearchResults
    lateinit var staffResult: StaffSearchResults
    lateinit var userResult: UserSearchResults

    lateinit var updateChips: (() -> Unit)

    
    var selectedSources: MutableSet<String> = mutableSetOf(SearchSource.ANILIST)
    private var multiSourceJob: Job? = null
    private val sourceResultMap = mutableMapOf<Int, Pair<String, ShowResponse>>()
    private val malResultIds = mutableSetOf<Int>()
    private var groupedAdapters: List<RecyclerView.Adapter<*>> = emptyList()
    private val fullSpanPositions = mutableSetOf<Int>()

    private val sourceCatalog: List<SearchSource> by lazy { buildSourceCatalog() }

    private fun buildSourceCatalog(): List<SearchSource> {
        val apis = mutableListOf(
            SearchSource(SearchSource.ANILIST, "AniList", SearchSourceCategory.API),
            SearchSource(SearchSource.MAL, "MAL", SearchSourceCategory.API),
        )
        if (searchType == SearchType.MANGA) return apis
        apis.add(SearchSource(SearchSource.KITSU, "Kitsu", SearchSourceCategory.API))
        AnimeSources.orderedNativeParsers.forEach { parser ->
            apis.add(
                SearchSource(
                    "native:${parser.saveName}", parser.name,
                    SearchSourceCategory.BUILT_IN, parser
                )
            )
        }
        val nativeNames = AnimeSources.allNativeParsers.map { it.name }.toSet()
        AnimeSources.list.forEach { lazier ->
            if (lazier.name in nativeNames || lazier.name == "Local") return@forEach
            val parser = runCatching { lazier.get.value }.getOrNull() ?: return@forEach
            apis.add(
                SearchSource(
                    "ext:${lazier.name}", lazier.name,
                    SearchSourceCategory.EXTENSION, parser
                )
            )
        }
        return apis
    }

    fun searchSourceCatalog(): List<SearchSource> = sourceCatalog

    fun isAniListOnly(): Boolean =
        selectedSources.size == 1 && selectedSources.contains(SearchSource.ANILIST)

    private fun selectedSourceObjects(): List<SearchSource> =
        sourceCatalog.filter { it.key in selectedSources }

    fun selectedSourceKeys(): Set<String> = selectedSources.toSet()

    private fun applySelectedSources(keys: Set<String>) {
        val valid = sourceCatalog.map { it.key }.toSet()
        val cleaned = keys.filter { it in valid }.toMutableSet()
        if (cleaned.isEmpty()) cleaned.add(SearchSource.ANILIST)
        selectedSources = cleaned
        invalidateSuggestionPool()
        if (isAniListOnly() && this::binding.isInitialized &&
            this::mediaAdaptor.isInitialized && this::progressAdapter.isInitialized
        ) {
            malResultIds.clear()
            sourceResultMap.clear()
            restoreSingleAdapter()
        }
        if (::updateChips.isInitialized) updateChips.invoke()
        search()
    }

    fun selectAllSources() = applySelectedSources(sourceCatalog.map { it.key }.toSet())

    fun resetSources() = applySelectedSources(setOf(SearchSource.ANILIST))

    fun toggleSource(key: String) {
        if (key == SearchSource.ALL) {
            selectAllSources()
            return
        }
        val next = selectedSources.toMutableSet()
        if (!next.remove(key)) next.add(key)
        if (next.isEmpty()) next.add(SearchSource.ANILIST)
        applySelectedSources(next)
    }

    fun sourceChipsForDisplay(): List<SearchFilterChipsAdapter.ChipSpec> {
        val selected = selectedSourceObjects()
        if (selected.isEmpty()) return emptyList()
        if (selected.size == sourceCatalog.size) {
            return listOf(
                SearchFilterChipsAdapter.ChipSpec(
                    "src:${SearchSource.ALL}", getString(R.string.all)
                )
            )
        }
        if (isAniListOnly()) return emptyList()
        return selected.map {
            SearchFilterChipsAdapter.ChipSpec("src:${it.key}", it.label)
        }
    }

    private fun runMultiSourceSearch() {
        multiSourceJob?.cancel()
        val query = aniMangaResult.search?.trim().orEmpty()
        val sources = selectedSourceObjects()
        if (sources.isEmpty()) return
        val isAnime = aniMangaResult.type == "ANIME"
        multiSourceJob = scope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val groups = ArrayList<Pair<SearchSource, List<Media>>>()
            val malIds = mutableSetOf<Int>()
            val providerMap = mutableMapOf<Int, Pair<String, ShowResponse>>()
            for (src in sources) {
                if (!isActive) return@launch
                val items: List<Media> = when (src.key) {
                    SearchSource.ANILIST -> runCatching {
                        Anilist.query.searchAniManga(
                            type = aniMangaResult.type,
                            perPage = 20,
                            search = query.ifBlank { null },
                            sort = aniMangaResult.sort,
                            genres = aniMangaResult.genres,
                            tags = aniMangaResult.tags,
                            status = aniMangaResult.status,
                            format = aniMangaResult.format,
                            countryOfOrigin = aniMangaResult.countryOfOrigin,
                            isAdult = aniMangaResult.isAdult,
                            onList = aniMangaResult.onList,
                            excludedGenres = aniMangaResult.excludedGenres,
                            excludedTags = aniMangaResult.excludedTags,
                            season = aniMangaResult.season,
                            seasonYear = if (isAnime) aniMangaResult.seasonYear else null,
                            startYear = if (isAnime) null else aniMangaResult.startYear,
                            adultOnly = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
                        )?.results.orEmpty()
                    }.getOrElse { emptyList() }

                    SearchSource.MAL -> runCatching {
                        MAL.jikan.search(
                            query = query,
                            endpoint = if (isAnime) "anime" else "manga",
                            limit = 20,
                            sfw = !aniMangaResult.isAdult
                        )?.data?.map { Media(it, isAnime) }.orEmpty()
                    }.getOrElse { emptyList() }

                    SearchSource.KITSU -> runCatching { Kitsu.search(query) }
                        .getOrElse { emptyList() }

                    else -> {
                        val parser = src.parser
                        if (query.isBlank() || parser == null ||
                            System.currentTimeMillis() - startedAt > 25_000
                        ) {
                            emptyList()
                        } else runCatching {
                            withTimeoutOrNull(10_000) { parser.search(query) }.orEmpty()
                        }.getOrElse { emptyList() }.mapNotNull { response ->
                            runCatching {
                                val media = AnimeCatalogueHome.displayMedia(
                                    src.key, CatalogueItem(response)
                                )
                                providerMap[media.id] = src.key to response
                                media
                            }.getOrNull()
                        }
                    }
                }
                if (src.key == SearchSource.MAL) items.forEach { malIds.add(it.id) }
                val unique = items.distinctBy { it.id }.take(40)
                if (unique.isNotEmpty()) groups.add(src to unique)
            }
            val flattened = groups.flatMap { it.second }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                malResultIds.clear()
                malResultIds.addAll(malIds)
                sourceResultMap.clear()
                sourceResultMap.putAll(providerMap)
                model.aniMangaSearchResults.results.clear()
                model.aniMangaSearchResults.results.addAll(flattened)
                model.aniMangaSearchResults.hasNextPage = false
                if (sources.size <= 1) {
                    restoreSingleAdapter()
                    mediaAdaptor.notifyDataSetChanged()
                } else {
                    showGroupedResults(groups)
                }
                progressAdapter.bar?.isVisible = false
                updateResultCount()
            }
        }
    }

    private fun showGroupedResults(groups: List<Pair<SearchSource, List<Media>>>) {
        val adapters = ArrayList<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
        fullSpanPositions.clear()
        var position = 0
        for ((source, items) in groups) {
            adapters.add(SearchSectionHeaderAdapter(source.label, items.size))
            fullSpanPositions.add(position)
            position++
            val sourceKey = source.key
            adapters.add(
                MediaAdaptor(
                    style,
                    items.toMutableList(),
                    this,
                    matchParent = true,
                    onItemClickOverride = { media -> handleGroupedResultClick(sourceKey, media) }
                )
            )
            position += items.size
        }
        adapters.add(progressAdapter)
        groupedAdapters = adapters
        concatAdapter = ConcatAdapter(*adapters.toTypedArray())
        binding.searchRecyclerView.adapter = concatAdapter
        binding.searchRecyclerView.scrollToPosition(0)
    }

    private fun restoreSingleAdapter() {
        groupedAdapters = emptyList()
        fullSpanPositions.clear()
        concatAdapter = ConcatAdapter(mediaAdaptor, progressAdapter)
        binding.searchRecyclerView.adapter = concatAdapter
    }

    private fun handleGroupedResultClick(sourceKey: String, media: Media): Boolean {
        return when (sourceKey) {
            SearchSource.ANILIST, SearchSource.KITSU -> false
            SearchSource.MAL -> {
                resolveMalAndOpen(media)
                true
            }
            else -> {
                val entry = sourceResultMap[media.id] ?: return false
                openSourceResult(entry.first, entry.second)
                true
            }
        }
    }

    private fun handleSearchResultClick(media: Media): Boolean {
        if (media.id < 0) {
            val entry = sourceResultMap[media.id] ?: return false
            openSourceResult(entry.first, entry.second)
            return true
        }
        if (malResultIds.contains(media.id)) {
            resolveMalAndOpen(media)
            return true
        }
        return false
    }

    private fun openSourceResult(sourceId: String, response: ShowResponse) {
        AnimeCatalogueHome.openItem(
            this,
            sourceId,
            CatalogueItem(response),
            onFailed = {
                Toast.makeText(this, getString(R.string.error), Toast.LENGTH_SHORT).show()
            }
        ) { resolved ->
            startActivity(
                Intent(this, MediaDetailsActivity::class.java)
                    .putExtra("media", resolved as java.io.Serializable)
            )
        }
    }

    private fun resolveMalAndOpen(media: Media) {
        scope.launch(Dispatchers.IO) {
            val type = if (media.anime != null) "ANIME" else "MANGA"
            val resolved = runCatching {
                Anilist.query.getMedia(media.id, mal = true, type = type)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (resolved != null) {
                    startActivity(
                        Intent(this@SearchActivity, MediaDetailsActivity::class.java)
                            .putExtra("media", resolved as java.io.Serializable)
                    )
                } else {
                    Toast.makeText(
                        this@SearchActivity, getString(R.string.error), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    
    val suggestionHandler = Handler(Looper.getMainLooper())
    private var suggestionJob: Job? = null
    private var lastSuggestionQuery: String = ""
    private val suggestionCache = object : LinkedHashMap<String, List<Media>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Media>>): Boolean {
            return size > 30
        }
    }
    private var suggestionsAdapter: SearchSuggestionsAdapter? = null
    private var suppressNextSuggestions = false
    private val SUGGESTION_DEBOUNCE_MS = 300L
    private var suggestionPool: List<Media> = emptyList()
    private var poolJob: Job? = null
    private var pendingPoolQuery: String? = null
    private var poolLoadAttempted = false
    private val malSuggestionObjects: MutableSet<Media> =
        Collections.newSetFromMap(IdentityHashMap<Media, Boolean>())

    private fun suggestionsEnabled(): Boolean =
        selectedSources.isNotEmpty() &&
            selectedSources.all { it == SearchSource.ANILIST || it == SearchSource.MAL }

    private fun normalizeTitle(media: Media): String =
        media.userPreferredName.trim().lowercase()

    private fun ensureSuggestionPool() {
        if (suggestionPool.isNotEmpty() || poolJob?.isActive == true || poolLoadAttempted) return
        poolLoadAttempted = true
        poolJob = scope.launch(Dispatchers.IO) {
            val type = model.aniMangaSearchResults.type
            val isAdult = model.aniMangaSearchResults.isAdult
            val merged = LinkedHashMap<String, Media>()
            if (selectedSources.contains(SearchSource.ANILIST)) {
                for (sort in listOf("POPULARITY_DESC", "TRENDING_DESC", "SCORE_DESC")) {
                    val res = try {
                        Anilist.query.searchAniManga(
                            type = type,
                            perPage = 50,
                            sort = sort,
                            isAdult = isAdult,
                            onList = model.aniMangaSearchResults.onList,
                            adultOnly = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
                        )
                    } catch (e: Exception) {
                        Logger.log("Suggest: pool '$sort' failed: ${e.message}")
                        null
                    }
                    res?.results?.forEach { merged.putIfAbsent(normalizeTitle(it), it) }
                }
            }
            val malPool = ArrayList<Media>()
            if (selectedSources.contains(SearchSource.MAL)) {
                try {
                    MAL.jikan.search(
                        query = "",
                        endpoint = if (type == "ANIME") "anime" else "manga",
                        limit = 25,
                        sfw = !isAdult
                    )?.data?.map { Media(it, type == "ANIME") }?.let { malPool.addAll(it) }
                } catch (e: Exception) {
                    Logger.log("Suggest: MAL pool failed: ${e.message}")
                }
                malPool.forEach { merged.putIfAbsent(normalizeTitle(it), it) }
            }
            val pool = merged.values.toList()
            Logger.log("Suggest: pool loaded ${pool.size} titles")
            scope.launch(Dispatchers.Main) {
                malSuggestionObjects.addAll(malPool)
                suggestionPool = pool
                val pending = pendingPoolQuery
                pendingPoolQuery = null
                if (pending != null && !isFinishing && !isDestroyed) fetchSuggestions(pending)
            }
        }
    }

    private fun matchesQuery(media: Media, q: String): Boolean {
        val needle = q.lowercase()
        return media.userPreferredName.lowercase().contains(needle) ||
            media.nameRomaji.lowercase().contains(needle) ||
            media.name?.lowercase()?.contains(needle) == true
    }

    private fun localMatches(q: String): List<Media> =
        if (suggestionPool.isEmpty()) emptyList()
        else suggestionPool.filter { matchesQuery(it, q) }.take(5)

    fun suppressSuggestionsForNextQuery() {
        suppressNextSuggestions = true
    }

    fun invalidateSuggestionPool() {
        poolJob?.cancel()
        poolJob = null
        pendingPoolQuery = null
        suggestionPool = emptyList()
        suggestionCache.clear()
        malSuggestionObjects.clear()
        poolLoadAttempted = false
    }

    fun onSearchQueryChanged(query: String?) {
        if (!suggestionsEnabled()) {
            closeSuggestions()
            return
        }
        if (query.isNullOrBlank()) {
            closeSuggestions()
            return
        }
        if (suppressNextSuggestions) {
            suppressNextSuggestions = false
            closeSuggestions()
            return
        }
        val q = query.trim()
        suggestionHandler.removeCallbacksAndMessages(null)
        if (suggestionPool.isNotEmpty() && localMatches(q).isNotEmpty()) {
            fetchSuggestions(q)
        } else {
            suggestionHandler.postDelayed({ fetchSuggestions(q) }, SUGGESTION_DEBOUNCE_MS)
        }
    }

    private fun fetchSuggestions(q: String) {
        if (isFinishing || isDestroyed) return
        if (!suggestionsEnabled()) return
        if (searchType != SearchType.ANIME && searchType != SearchType.MANGA) return
        if (lastSuggestionQuery == q && binding.searchSuggestionsPanel.visibility == View.VISIBLE) {
            return
        }
        suggestionJob?.cancel()

        ensureSuggestionPool()
        if (suggestionPool.isEmpty()) pendingPoolQuery = q
        val local = localMatches(q)
        val cached = suggestionCache[q]
        if (cached != null) {
            showSuggestions(mergeSuggestions(local, cached), q)
            lastSuggestionQuery = q
            return
        }
        if (local.size >= 5 && !selectedSources.contains(SearchSource.MAL)) {
            showSuggestions(local, q)
            lastSuggestionQuery = q
            return
        }
        lastSuggestionQuery = q
        suggestionJob = scope.launch(Dispatchers.IO) {
            Logger.log("Suggest: query='$q'")
            val isAnime = model.aniMangaSearchResults.type == "ANIME"
            val isAdult = model.aniMangaSearchResults.isAdult
            val combined = ArrayList<Media>()
            val malResults = ArrayList<Media>()
            if (selectedSources.contains(SearchSource.ANILIST)) {
                val res = try {
                    Anilist.query.searchAniManga(
                        type = model.aniMangaSearchResults.type,
                        perPage = 10,
                        search = q,
                        isAdult = isAdult,
                        onList = model.aniMangaSearchResults.onList,
                        adultOnly = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
                    )
                } catch (e: Exception) {
                    Logger.log("Suggest: request failed for '$q': ${e.message}")
                    null
                }
                res?.results?.let { combined.addAll(it) }
            }
            if (selectedSources.contains(SearchSource.MAL) && q.length >= 3) {
                val mal = try {
                    MAL.jikan.search(
                        query = q,
                        endpoint = if (isAnime) "anime" else "manga",
                        limit = 10,
                        sfw = !isAdult
                    )?.data?.map { Media(it, isAnime) }.orEmpty()
                } catch (e: Exception) {
                    Logger.log("Suggest: MAL request failed for '$q': ${e.message}")
                    emptyList()
                }
                malResults.addAll(mal)
                combined.addAll(mal)
            }
            if (combined.isNotEmpty()) suggestionCache[q] = combined
            Logger.log("Suggest: ${combined.size} api results for '$q'")
            scope.launch(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@launch
                malSuggestionObjects.addAll(malResults)
                showSuggestions(mergeSuggestions(localMatches(q), combined), q)
            }
        }
    }

    private fun mergeSuggestions(local: List<Media>, api: List<Media>): List<Media> {
        val seen = HashSet<String>()
        val out = ArrayList<Media>(local.size + api.size)
        for (m in local + api) {
            if (seen.add(normalizeTitle(m))) out.add(m)
            if (out.size >= 5) break
        }
        return out
    }

    private fun openSuggestion(media: Media) {
        if (media in malSuggestionObjects) {
            resolveMalAndOpen(media)
            return
        }
        try {
            startActivity(
                Intent(this, MediaDetailsActivity::class.java)
                    .putExtra("media", media as java.io.Serializable)
            )
        } catch (e: Exception) {
            Logger.log("Suggest: open failed: ${e.message}")
        }
    }

    fun showSuggestions(list: List<Media>, q: String) {
        val adapter = suggestionsAdapter ?: return
        if (list.isEmpty()) {
            binding.searchSuggestionsPanel.visibility = View.GONE
            binding.searchRecyclerView.visibility = View.VISIBLE
            return
        }
        adapter.submit(list, q)
        binding.searchEmptyState.visibility = View.GONE
        binding.searchRecyclerView.visibility = View.GONE
        binding.searchSuggestionsPanel.visibility = View.VISIBLE
    }

    fun closeSuggestions() {
        binding.searchSuggestionsPanel.visibility = View.GONE
        binding.searchRecyclerView.visibility = View.VISIBLE
        binding.searchEmptyState.visibility = View.GONE
        lastSuggestionQuery = ""
    }

    private fun currentSearchText(): String = when (searchType) {
        SearchType.ANIME, SearchType.MANGA ->
            if (this::aniMangaResult.isInitialized) aniMangaResult.search ?: "" else ""
        SearchType.CHARACTER ->
            if (this::characterResult.isInitialized) characterResult.search ?: "" else ""
        SearchType.STUDIO ->
            if (this::studioResult.isInitialized) studioResult.search ?: "" else ""
        SearchType.STAFF ->
            if (this::staffResult.isInitialized) staffResult.search ?: "" else ""
        SearchType.USER ->
            if (this::userResult.isInitialized) userResult.search ?: "" else ""
    }

    fun updateResultCount() {
        if (!this::headerAdaptor.isInitialized) return
        val count = when (searchType) {
            SearchType.ANIME, SearchType.MANGA ->
                if (this::mediaAdaptor.isInitialized) model.aniMangaSearchResults.results.size else 0
            SearchType.CHARACTER ->
                if (this::characterResult.isInitialized) characterResult.results.size else 0
            SearchType.STUDIO ->
                if (this::studioResult.isInitialized) studioResult.results.size else 0
            SearchType.STAFF ->
                if (this::staffResult.isInitialized) staffResult.results.size else 0
            SearchType.USER ->
                if (this::userResult.isInitialized) userResult.results.size else 0
        }
        binding.searchHeader.searchResultCount.text =
            getString(R.string.search_results) + " · " + count
        val emptyVisible = count == 0 && !model.hasNextPage(searchType) &&
            currentSearchText().isNotBlank() &&
            binding.searchSuggestionsPanel.visibility != View.VISIBLE
        binding.searchEmptyState.apply {
            if (emptyVisible) {
                text = getString(R.string.no_results_for, currentSearchText())
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val header = binding.searchHeader
                val searchBarText = header.searchBarText
                val keyboardVisible = TvKeyboardUtil.isCompactKeyboardVisible(searchBarText)
                Logger.log(Log.INFO, "SearchActivity BACK: keyboardVisible=$keyboardVisible focused=${currentFocus?.let { it.javaClass.simpleName + "#" + it.id }}", "TvKeyboard")
                if (keyboardVisible) {
                    searchBarText.clearFocus()
                    header.searchFilter.post { header.searchFilter.requestFocus() }
                    TvKeyboardUtil.hideCompactKeyboard(searchBarText)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
        initActivity(this)
        screenWidth = resources.displayMetrics.run { widthPixels / density }

        binding.root.updatePaddingRelative(top = statusBarHeight)
        binding.searchRecyclerView.updatePaddingRelative(
            bottom = navBarHeight + 80f.px
        )

        val notSet = model.notSet
        searchType = SearchType.fromString(intent.getStringExtra("type") ?: "ANIME")
        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                style = PrefManager.getVal(PrefName.SearchStyle)
                var listOnly: Boolean? = intent.getBooleanExtra("listOnly", false)
                if (!listOnly!!) listOnly = null

                if (model.notSet) {
                    model.notSet = false
                    model.aniMangaSearchResults = AniMangaSearchResults(
                        intent.getStringExtra("type") ?: "ANIME",
                        isAdult = if (Anilist.adult) intent.getBooleanExtra(
                            "hentai",
                            false
                        ) else false,
                        onList = listOnly,
                        search = intent.getStringExtra("query"),
                        genres = intent.getStringExtra("genre")?.let { mutableListOf(it) },
                        tags = intent.getStringExtra("tag")?.let { mutableListOf(it) },
                        sort = intent.getStringExtra("sortBy"),
                        status = intent.getStringExtra("status"),
                        source = intent.getStringExtra("source"),
                        countryOfOrigin = intent.getStringExtra("country"),
                        season = intent.getStringExtra("season"),
                        seasonYear = if (intent.getStringExtra("type") == "ANIME") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        startYear = if (intent.getStringExtra("type") == "MANGA") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }

                aniMangaResult = model.aniMangaSearchResults
                mediaAdaptor =
                    MediaAdaptor(
                        style,
                        model.aniMangaSearchResults.results,
                        this,
                        matchParent = true,
                        onItemClickOverride = { media -> handleSearchResultClick(media) }
                    )
            }

            SearchType.CHARACTER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.characterSearchResults = CharacterSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                characterResult = model.characterSearchResults
                characterAdaptor = CharacterAdapter(model.characterSearchResults.results as MutableList<Character>)
            }

            SearchType.STUDIO -> {
                if (model.notSet) {
                    model.notSet = false
                    model.studioSearchResults = StudioSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                studioResult = model.studioSearchResults
                studioAdaptor = StudioAdapter(model.studioSearchResults.results as MutableList<Studio>)
            }

            SearchType.STAFF -> {
                if (model.notSet) {
                    model.notSet = false
                    model.staffSearchResults = StaffSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                staffResult = model.staffSearchResults
                staffAdaptor = AuthorAdapter(model.staffSearchResults.results as MutableList<Author>)
            }

            SearchType.USER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.userSearchResults = UserSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                userResult = model.userSearchResults
                usersAdapter = UsersAdapter(model.userSearchResults.results as MutableList<User>, grid = true)
            }
        }

        progressAdapter = ProgressAdapter(searched = model.searched)
        headerAdaptor = if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            SearchAdapter(this, searchType, binding.searchHeader)
        } else {
            SupportingSearchAdapter(this, searchType, binding.searchHeader)
        }
        headerAdaptor.bind()

        val gridSize = (screenWidth / 120f).toInt().coerceIn(3, 6)
        val gridLayoutManager = GridLayoutManager(this, gridSize)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position in fullSpanPositions) return gridSize
                return when (position) {
                    concatAdapter.itemCount - 1 -> gridSize
                    else -> when (style) {
                        0 -> 1
                        else -> gridSize
                    }
                }
            }
        }

        concatAdapter = when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                ConcatAdapter(mediaAdaptor, progressAdapter)
            }

            SearchType.CHARACTER -> {
                ConcatAdapter(characterAdaptor, progressAdapter)
            }

            SearchType.STUDIO -> {
                ConcatAdapter(studioAdaptor, progressAdapter)
            }

            SearchType.STAFF -> {
                ConcatAdapter(staffAdaptor, progressAdapter)
            }

            SearchType.USER -> {
                ConcatAdapter(usersAdapter, progressAdapter)
            }
        }

        binding.searchRecyclerView.layoutManager = gridLayoutManager
        binding.searchRecyclerView.adapter = concatAdapter

        suggestionsAdapter = SearchSuggestionsAdapter(
            onPick = { media -> openSuggestion(media) },
            onSeeAll = { q ->
                headerAdaptor.seeAllResults(q)
            },
            onClosed = {
                closeSuggestions()
            }
        )
        binding.searchSuggestionsList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.searchSuggestionsList.adapter = suggestionsAdapter

        binding.searchRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (!v.canScrollVertically(1)) {
                    if (isAniListOnly() && model.hasNextPage(searchType) &&
                        model.resultsIsNotEmpty(searchType) && !loading
                    ) {
                        scope.launch(Dispatchers.IO) {
                            model.loadNextPage(searchType)
                        }
                    }
                }
                super.onScrolled(v, dx, dy)
            }
        })

        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                model.getSearch<AniMangaSearchResults>(searchType).observe(this) {
                    if (!isAniListOnly()) return@observe
                    if (it != null) {
                        model.aniMangaSearchResults.apply {
                            onList = it.onList
                            isAdult = it.isAdult
                            perPage = it.perPage
                            search = it.search
                            sort = it.sort
                            genres = it.genres
                            excludedGenres = it.excludedGenres
                            excludedTags = it.excludedTags
                            tags = it.tags
                            season = it.season
                            startYear = it.startYear
                            seasonYear = it.seasonYear
                            status = it.status
                            source = it.source
                            format = it.format
                            countryOfOrigin = it.countryOfOrigin
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.aniMangaSearchResults.results.size
                        val newResults = it.results.distinctBy { it.id }.filter { newItem -> model.aniMangaSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                        model.aniMangaSearchResults.results.addAll(newResults)
                        mediaAdaptor.notifyItemRangeInserted(prev, newResults.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                        updateResultCount()
                    }
                }
            }

            SearchType.CHARACTER -> {
                model.getSearch<CharacterSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.characterSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.characterSearchResults.results.size
                        @Suppress("UNCHECKED_CAST")
                        val newResults = (it.results as List<Character>).distinctBy { it.id }.filter { newItem -> (model.characterSearchResults.results as List<Character>).none { oldItem -> oldItem.id == newItem.id } }
                        model.characterSearchResults.results.addAll(newResults)
                        characterAdaptor.notifyItemRangeInserted(prev, newResults.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                        updateResultCount()
                    }
                }
            }

            SearchType.STUDIO -> {
                model.getSearch<StudioSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.studioSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.studioSearchResults.results.size
                        @Suppress("UNCHECKED_CAST")
                        val newResults = (it.results as List<Studio>).distinctBy { it.id }.filter { newItem -> (model.studioSearchResults.results as List<Studio>).none { oldItem -> oldItem.id == newItem.id } }
                        model.studioSearchResults.results.addAll(newResults)
                        studioAdaptor.notifyItemRangeInserted(prev, newResults.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                        updateResultCount()
                    }
                }
            }

            SearchType.STAFF -> {
                model.getSearch<StaffSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.staffSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.staffSearchResults.results.size
                        @Suppress("UNCHECKED_CAST")
                        val newResults = (it.results as List<Author>).distinctBy { it.id }.filter { newItem -> (model.staffSearchResults.results as List<Author>).none { oldItem -> oldItem.id == newItem.id } }
                        model.staffSearchResults.results.addAll(newResults)
                        staffAdaptor.notifyItemRangeInserted(prev, newResults.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                        updateResultCount()
                    }
                }
            }

            SearchType.USER -> {
                model.getSearch<UserSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.userSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.userSearchResults.results.size
                        @Suppress("UNCHECKED_CAST")
                        val newResults = (it.results as List<User>).distinctBy { it.id }.filter { newItem -> (model.userSearchResults.results as List<User>).none { oldItem -> oldItem.id == newItem.id } }
                        model.userSearchResults.results.addAll(newResults)
                        usersAdapter.notifyItemRangeInserted(prev, newResults.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                        updateResultCount()
                    }
                }
            }
        }

        binding.searchRecyclerView.post { runInitialSearchActions(notSet) }
    }

    fun emptyMediaAdapter() {
        searchTimer.cancel()
        searchTimer.purge()

        suggestionHandler.removeCallbacksAndMessages(null)
        suggestionJob?.cancel()
        binding.searchEmptyState.visibility = View.GONE
        binding.searchSuggestionsPanel.visibility = View.GONE
        binding.searchRecyclerView.visibility = View.VISIBLE

        binding.searchRecyclerView.clearFocus()
        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                mediaAdaptor.notifyItemRangeRemoved(0, model.aniMangaSearchResults.results.size)
                model.aniMangaSearchResults.results.clear()
            }

            SearchType.CHARACTER -> {
                characterAdaptor.notifyItemRangeRemoved(
                    0,
                    model.characterSearchResults.results.size
                )
                model.characterSearchResults.results.clear()
            }

            SearchType.STUDIO -> {
                studioAdaptor.notifyItemRangeRemoved(0, model.studioSearchResults.results.size)
                model.studioSearchResults.results.clear()
            }

            SearchType.STAFF -> {
                staffAdaptor.notifyItemRangeRemoved(0, model.staffSearchResults.results.size)
                model.staffSearchResults.results.clear()
            }

            SearchType.USER -> {
                usersAdapter.notifyItemRangeRemoved(0, model.userSearchResults.results.size)
                model.userSearchResults.results.clear()
            }
        }
        progressAdapter.bar?.visibility = View.GONE
    }

    private var searchTimer = Timer()
    private var loading = false
    fun search() {
        suggestionHandler.removeCallbacksAndMessages(null)
        suggestionJob?.cancel()
        closeSuggestions()
        headerAdaptor.setHistoryVisibility(false)
        val size = model.size(searchType)
        model.clearResults(searchType)
        if ((searchType == SearchType.ANIME || searchType == SearchType.MANGA) &&
            !isAniListOnly() && this::binding.isInitialized &&
            this::mediaAdaptor.isInitialized && this::progressAdapter.isInitialized
        ) {
            restoreSingleAdapter()
            mediaAdaptor.notifyDataSetChanged()
        }

        binding.searchRecyclerView.clearFocus()
        binding.searchRecyclerView.post {
            when (searchType) {
                SearchType.ANIME, SearchType.MANGA -> {
                    mediaAdaptor.notifyItemRangeRemoved(0, size)
                }

                SearchType.CHARACTER -> {
                    characterAdaptor.notifyItemRangeRemoved(0, size)
                }

                SearchType.STUDIO -> {
                    studioAdaptor.notifyItemRangeRemoved(0, size)
                }

                SearchType.STAFF -> {
                    staffAdaptor.notifyItemRangeRemoved(0, size)
                }

                SearchType.USER -> {
                    usersAdapter.notifyItemRangeRemoved(0, size)
                }
            }
        }

        progressAdapter.bar?.visibility = View.VISIBLE

        searchTimer.cancel()
        searchTimer.purge()
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                if ((searchType == SearchType.ANIME || searchType == SearchType.MANGA) &&
                    !isAniListOnly()
                ) {
                    runMultiSourceSearch()
                    return
                }
                scope.launch(Dispatchers.IO) {
                    loading = true
                    model.loadSearch(searchType)
                    loading = false
                }
            }
        }
        searchTimer = Timer()
        searchTimer.schedule(timerTask, 500)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun recycler() {
        if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            if (groupedAdapters.isNotEmpty()) {
                groupedAdapters.forEach { adapter ->
                    if (adapter is MediaAdaptor) {
                        adapter.type = style
                        adapter.refreshCache()
                        adapter.notifyDataSetChanged()
                    }
                }
            } else {
                mediaAdaptor.type = style
                mediaAdaptor.refreshCache()
                mediaAdaptor.notifyDataSetChanged()
            }
        }
    }

    private fun runInitialSearchActions(notSet: Boolean) {
        if (isFinishing || isDestroyed) return

        if (!notSet) {
            if (!model.searched) {
                model.searched = true
                headerAdaptor.search?.run()
            }
        } else {
            headerAdaptor.requestFocus?.run()
        }

        if (intent.getBooleanExtra("search", false)) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
            search()
        }
    }

    var state: Parcelable? = null
    override fun onPause() {
        if (this::headerAdaptor.isInitialized) {
            headerAdaptor.addHistory()
        }
        super.onPause()
        state = binding.searchRecyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        binding.searchRecyclerView.layoutManager?.onRestoreInstanceState(state)
    }

    override fun onDestroy() {
        suggestionHandler.removeCallbacksAndMessages(null)
        suggestionJob?.cancel()
        multiSourceJob?.cancel()
        super.onDestroy()
    }
}
