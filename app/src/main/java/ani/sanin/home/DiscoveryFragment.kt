package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import android.widget.ImageButton
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.kitsu.Kitsu
import ani.sanin.connections.kitsu.KitsuCategory
import ani.sanin.databinding.FragmentDiscoverBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaDetailsActivity
import ani.sanin.media.SearchActivity
import ani.sanin.home.SearchBottomSheet
import ani.sanin.reels.ReelFeed
import ani.sanin.youtube.ReelShortsPlayerActivity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import ani.sanin.cloudstream.TmdbCards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger

class DiscoverViewModel : ViewModel() {
    private val _categories = MutableStateFlow<List<KitsuCategory>>(emptyList())
    val categories: StateFlow<List<KitsuCategory>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow<KitsuCategory?>(null)
    val selectedCategory: StateFlow<KitsuCategory?> = _selectedCategory.asStateFlow()

    private val _selectedSeason = MutableStateFlow(DiscoverSeason.current())
    val selectedSeason: StateFlow<DiscoverSeason> = _selectedSeason.asStateFlow()

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var pageJob: Job? = null
    private var categoriesJob: Job? = null
    private var loaded = false

    private val fetchGeneration = AtomicInteger(0)

    fun loadCategories() {
        if (_categories.value.isNotEmpty() || categoriesJob?.isActive == true) return
        categoriesJob = viewModelScope.launch(Dispatchers.IO) {

            val categories = Kitsu.allCategories()
            if (categories.isNotEmpty()) _categories.value = categories
        }
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun selectCategory(category: KitsuCategory?) {
        _selectedCategory.value = category
        refresh()
    }

    fun selectSeason(season: DiscoverSeason) {
        _selectedSeason.value = season

        _selectedCategory.value = null
        refresh()
    }

    fun retry() = refresh()

    fun refresh() {

        val generation = fetchGeneration.incrementAndGet()
        val category = _selectedCategory.value
        val season = _selectedSeason.value
        pageJob?.cancel()
        _uiState.value = DiscoverUiState(queryId = generation, isInitialLoading = true)
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            if (category == null) {
                loadAnilist(generation, season)
            } else {
                appendCategoryPages(generation, category, baseItems = emptyList(), basePage = 0)
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.queryId != fetchGeneration.get()) return
        if (state.isInitialLoading || state.isLoadingMore || !state.hasMore ||
            state.error || state.loadMoreError
        ) {
            return
        }
        val category = _selectedCategory.value ?: return
        _uiState.value = state.copy(isLoadingMore = true, loadMoreError = false)
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            appendCategoryPages(state.queryId, category, baseItems = state.items, basePage = state.page)
        }
    }

    fun retryLoadMore() {
        val state = _uiState.value
        if (state.isLoadingMore) return
        _uiState.value = state.copy(loadMoreError = false)
        loadMore()
    }

    private suspend fun loadAnilist(generation: Int, season: DiscoverSeason) {

        val list = runCatching {
            Anilist.query.discoverAnime(
                season = season.apiValue,
                seasonYear = season.year,
            )
        }.getOrNull()
        if (generation != fetchGeneration.get()) return
        _uiState.value = if (list == null) {
            DiscoverUiState(queryId = generation, error = true)
        } else {
            DiscoverUiState(queryId = generation, items = list.distinctBy { it.id })
        }
    }

    private suspend fun appendCategoryPages(
        generation: Int,
        category: KitsuCategory,
        baseItems: List<Media>,
        basePage: Int,
    ) {
        val items = ArrayList(baseItems)
        val seenIds = HashSet<Int>(baseItems.size * 2)
        baseItems.forEach { seenIds.add(it.id) }
        var page = basePage
        var fetched = 0
        while (generation == fetchGeneration.get() && fetched < MAX_PAGES_PER_TRIGGER) {
            val result = Kitsu.animeByCategory(category.slug, page + 1)
            if (generation != fetchGeneration.get()) return
            if (result.failed) {

                val nothingYet = items.isEmpty()
                _uiState.value = DiscoverUiState(
                    queryId = generation,
                    items = items,
                    page = page,
                    hasMore = !nothingYet,
                    error = nothingYet,
                    loadMoreError = !nothingYet,
                )
                return
            }
            page += 1
            fetched += 1
            var added = 0
            for (media in result.media) {
                if (seenIds.add(media.id)) {
                    items.add(media)
                    added++
                }
            }
            if (!result.hasMore) {
                _uiState.value = DiscoverUiState(
                    queryId = generation,
                    items = items,
                    page = page,
                    hasMore = false,
                )
                return
            }
            if (added >= PAGE_FILL_TARGET) break
        }
        _uiState.value = DiscoverUiState(
            queryId = generation,
            items = items,
            page = page,
            hasMore = true,
        )
    }

    private companion object {

        const val PAGE_FILL_TARGET = 20

        const val MAX_PAGES_PER_TRIGGER = 5
    }
}

data class DiscoverUiState(
    val queryId: Int = 0,
    val items: List<Media> = emptyList(),
    val page: Int = 0,
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
)

data class DiscoverSeason(
    val label: String,
    val apiValue: String,
    val year: Int,
) {
    companion object {
        private fun seasonFor(month: Int): String = when (month) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }

        fun current(): DiscoverSeason {
            val cal = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            val season = seasonFor(month)
            return DiscoverSeason(
                label = "${season.lowercase().replaceFirstChar { it.uppercase() }} $year",
                apiValue = season,
                year = year,
            )
        }

        fun seasons(): List<DiscoverSeason> {
            val cal = Calendar.getInstance()
            val curMonth = cal.get(Calendar.MONTH) + 1
            val curYear = cal.get(Calendar.YEAR)
            val result = mutableListOf<DiscoverSeason>()
            val nextIdx = SEASON_ORDER.indexOf(seasonFor(curMonth)) + 1
            val nextSeason = SEASON_ORDER[nextIdx % 4]
            val nextYear = if (nextIdx >= 4) curYear + 1 else curYear
            result.add(DiscoverSeason("${nextSeason.lowercase().replaceFirstChar { it.uppercase() }} $nextYear", nextSeason, nextYear))
            var idx = SEASON_ORDER.indexOf(seasonFor(curMonth))
            var yr = curYear
            repeat(5) {
                result.add(DiscoverSeason("${SEASON_ORDER[idx].lowercase().replaceFirstChar { it.uppercase() }} $yr", SEASON_ORDER[idx], yr))
                idx = (idx - 1 + 4) % 4
                if (idx == 3) yr--
            }
            return result
        }

        private val SEASON_ORDER = listOf("WINTER", "SPRING", "SUMMER", "FALL")
    }
}

class DiscoveryFragment : Fragment() {
    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    private val discoverViewModel: DiscoverViewModel by activityViewModels()
    private var kitsuPicks: List<ani.sanin.connections.kitsu.KitsuDiscoveryItem> = emptyList()
    private var kitsuLoaded = false
    private var tmdbAnimationPicks: List<ani.sanin.connections.tmdb.TmdbMedia> = emptyList()
    private var tmdbAnimationLoaded = false
    private var kitsuCategories: List<ani.sanin.connections.kitsu.KitsuCategory> = emptyList()
    private var categoryArtwork: Map<String, String?> = emptyMap()
    private val categoryArtworkLoading = HashSet<String>()
    private val categoryArtworkAttempts = HashMap<String, Int>()
    private val categoryArtworkSemaphore = Semaphore(4)
    private val reservedCategoryArtworkUrls = HashSet<String>()
    private var allDiscoveryCategories: List<CuratedDiscoveryCategory> = CuratedDiscoveryCategories.all
    private var remainingCategoriesLoaded = false
    private var remainingCategoriesLoading = false
    private var categoryArtworkGeneration = 0
    private var categoriesLoaded = false
    private var reelRailLoaded = false
    private var reelEntries: List<ReelFeed.ReelItem> = emptyList()
    private var reelRailCards: List<ReelCard> = emptyList()

    private var animeCategoryOpen = false
    private var shelvesScrollY = 0
    private var animeCategorySlug = ""
    private var animeCategoryTitle = ""
    private var animeCategoryDescription = ""
    private var animeCategoryOffset = 0
    private var animeCategoryLoading = false
    private var animeCategoryEndReached = false
    private var animeCategoryRetries = 0
    private lateinit var animeCategoryAdapter: DiscoveryStandardCardAdapter
    private lateinit var animeCategoryListRowAdapter: DiscoveryListRowAdapter
    private val animeCategoryCards = mutableListOf<DiscoveryStandardCardAdapter.Card>()
    private var animeCategoryGridButton: ImageButton? = null
    private var animeCategoryListButton: ImageButton? = null
    private var animeCategoryBackCallback: OnBackPressedCallback? = null
    private var animeExhaustedAnnounced = false

    private data class ReelCard(
        val title: String,
        val key: String,
        val startIndex: Int,
    )

    private lateinit var gridItems: MutableList<Media>
    private lateinit var gridAdapter: MediaAdaptor

    private var renderedQueryId = -1

    private var isTv = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    private data class CategoryArtworkTag(val url: String?, val loaded: Boolean = false)

    private var visibleCuratedCategoryCount = CuratedDiscoveryCategories.all.size
    private var renderedCuratedCategoryCount = 0
    private var renderedCategoryColumnCount = 0
    private var categoryGridScrollListenerInstalled = false

    private companion object {
        const val CATEGORY_CARD_HEIGHT_DP = 100
        const val CATEGORY_COLUMN_GAP_DP = 18
        const val CATEGORY_ROW_GAP_DP = 18
        const val CATEGORY_SIDE_PADDING_DP = 3
        const val MIN_CATEGORY_CONTENT_WIDTH_DP = 140
        const val MAX_CATEGORY_COLUMNS = 2

        const val CATEGORY_ARTWORK_MAX_ATTEMPTS = 3

        const val RAIL_ANIME_COUNT = 12

        const val ANIME_CATEGORY_PAGE_SIZE = 20
    }

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        isTv = isTvDevice(requireContext())
        visibleCuratedCategoryCount = CuratedDiscoveryCategories.all.size
        renderedCuratedCategoryCount = 0
        allDiscoveryCategories = CuratedDiscoveryCategories.all
        categoryArtwork = DiscoveryArtworkStore.animePosters(requireContext())
        categoryArtworkLoading.clear()
        categoryArtworkAttempts.clear()
        synchronized(reservedCategoryArtworkUrls) {
            reservedCategoryArtworkUrls.clear()
            reservedCategoryArtworkUrls.addAll(categoryArtwork.values.filterNotNull())
        }
        remainingCategoriesLoaded = false
        remainingCategoriesLoading = false
        categoryArtworkGeneration++
        setupSearchBar()
        setupAvatar()
        setupAnimeCategoryResults()

        binding.genreChipGroup.visibility = View.GONE
        binding.seasonChipGroup.visibility = View.GONE
        binding.discoverRecyclerView.visibility = View.GONE
        binding.discoverProgressBar.visibility = View.GONE
        binding.discoverEmptyText.visibility = View.GONE
        binding.discoverErrorGroup.visibility = View.GONE
        binding.discoverLoadingMore.visibility = View.GONE
        binding.discoverLoadMoreErrorGroup.visibility = View.GONE
        binding.discoverEditorialShelves.visibility = View.GONE

        binding.discoverSearchBar.nextFocusDownId = View.NO_ID
        binding.discoverAvatar.nextFocusDownId = View.NO_ID
        binding.discoverAvatar.nextFocusLeftId = R.id.discoverSearchBar

        
        binding.discoverCategoryResultsGrid.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (animeCategoryOpen) {
                    Logger.log("AnimeShelf grid state=$newState gridH=${rv.height} nested=${rv.isNestedScrollingEnabled}")
                }
            }

            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!animeCategoryOpen) return
                Logger.log("AnimeShelf grid scrolled dx=$dx dy=$dy")
                if (dy <= 0) return
                val count = rv.adapter?.itemCount ?: return
                val lm = rv.layoutManager
                val last = when (lm) {
                    is androidx.recyclerview.widget.GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is androidx.recyclerview.widget.LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (last >= count - 8) loadAnimeCategoryPage()
            }
        })
        binding.discoverScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (animeCategoryOpen) {
                Logger.log("AnimeShelf outer scrollY=$scrollY viewport=${binding.discoverScroll.height}")
                val resultsContent = binding.discoverScroll.getChildAt(0)?.height
                    ?: return@setOnScrollChangeListener
                if (scrollY + binding.discoverScroll.height >= resultsContent - dp(500)) {
                    if (animeCategoryEndReached && animeCategoryCards.isNotEmpty()) showAnimeExhaustedSnackbar()
                    else loadAnimeCategoryPage()
                }
                return@setOnScrollChangeListener
            }
            val contentHeight = binding.discoverScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            if (scrollY + binding.discoverScroll.height >= contentHeight - dp(500)) {
                if (visibleCuratedCategoryCount < allDiscoveryCategories.size) {
                    revealNextCategoryBatch()
                } else if (!remainingCategoriesLoaded) {
                    loadNextRemainingCategoryBatch()
                }
            }
            loadVisibleCategoryArtwork()
        }
        renderCategoryGrid()

        binding.discoverCategoryGrid.post { if (_binding != null) loadVisibleCategoryArtwork() }

        loadReelRail()

        loadNextRemainingCategoryBatch()
        if (isTv) {
            binding.discoverSearchBar.post {
                if (isAdded) binding.discoverSearchBar.requestFocus()
            }
        }
    }

    private fun revealNextCategoryBatch() {
        if (visibleCuratedCategoryCount >= allDiscoveryCategories.size) return
        val start = visibleCuratedCategoryCount
        visibleCuratedCategoryCount = (visibleCuratedCategoryCount + 10)
            .coerceAtMost(allDiscoveryCategories.size)
        renderCategoryGrid()
        loadVisibleCategoryArtwork()
    }

    private fun discoveryCategoryDescription(category: KitsuCategory): String {
        val title = category.title.trim()
        val key = title.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val exact = mapOf(
            "school" to "Hallway crushes, classroom chaos, and drama after the bell.",
            "blackmail" to "One secret in the wrong hands changes everything.",
            "samurai" to "Honor draws the blade. Survival decides the rest.",
            "swordplay" to "Steel sings, skill decides, and every duel has a story.",
            "assassination" to "One target, a risky plan, and no room for mistakes.",
            "revenge" to "Old wounds make dangerous plans.",
            "time travel" to "Change one moment. Face a thousand consequences.",
            "parallel world" to "A new world, unfamiliar rules, and no easy way home.",
            "survival" to "Every choice buys time—or costs it.",
            "bullying" to "Power games, hard truths, and standing your ground.",
            "workplace" to "Office politics by day, personal chaos after hours.",
            "delinquents" to "Bad reputations, fierce loyalty, and trouble on every corner.",
            "mafia" to "Family ties get messy when business turns personal.",
            "detective" to "Follow the clues. Trust nobody too quickly.",
            "ghost" to "The past refuses to stay buried.",
            "zombies" to "The world falls apart; getting through today is the win.",
            "robots" to "Human stakes, machine muscle, and futures on the line.",
            "aliens" to "First contact rarely goes according to plan.",
            "idol" to "Bright lights, brutal practice, and the dream behind the smile.",
            "cooking" to "Big flavours, fierce rivals, and plates worth fighting for.",
            "revenge" to "Old wounds make dangerous plans.",
            "twins" to "Double the trouble, with secrets to match.",
            "body swapping" to "Wrong body, wrong life, and a very strange Monday.",
            "gender bender" to "Identity gets complicated when expectations flip.",
            "unrequited love" to "One heart speaks; the other may never hear it.",
            "love triangle" to "Three hearts, crossed signals, zero simple answers.",
            "childhood friends" to "Years of history, one new feeling, and everything at stake.",
            "found family" to "Strangers become your people, one mess at a time.",
            "coming of age" to "Growing up is the plot twist nobody dodges.",
            "tragic romance" to "Tender moments, impossible timing, and feelings that linger.",
            "psychological" to "Mind games, shifting truths, and a reality you can't quite trust.",
            "post apocalyptic" to "After the end, the hardest part is starting over.",
            "dystopia" to "A broken system—and someone reckless enough to challenge it.",
            "space" to "Big skies, bigger stakes, and a long way from home.",
            "pirates" to "Treasure, treachery, and trouble on the open sea.",
            "ghost stories" to "Every haunting comes with a story.",
            "school life" to "Hallway crushes, classroom chaos, and drama after the bell."
        )
        exact[key]?.let { return it }

        return when {
            listOf("blackmail", "extortion", "hostage", "kidnap").any(key::contains) ->
                "Secrets become leverage, and nobody gets a clean exit."
            listOf("school", "student", "classroom", "campus", "academy").any(key::contains) ->
                "Hallway drama, unlikely friendships, and chaos between classes."
            listOf("sword", "samurai", "ninja", "martial", "fencing", "duel").any(key::contains) ->
                "Sharp skills, sharper rivals, and every move matters."
            listOf("love", "romance", "couple", "kiss", "dating", "heartbreak").any(key::contains) ->
                "Mixed signals, big feelings, and timing that never helps."
            listOf("battle", "war", "combat", "fight", "military", "weapon").any(key::contains) ->
                "High stakes, hard choices, and no easy victories."
            listOf("mystery", "crime", "detective", "thriller", "conspiracy", "suspense").any(key::contains) ->
                "Clues pile up. The truth keeps moving."
            listOf("ghost", "horror", "demon", "vampire", "monster", "occult", "haunted").any(key::contains) ->
                "Something is lurking—and it knows more than it should."
            listOf("magic", "fantasy", "witch", "wizard", "dragon", "myth").any(key::contains) ->
                "Strange powers, stranger rules, and trouble around every corner."
            listOf("space", "sci fi", "science fiction", "cyber", "robot", "mecha", "android").any(key::contains) ->
                "Unfamiliar futures, bold ideas, and technology with consequences."
            listOf("sport", "soccer", "football", "baseball", "basketball", "tennis", "volleyball", "racing", "boxing").any(key::contains) ->
                "Rivals, relentless practice, and a moment to prove it."
            listOf("music", "band", "sing", "dance", "idol", "perform", "theatre").any(key::contains) ->
                "Rehearsals, rivalries, and the rush of taking the stage."
            listOf("food", "cook", "cooking", "restaurant", "gourmet").any(key::contains) ->
                "Passion on the plate and competition turned up high."
            listOf("family", "friend", "childhood", "sibling", "parent").any(key::contains) ->
                "Complicated bonds, shared history, and showing up for each other."
            listOf("comedy", "parody", "absurd", "gag", "slapstick").any(key::contains) ->
                "Bad plans, great timing, and chaos with perfect delivery."
            listOf("travel", "journey", "adventure", "exploration").any(key::contains) ->
                "New places, unexpected detours, and stories waiting around the bend."
            else ->
                "A distinct anime trope, with its own kind of chaos."
        }
    }

    private fun loadNextRemainingCategoryBatch() {
        if (remainingCategoriesLoading || remainingCategoriesLoaded) return
        remainingCategoriesLoading = true
        renderCategoryGrid()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val fetched = runCatching { Kitsu.allCategories() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                remainingCategoriesLoading = false
                if (fetched.isEmpty()) {

                    renderCategoryGrid()
                    return@withContext
                }
                val chosenSlugs = CuratedDiscoveryCategories.all.mapTo(HashSet()) { it.kitsuSlug }
                val remaining = fetched
                    .asSequence()
                    .filter { it.slug !in chosenSlugs }
                    .distinctBy { it.slug }
                    .sortedByDescending { it.mediaCount }
                    .take(86)
                    .map { category ->
                        CuratedDiscoveryCategory(
                            title = category.title,
                            kitsuSlug = category.slug,
                            description = discoveryCategoryDescription(category)
                        )
                    }
                    .toList()
                allDiscoveryCategories = CuratedDiscoveryCategories.all + remaining
                remainingCategoriesLoaded = true
                if (visibleCuratedCategoryCount < allDiscoveryCategories.size) {
                    revealNextCategoryBatch()
                } else {
                    renderCategoryGrid()
                }
            }
        }
    }

    private fun updateCategoryArtworkUrls() {
        val host = _binding?.discoverCategoryGrid ?: return
        for (rowIndex in 1 until host.childCount) {
            val row = host.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (cardIndex in 0 until row.childCount) {
                val card = row.getChildAt(cardIndex) as? LinearLayout ?: continue
                val slug = card.tag as? String ?: continue
                val frame = (0 until card.childCount)
                    .mapNotNull { card.getChildAt(it) as? FrameLayout }
                    .firstOrNull() ?: continue
                val image = frame.getChildAt(0) as? ImageView ?: continue
                val url = categoryArtwork[slug]
                val old = image.tag as? CategoryArtworkTag
                if (old?.url != url) image.tag = CategoryArtworkTag(url)
            }
        }
    }

    private fun loadVisibleCategoryArtwork() {
        val currentBinding = _binding ?: return
        val host = currentBinding.discoverCategoryGrid
        val scroll = currentBinding.discoverScroll

        if (scroll.height <= 0 || host.height <= 0) return
        val viewportTop = scroll.scrollY - dp(240)
        val viewportBottom = scroll.scrollY + scroll.height + dp(500)
        for (rowIndex in 1 until host.childCount) {
            val row = host.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (cardIndex in 0 until row.childCount) {
                val card = row.getChildAt(cardIndex) as? LinearLayout ?: continue
                val cardTop = host.top + row.top + card.top
                val cardBottom = cardTop + card.height
                if (cardBottom < viewportTop || cardTop > viewportBottom) continue
                val slug = card.tag as? String ?: continue
                val frame = (0 until card.childCount)
                    .mapNotNull { card.getChildAt(it) as? FrameLayout }
                    .firstOrNull() ?: continue
                val image = frame.getChildAt(0) as? ImageView ?: continue
                val tag = image.tag as? CategoryArtworkTag ?: continue
                val url = tag.url
                if (!url.isNullOrBlank()) {
                    if (!tag.loaded) {
                        image.loadImage(url, dp(82))
                        image.tag = tag.copy(loaded = true)
                    }
                    continue
                }

                if (categoryArtwork.containsKey(slug) || !categoryArtworkLoading.add(slug)) continue
                val attempts = categoryArtworkAttempts.getOrDefault(slug, 0)
                if (attempts >= CATEGORY_ARTWORK_MAX_ATTEMPTS) {
                    categoryArtworkLoading.remove(slug)
                    continue
                }
                categoryArtworkAttempts[slug] = attempts + 1
                val generation = categoryArtworkGeneration
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {

                    val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
                    val poster = categoryArtworkSemaphore.withPermit {

                        if (generation != categoryArtworkGeneration) return@withPermit null
                        runCatching {
                            val covers = Kitsu.animeByCategory(slug, page = 1).media
                                .filter { !it.isAdult }
                                .mapNotNull { it.cover?.takeIf(String::isNotBlank) }
                                .distinct()
                            synchronized(reservedCategoryArtworkUrls) {
                                val picked = covers.filterNot { it in reservedCategoryArtworkUrls }
                                    .randomOrNull() ?: covers.randomOrNull()
                                if (!picked.isNullOrBlank()) reservedCategoryArtworkUrls.add(picked)
                                picked
                            }
                        }.onSuccess { resolved.set(true) }.getOrNull()
                    }
                    withContext(Dispatchers.Main) {

                        if (_binding == null || generation != categoryArtworkGeneration) return@withContext
                        categoryArtworkLoading.remove(slug)
                        if (resolved.get()) {
                            if (!poster.isNullOrBlank()) {
                                DiscoveryArtworkStore.putAnime(requireContext(), slug, poster)
                            }
                            categoryArtwork = categoryArtwork + (slug to poster)
                        }
                        updateCategoryArtworkUrls()
                        loadVisibleCategoryArtwork()
                    }
                }
            }
        }
    }

    private fun loadKitsuEditorial() {
        if (kitsuLoaded) return
        kitsuLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            kitsuPicks = runCatching {
                ani.sanin.connections.kitsu.Kitsu.discoverEditorial(limit = 20)
            }.getOrDefault(emptyList())
            if (_binding != null) renderEditorialShelves(discoverViewModel.uiState.value.items)
        }
    }

    private fun loadTmdbAnimationEditorial() {
        if (tmdbAnimationLoaded) return
        tmdbAnimationLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            tmdbAnimationPicks = runCatching {
                val client = ani.sanin.connections.tmdb.Tmdb
                val films = client.discover(
                    mediaType = "movie", genres = "16", sort = "popularity.desc"
                )
                val series = client.discover(
                    mediaType = "tv", genres = "16", sort = "popularity.desc"
                )
                films.zip(series).flatMap { (film, show) -> listOf(film, show) }
                    .plus(if (films.size > series.size) films.drop(series.size) else emptyList())
                    .plus(if (series.size > films.size) series.drop(films.size) else emptyList())
                    .distinctBy { it.type to it.id }
                    .take(12)
            }.getOrDefault(emptyList())
            if (_binding != null) {
                renderEditorialShelves(discoverViewModel.uiState.value.items)
                loadReelRail()
            }
        }
    }

    private fun loadKitsuCategories() {

        renderCategoryGrid()
    }

    private fun renderCategoryGrid() {
        val host = _binding?.discoverCategoryGrid ?: return
        val context = host.context
        if (host.childCount == 0) {
            renderedCuratedCategoryCount = 0
            host.orientation = LinearLayout.VERTICAL
            host.setPadding(dp(CATEGORY_SIDE_PADDING_DP), dp(4), dp(CATEGORY_SIDE_PADDING_DP), dp(16))
            host.addView(TextView(context).apply {
                text = "FIND YOUR NEXT OBSESSION"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(12), dp(16), dp(12), dp(12))
            })
            host.addView(TextView(context).apply {
                text = "150 moods, tropes, worlds, and rabbit holes, sorted for whatever you're in the mood for."
                textSize = 13f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(12), 0, dp(12), dp(12))
            })
        }

        host.findViewWithTag<View>("curated-more-footer")?.let { host.removeView(it) }
        val categories = allDiscoveryCategories
        val shownCount = visibleCuratedCategoryCount.coerceAtMost(categories.size)
        if (host.width <= 0) {
            host.post { if (_binding != null) { renderCategoryGrid(); loadVisibleCategoryArtwork() } }
            return
        }
        val availableWidthDp = ((host.width - host.paddingLeft - host.paddingRight) /
            resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val columnCount = ((availableWidthDp + CATEGORY_COLUMN_GAP_DP) /
            (MIN_CATEGORY_CONTENT_WIDTH_DP + CATEGORY_COLUMN_GAP_DP))
            .coerceIn(1, MAX_CATEGORY_COLUMNS)
        if (renderedCategoryColumnCount != 0 && renderedCategoryColumnCount != columnCount) {
            val focusedSlug = host.findFocus()?.tag as? String

            while (host.childCount > 1) host.removeViewAt(1)
            renderedCuratedCategoryCount = 0
            renderedCategoryColumnCount = columnCount
            renderCategoryGrid()
            if (focusedSlug != null) {
                host.post {
                    (0 until host.childCount)
                        .asSequence()
                        .map { host.getChildAt(it) as? LinearLayout }
                        .filterNotNull()
                        .flatMap { row -> (0 until row.childCount).asSequence().map { row.getChildAt(it) } }
                        .firstOrNull { it.tag == focusedSlug }
                        ?.requestFocus()
                }
            }
            return
        }
        renderedCategoryColumnCount = columnCount
        if (shownCount > renderedCuratedCategoryCount) {
            val newCategories = categories.subList(renderedCuratedCategoryCount, shownCount)
            newCategories.chunked(columnCount).forEachIndexed { rowIndex, rowCategories ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(CATEGORY_ROW_GAP_DP) }
                }
                rowCategories.forEachIndexed { columnIndex, category ->
                    val card = LinearLayout(context).apply {
                        id = View.generateViewId()
                        tag = category.kitsuSlug
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isFocusable = true
                        isClickable = true
                        clipChildren = false
                        background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, dp(CATEGORY_CARD_HEIGHT_DP), 1f).apply {
                            if (columnIndex < columnCount - 1) marginEnd = dp(CATEGORY_COLUMN_GAP_DP)
                        }
                        contentDescription = "${category.title}. ${category.description}"
                        setOnClickListener {
                            openAnimeCategory(category.kitsuSlug, category.title, category.description)
                        }
                    }

                    val surfaceColor = context.getThemeColor(com.google.android.material.R.attr.colorSurface)
                    val artworkFrame = FrameLayout(context).apply {
                        clipChildren = false
                        clipToPadding = false
                        layoutParams = LinearLayout.LayoutParams(dp(82), dp(82))
                    }
                    val artworkImage = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                            }
                        }
                        setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                        contentDescription = "${category.title} category thumbnail"
                        rotation = if (columnIndex % 2 == 0) -8f else 8f
                        layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
                        tag = CategoryArtworkTag(categoryArtwork[category.kitsuSlug])
                    }
                    artworkFrame.addView(artworkImage)

                    fun addArtworkFade(
                        orientation: GradientDrawable.Orientation,
                        colors: IntArray,
                        params: FrameLayout.LayoutParams,
                    ) {
                        artworkFrame.addView(View(context).apply {
                            background = GradientDrawable(orientation, colors)
                            isClickable = false
                            isFocusable = false
                        }, params)
                    }
                    val fadeSize = dp(30)
                    addArtworkFade(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(surfaceColor, Color.TRANSPARENT),
                        FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                    )
                    addArtworkFade(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.TRANSPARENT, surfaceColor),
                        FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                    )
                    addArtworkFade(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(surfaceColor, Color.TRANSPARENT),
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.TOP)
                    )
                    addArtworkFade(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.TRANSPARENT, surfaceColor),
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.BOTTOM)
                    )
                    val textColumn = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    }
                    textColumn.addView(TextView(context).apply {
                        text = category.title
                        textSize = 14f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    })
                    textColumn.addView(TextView(context).apply {
                        text = category.description
                        textSize = 11.5f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        alpha = 0.88f
                        setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                        setPadding(0, dp(4), 0, 0)
                    })

                    if (columnIndex == 0) {
                        card.addView(artworkFrame)
                        card.addView(textColumn)
                    } else {
                        card.addView(textColumn)
                        card.addView(artworkFrame)
                    }
                    FocusEffectUtil.applyFocusListener(card)
                    if (renderedCuratedCategoryCount == 0 && rowIndex == 0 &&
                        category == newCategories.firstOrNull()
                    ) {
                        binding.discoverSearchBar.nextFocusDownId = card.id
                        binding.discoverAvatar.nextFocusDownId = card.id
                    }
                    row.addView(card)
                }
                if (rowCategories.size < columnCount) {
                    repeat(columnCount - rowCategories.size) { spacerIndex ->
                        row.addView(View(context).apply {
                            isFocusable = false
                            isClickable = false
                        }, LinearLayout.LayoutParams(0, dp(CATEGORY_CARD_HEIGHT_DP), 1f).apply {
                            if (spacerIndex < columnCount - rowCategories.size - 1) {
                                marginEnd = dp(CATEGORY_COLUMN_GAP_DP)
                            }
                        })
                    }
                }
                host.addView(row)
            }
            renderedCuratedCategoryCount = shownCount
        }

        if (shownCount < categories.size || !remainingCategoriesLoaded || remainingCategoriesLoading) {
            host.addView(TextView(context).apply {
                tag = "curated-more-footer"
                text = when {
                    remainingCategoriesLoading -> "FINDING MORE CATEGORIES…"
                    !remainingCategoriesLoaded -> "SCROLL FOR 10 MORE CATEGORIES"
                    shownCount < categories.size -> "SCROLL FOR 10 MORE CATEGORIES"
                    else -> ""
                }
                textSize = 11f
                gravity = Gravity.CENTER
                alpha = 0.72f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    when {
                        !remainingCategoriesLoaded -> loadNextRemainingCategoryBatch()
                        visibleCuratedCategoryCount < allDiscoveryCategories.size -> revealNextCategoryBatch()
                    }
                }
            })
        }
    }

    private fun setupAnimeCategoryResults() {
        val openCard: (DiscoveryStandardCardAdapter.Card) -> Unit = { card ->
            startActivity(
                Intent(requireContext(), MediaDetailsActivity::class.java)
                    .putExtra("mediaId", if (card.id > 0) card.id else 0)
                    .putExtra("mediaFallbackTitle", card.title)
            )
        }
        animeCategoryAdapter = DiscoveryStandardCardAdapter(animeCategoryCards, openCard)
        animeCategoryListRowAdapter = DiscoveryListRowAdapter(animeCategoryCards, openCard)
        val dm = resources.displayMetrics
        binding.discoverCategoryResultsGrid.isNestedScrollingEnabled = false
        binding.discoverCategoryResultsGrid.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (animeCategoryOpen) {
                Logger.log(
                    "AnimeShelf gridLayout h=${v.height} nested=${v.isNestedScrollingEnabled} " +
                        "canDown=${v.canScrollVertically(1)} canUp=${v.canScrollVertically(-1)} " +
                        "items=${binding.discoverCategoryResultsGrid.adapter?.itemCount ?: 0}"
                )
            }
        }
        binding.discoverCategoryResultsGrid.addOnItemTouchListener(
            object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    e: MotionEvent,
                ): Boolean {
                    if (animeCategoryOpen && !rv.isNestedScrollingEnabled) {
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                                Logger.log(
                                    "AnimeShelf touch DOWN gridH=${rv.height} " +
                                        "canDown=${rv.canScrollVertically(1)} " +
                                        "canUp=${rv.canScrollVertically(-1)} state=${rv.scrollState}"
                                )
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                rv.parent?.requestDisallowInterceptTouchEvent(false)
                                Logger.log(
                                    "AnimeShelf touch UP gridH=${rv.height} " +
                                        "canDown=${rv.canScrollVertically(1)} " +
                                        "canUp=${rv.canScrollVertically(-1)} state=${rv.scrollState}"
                                )
                            }
                        }
                    }
                    return false
                }
            }
        )
        animeCategoryBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeAnimeCategory()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, animeCategoryBackCallback!!)
    }

    private fun openAnimeCategory(slug: String, title: String, description: String) {
        if (animeCategoryOpen || slug.isBlank()) return
        Logger.log("AnimeShelf open slug=$slug title=$title viewport=${binding.discoverScroll.height}")
        animeCategoryOpen = true
        shelvesScrollY = binding.discoverScroll.scrollY
        animeCategorySlug = slug
        animeCategoryTitle = title
        animeCategoryDescription = description
        categoryArtworkGeneration++
        animeCategoryOffset = 0
        animeCategoryEndReached = false
        animeCategoryBackCallback?.isEnabled = true
        animeExhaustedAnnounced = false
        animeCategoryCards.clear()
        animeCategoryAdapter.notifyDataSetChanged()
        animeCategoryListRowAdapter.notifyDataSetChanged()
        binding.discoverCategoryGrid.visibility = View.GONE
        binding.discoverEditorialShelves.visibility = View.GONE
        binding.discoverTrailerCarousel.visibility = View.GONE
        binding.discoverResultsSection.visibility = View.GONE
        binding.discoverCategoryHeader.visibility = View.VISIBLE
        binding.discoverCategoryResultsGrid.visibility = View.VISIBLE
        binding.discoverCategoryStatus.visibility = View.GONE
        renderAnimeCategoryHeader(mediaCount = null)
        applyAnimeCategoryLayout(PrefManager.getCustomVal("discover_category_view", 0))
        binding.discoverScroll.scrollTo(0, 0)
        if (isTv) {
            binding.discoverCategoryHeader.post {
                if (_binding != null) binding.discoverCategoryHeader.getChildAt(0)?.requestFocus()
            }
        }
        
        
        
        val content = binding.discoverScroll.getChildAt(0) as? android.view.ViewGroup
        val grid = binding.discoverCategoryResultsGrid
        val scrollHost = binding.discoverScroll
        if (content != null) {
            content.requestLayout()
            content.doOnLayout {
                if (_binding == null || !animeCategoryOpen) return@doOnLayout
                var others = 0
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i)
                    if (child !== grid && child.visibility != android.view.View.GONE) others += child.height
                }
                val remaining = scrollHost.height - others
                if (remaining > dp(240)) {
                    content.layoutParams.height = scrollHost.height
                    grid.layoutParams.height = remaining
                    grid.isNestedScrollingEnabled = false
                } else {
                    content.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    grid.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    grid.isNestedScrollingEnabled = true
                }
                content.requestLayout()
                grid.requestLayout()
                scrollHost.scrollTo(0, 0)
                Logger.log(
                    "AnimeShelf pin viewport=${scrollHost.height} contentH=${content.height} " +
                        "gridH=${grid.height} others=$others remaining=$remaining " +
                        "nested=${grid.isNestedScrollingEnabled} canDown=${grid.canScrollVertically(1)} " +
                        "items=${grid.adapter?.itemCount ?: 0}"
                )
            }
        }
        loadAnimeCategoryPage()
    }

    private fun closeAnimeCategory() {
        if (!animeCategoryOpen) return
        animeCategoryOpen = false
        animeCategoryBackCallback?.isEnabled = false
        animeCategoryCards.clear()
        animeCategoryAdapter.notifyDataSetChanged()
        animeCategoryListRowAdapter.notifyDataSetChanged()
        binding.discoverCategoryHeader.visibility = View.GONE
        binding.discoverCategoryResultsGrid.visibility = View.GONE
        binding.discoverCategoryResultsGrid.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.discoverCategoryResultsGrid.isNestedScrollingEnabled = true
        binding.discoverCategoryStatus.visibility = View.GONE
        binding.discoverCategoryProgress.visibility = View.GONE
        binding.discoverCategoryGrid.visibility = View.VISIBLE
        binding.discoverEditorialShelves.visibility = View.VISIBLE
        binding.discoverResultsSection.visibility = View.VISIBLE
        binding.discoverTrailerCarousel.visibility =
            if (binding.discoverTrailerCarousel.childCount > 0) View.VISIBLE else View.GONE
        (binding.discoverScroll.getChildAt(0) as? android.view.View)?.let {
            it.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            it.requestLayout()
        }

        val target = shelvesScrollY
        binding.discoverScroll.post { if (_binding != null) binding.discoverScroll.scrollTo(0, target) }
    }

    private fun renderAnimeCategoryHeader(mediaCount: Int?) {
        val header = binding.discoverCategoryHeader
        header.removeAllViews()
        val context = header.context
        header.setPadding(dp(12), dp(12), dp(12), dp(16))
        header.addView(Chip(context).apply {
            text = "‹  All discovery shelves"
            isFocusable = true
            setOnClickListener { closeAnimeCategory() }
            FocusEffectUtil.applyFocusListener(this)
        })
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = if (mediaCount != null) "$animeCategoryTitle ($mediaCount)" else animeCategoryTitle
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val gridButton = ImageButton(context).apply {
            setImageResource(ani.sanin.R.drawable.ic_round_grid_view_24)
            setColorFilter(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            LensButtonBackground.apply(this)
            contentDescription = context.getString(ani.sanin.R.string.grid)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val listButton = ImageButton(context).apply {
            setImageResource(ani.sanin.R.drawable.ic_round_view_list_24)
            setColorFilter(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            LensButtonBackground.apply(this)
            contentDescription = context.getString(ani.sanin.R.string.list)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        titleRow.addView(gridButton)
        titleRow.addView(listButton)
        FocusEffectUtil.applyFocusListener(gridButton)
        FocusEffectUtil.applyFocusListener(listButton)
        animeCategoryGridButton = gridButton
        animeCategoryListButton = listButton
        gridButton.setOnClickListener { applyAnimeCategoryLayout(0) }
        listButton.setOnClickListener { applyAnimeCategoryLayout(1) }
        header.addView(titleRow)
        if (animeCategoryDescription.isNotBlank()) {
            header.addView(TextView(context).apply {
                text = animeCategoryDescription
                textSize = 13f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
    }

    private fun applyAnimeCategoryLayout(mode: Int) {
        val b = _binding ?: return
        PrefManager.setCustomVal("discover_category_view", mode)
        animeCategoryGridButton?.alpha = if (mode == 0) 1f else 0.33f
        animeCategoryListButton?.alpha = if (mode == 1) 1f else 0.33f
        b.discoverCategoryResultsGrid.adapter =
            if (mode == 1) animeCategoryListRowAdapter else animeCategoryAdapter
        b.discoverCategoryResultsGrid.layoutManager =
            if (mode == 1) {
                LinearLayoutManager(requireContext())
            } else {
                GridLayoutManager(requireContext(), TmdbCards.gridSpan(resources.displayMetrics.widthPixels / resources.displayMetrics.density))
            }
    }

    private fun loadAnimeCategoryPage() {
        val b = _binding ?: return
        if (animeCategoryLoading || animeCategoryEndReached) return
        animeCategoryLoading = true
        Logger.log("AnimeShelf loadPage start offset=$animeCategoryOffset cards=${animeCategoryCards.size}")
        b.discoverCategoryProgress.visibility =
            if (animeCategoryCards.isEmpty()) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            var added = false
            var statusShown = false
            var attempts = 0
            do {
                val page = withContext(Dispatchers.IO) {
                    Kitsu.categoryAnime(
                        categorySlug = animeCategorySlug,
                        offset = animeCategoryOffset,
                        limit = ANIME_CATEGORY_PAGE_SIZE,
                    )
                }
                if (page.success) animeCategoryRetries = 0
                if (!page.success) {
                    if (animeCategoryCards.isEmpty()) {
                        statusShown = true
                        showAnimeCategoryStatus("Couldn't load this category. Tap to retry.") {
                            animeCategoryEndReached = false
                            loadAnimeCategoryPage()
                        }
                    } else {
                        animeCategoryEndReached = false
                        statusShown = true
                        showAnimeCategoryStatus("Couldn't load more. Scroll down to retry.")
                        if (animeCategoryRetries < 5) {
                            animeCategoryRetries++
                            b.discoverScroll.postDelayed({
                                if (_binding != null && animeCategoryOpen &&
                                    !animeCategoryLoading && !animeCategoryEndReached
                                ) loadAnimeCategoryPage()
                            }, 5000)
                        }
                    }
                    break
                }
                animeCategoryOffset += page.rawCount
                animeCategoryEndReached = !page.hasMore
                Logger.log(
                    "AnimeShelf loadPage offset=$animeCategoryOffset raw=${page.rawCount} " +
                        "items=${page.items.size} hasMore=${page.hasMore}"
                )
                if (page.items.isNotEmpty()) {
                    val start = animeCategoryCards.size
                    animeCategoryCards.addAll(page.items.map { item ->
                        DiscoveryStandardCardAdapter.Card(
                            id = item.anilistId
                                ?: -(kotlin.math.abs(item.title.hashCode() % 1_000_000_000) + 1),
                            title = item.title,
                            posterUrl = item.posterUrl,
                            year = item.year?.toString(),
                        )
                    })
                    val count = animeCategoryCards.size - start
                    animeCategoryAdapter.notifyItemRangeInserted(start, count)
                    animeCategoryListRowAdapter.notifyItemRangeInserted(start, count)
                    added = true
                }
                attempts++
            } while (!added && !animeCategoryEndReached && attempts < 3)

            if (_binding == null) {
                animeCategoryLoading = false
                return@launch
            }
            if (animeCategoryCards.isEmpty() && animeCategoryEndReached) {
                showAnimeCategoryStatus("No mapped anime found in this category.")
            } else if (!statusShown) {
                binding.discoverCategoryStatus.visibility = View.GONE
            }
            binding.discoverCategoryProgress.visibility = View.GONE
            animeCategoryLoading = false
            if (added && animeCategoryOpen && !animeCategoryEndReached && animeCategoryCards.size < 160) {
                binding.discoverScroll.postDelayed({
                    if (_binding != null && animeCategoryOpen &&
                        !animeCategoryLoading && !animeCategoryEndReached
                    ) loadAnimeCategoryPage()
                }, 3000)
            }
        }
    }

    private fun showAnimeExhaustedSnackbar() {
        if (animeExhaustedAnnounced || _binding == null) return
        animeExhaustedAnnounced = true
        com.google.android.material.snackbar.Snackbar.make(
            binding.discoverScroll,
            "That\'s all for \'" + animeCategoryTitle + "\'",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }

    private fun animeCategoryNearBottom(): Boolean {
        val scroll = binding.discoverScroll
        val content = scroll.getChildAt(0)?.height ?: return false
        return scroll.scrollY + scroll.height >= content - dp(500)
    }

    private fun showAnimeCategoryStatus(message: String, onRetry: (() -> Unit)? = null) {
        binding.discoverCategoryStatus.text = message
        binding.discoverCategoryStatus.isFocusable = onRetry != null
        binding.discoverCategoryStatus.isClickable = onRetry != null
        binding.discoverCategoryStatus.setOnClickListener { onRetry?.invoke() }
        binding.discoverCategoryStatus.visibility = View.VISIBLE
        (binding.discoverScroll.getChildAt(0) as? android.view.View)?.let {
            it.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            it.requestLayout()
        }
    }

    private fun loadReelRail() {
        if (reelRailLoaded) {
            renderReelRail()
            return
        }
        reelRailLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    ReelFeed.buildInterleaved(ReelFeed.railPool(RAIL_ANIME_COUNT))
                }.getOrNull()
            }
            reelEntries = entries ?: emptyList()

            val seen = HashSet<String>()
            reelRailCards = reelEntries.mapIndexedNotNull { index, item ->
                if (seen.add(item.animeTitle)) ReelCard(item.animeTitle, item.key, index) else null
            }
            if (_binding != null) renderReelRail()
        }
    }

    private fun renderReelRail() {
        val host = _binding?.discoverTrailerCarousel ?: return
        host.visibility = if (animeCategoryOpen) View.GONE else View.VISIBLE
        host.removeAllViews()
        val context = host.context
        host.addView(TextView(context).apply {
            text = "TRAILERS TO DROP INTO"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(12), dp(12), dp(8))
        })
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(2), dp(8), dp(10))
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (reelRailCards.isEmpty()) {
            repeat(3) { index ->
                val placeholder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    layoutParams = LinearLayout.LayoutParams(dp(112), dp(178)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                placeholder.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
                    contentDescription = "Trailer artwork unavailable"
                })
                placeholder.addView(TextView(context).apply {
                    text = if (ani.sanin.connections.tmdb.Tmdb.apiKey.isBlank())
                        "Add TMDB API key" else "Loading reels ${index + 1}"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, dp(8), 0, 0)
                })
                row.addView(placeholder)
            }
        } else {
            reelRailCards.forEach { card ->
                val holder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(dp(116), dp(190)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { openReelPlayer(card.startIndex) }
                }
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = card.title
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(145)
                    )
                }
                thumb.loadReelThumbnail(card.key)
                holder.addView(thumb)
                holder.addView(TextView(context).apply {
                    text = card.title
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(dp(3), dp(5), dp(3), 0)
                })
                FocusEffectUtil.applyFocusListener(holder)
                row.addView(holder)
            }
        }
        scroll.addView(row)
        host.addView(scroll)
    }

    private fun openReelPlayer(startIndex: Int) {
        if (reelEntries.isEmpty() || !isAdded) return
        val intent = Intent(requireContext(), ReelShortsPlayerActivity::class.java).apply {
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_VIDEO_IDS,
                ArrayList(reelEntries.map { it.key })
            )

            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TITLES,
                ArrayList(reelEntries.map { it.videoName })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_ANIME_TITLES,
                ArrayList(reelEntries.map { it.animeTitle })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_SYNOPSIS,
                ArrayList(reelEntries.map { it.synopsis ?: "" })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_GENRES,
                ArrayList(reelEntries.map { it.genres.joinToString("|") })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_COVERS,
                ArrayList(reelEntries.map { it.cover ?: "" })
            )

            putIntegerArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_ANIME_IDS,
                ArrayList(reelEntries.map { it.anilistId })
            )
            putExtra(ReelShortsPlayerActivity.EXTRA_START_INDEX, startIndex)
            putExtra(ReelShortsPlayerActivity.EXTRA_REEL_MODE, true)
        }
        startActivity(intent)
    }

    private fun ImageView.loadReelThumbnail(key: String) {
        val view = this

        Glide.with(view)
            .load(ReelFeed.thumbnailUrl(key))
            .error(Glide.with(view).load(ReelFeed.thumbnailFallbackUrl(key)))
            .into(view)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setupSearchBar() {
        binding.discoverSearchBar.hint = getString(R.string.search)
        val openSearch = {
            val ctx = requireContext()
            if (Anilist.token != null) {
                ContextCompat.startActivity(
                    ctx,
                    Intent(ctx, SearchActivity::class.java).putExtra("type", "ANIME"),
                    null
                )
            } else {
                SearchBottomSheet.newInstance().show(
                    (requireActivity() as AppCompatActivity).supportFragmentManager,
                    "search"
                )
            }
        }
        binding.discoverSearchText.setOnClickListener { openSearch() }

        binding.discoverSearchBar.isClickable = true
        binding.discoverSearchBar.isFocusableInTouchMode = true
        binding.discoverSearchBar.setOnClickListener { openSearch() }
        FocusEffectUtil.applyFocusListener(binding.discoverSearchBar)
    }

    private fun setupAvatar() {
        val avatarUrl = ani.sanin.connections.anilist.Anilist.avatar
            ?: ani.sanin.connections.simkl.Simkl.avatar
        if (!avatarUrl.isNullOrBlank()) {
            binding.discoverAvatar.loadImage(avatarUrl)
        }
        binding.discoverAvatar.setOnClickListener {
            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(android.view.Gravity.END)) {
                    val popMethod = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                    popMethod.isAccessible = true
                    popMethod.invoke(act)
                    drawer.openDrawer(android.view.Gravity.END)
                }
            }
        }
        FocusEffectUtil.applyFocusListener(binding.discoverAvatar)
    }

    private fun setupGenreChips() {
        renderCategoryChips(emptyList())
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.categories.collect { renderCategoryChips(it) }
        }
        discoverViewModel.loadCategories()
    }

    private fun renderCategoryChips(categories: List<KitsuCategory>) {
        if (!isAdded || _binding == null) return
        val group = binding.genreChipGroup
        val textColor = requireContext().getThemeColor(
            com.google.android.material.R.attr.colorOnSurface
        )
        val selected = discoverViewModel.selectedCategory.value
        group.removeAllViews()
        addCategoryChip(group, textColor, getString(R.string.all), selected == null) {
            applyCategorySelection(null)
        }
        if (isTv) {
            if (categories.isEmpty()) return

            val browse = Chip(group.context).apply {
                text = selected?.title ?: getString(R.string.categories)
                isCheckable = true
                isChecked = selected != null
                isFocusable = true
                setTextColor(textColor)
                setOnClickListener { showCategoryPicker() }
            }
            FocusEffectUtil.applyFocusListener(browse)
            group.addView(browse)
        } else {
            categories.forEach { category ->
                addCategoryChip(group, textColor, category.title, selected?.id == category.id) {
                    applyCategorySelection(category)
                }
            }
        }
    }

    private fun addCategoryChip(
        group: ChipGroup,
        textColor: Int,
        label: String,
        checked: Boolean,
        onSelect: () -> Unit,
    ) {
        val chip = Chip(group.context).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isFocusable = true
            setTextColor(textColor)
            setOnClickListener {
                group.clearCheck()
                isChecked = true
                onSelect()
            }
        }
        FocusEffectUtil.applyFocusListener(chip)
        group.addView(chip)
    }

    private fun applyCategorySelection(category: KitsuCategory?) {
        val hadCategory = discoverViewModel.selectedCategory.value != null
        discoverViewModel.selectCategory(category)
        setSeasonRowEnabled(category == null)

        if (category != null) {
            binding.seasonChipGroup.clearCheck()
        } else if (hadCategory) {
            checkSeasonChip(discoverViewModel.selectedSeason.value)
        }

        if (isTv) {
            (binding.genreChipGroup.getChildAt(1) as? Chip)?.apply {
                text = category?.title ?: getString(R.string.categories)
                isChecked = category != null
            }
        }
    }

    private fun setSeasonRowEnabled(enabled: Boolean) {
        val group = binding.seasonChipGroup
        group.isEnabled = enabled
        group.alpha = if (enabled) 1f else 0.4f
        for (index in 0 until group.childCount) {
            group.getChildAt(index).isEnabled = enabled
        }
    }

    private fun checkSeasonChip(season: DiscoverSeason) {
        val group = binding.seasonChipGroup
        group.clearCheck()
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? Chip ?: continue
            if (chip.text == season.label) {
                chip.isChecked = true
                break
            }
        }
    }

    private fun showCategoryPicker() {
        if (!isAdded) return
        val categories = discoverViewModel.categories.value
        if (categories.isEmpty()) return
        val ctx = requireContext()
        val textColor = ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val pad = (ctx.resources.displayMetrics.density * 16).toInt()
        val group = ChipGroup(ctx).apply {
            isSingleSelection = true
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.categories)
            .setView(group)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val selected = discoverViewModel.selectedCategory.value
        addCategoryChip(group, textColor, getString(R.string.all), selected == null) {
            dialog.dismiss()
            applyCategorySelection(null)
        }
        categories.forEach { category ->
            addCategoryChip(group, textColor, category.title, selected?.id == category.id) {
                dialog.dismiss()
                applyCategorySelection(category)
            }
        }
        dialog.show()
    }

    private fun setupSeasonChips() {
        val onSurface = requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val seasons = DiscoverSeason.seasons()
        val currentSeason = discoverViewModel.selectedSeason.value
        seasons.forEach { season ->
            val chip = Chip(requireContext()).apply {
                text = season.label
                isCheckable = true
                isChecked = (season.apiValue == currentSeason.apiValue && season.year == currentSeason.year)
                isFocusable = true
                setTextColor(onSurface)
                setOnClickListener {
                    binding.seasonChipGroup.clearCheck()
                    isChecked = true
                    discoverViewModel.selectSeason(season)

                    updateCategoryRowToAll()
                }
            }
            FocusEffectUtil.applyFocusListener(chip)
            binding.seasonChipGroup.addView(chip)
        }

        setSeasonRowEnabled(discoverViewModel.selectedCategory.value == null)
    }

    private fun updateCategoryRowToAll() {
        val group = binding.genreChipGroup
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? Chip)?.isChecked = false
        }
        (group.getChildAt(0) as? Chip)?.isChecked = true
        if (isTv) {
            (group.getChildAt(1) as? Chip)?.text = getString(R.string.categories)
        }
    }

    private fun setupResultsGrid() {
        gridItems = mutableListOf()
        gridAdapter = MediaAdaptor(0, gridItems, requireActivity(), true)
        binding.discoverRecyclerView.apply {
            val dm = resources.displayMetrics
            layoutManager = GridLayoutManager(requireContext(), TmdbCards.gridSpan(dm.widthPixels / dm.density))

            adapter = gridAdapter
            isNestedScrollingEnabled = false
            isFocusable = true
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    true
                } else false
            }
        }

        binding.discoverScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val content = binding.discoverScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            val remaining = content - binding.discoverScroll.scrollY - binding.discoverScroll.height
            if (remaining <= resources.displayMetrics.heightPixels) {
                discoverViewModel.loadMore()
            }
        }
        binding.discoverLoadMoreRetry.setOnClickListener { discoverViewModel.retryLoadMore() }
        FocusEffectUtil.applyFocusListener(binding.discoverRetryChip, binding.discoverLoadMoreRetry)
        binding.discoverRetryChip.setOnClickListener { discoverViewModel.retry() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.uiState.collect { render(it) }
        }
    }

    private fun render(state: DiscoverUiState) {
        val newQuery = state.queryId != renderedQueryId
        if (newQuery) renderedQueryId = state.queryId

        if (newQuery) {
            gridItems.clear()
            gridItems.addAll(state.items)
            gridAdapter.notifyDataSetChanged()
            binding.discoverRecyclerView.scrollToPosition(0)
            binding.discoverScroll.scrollTo(0, 0)
        } else if (state.items.size > gridItems.size) {
            val start = gridItems.size
            gridItems.addAll(state.items.subList(start, state.items.size))
            gridAdapter.notifyItemRangeInserted(start, gridItems.size - start)
        }

        renderEditorialShelves(state.items)

        val hasItems = state.items.isNotEmpty()
        binding.discoverProgressBar.isVisible = state.isInitialLoading && !hasItems
        binding.discoverRecyclerView.isVisible = hasItems
        binding.discoverEmptyText.isVisible = !state.isInitialLoading && !state.error && !hasItems
        binding.discoverErrorGroup.isVisible = state.error && !hasItems

        binding.discoverLoadingMore.isVisible = hasItems && state.isLoadingMore
        binding.discoverLoadMoreErrorGroup.isVisible = hasItems && state.loadMoreError
    }

    private fun renderEditorialShelves(media: List<Media>) {
        fun searchable(item: Media): String = buildString {
            append(item.userPreferredName).append(' ')
            append(item.nameRomaji).append(' ')
            append(item.description.orEmpty()).append(' ')
            append(item.genres.joinToString(" ")).append(' ')
            append(item.tags.joinToString(" "))
        }.lowercase()

        fun shelf(
            title: String,
            note: String,
            predicate: (Media, String) -> Boolean
        ): EditorialShelfData {
            val cards = media.asSequence()
                .map { it to searchable(it) }
                .filter { (item, text) -> predicate(item, text) }
                .distinctBy { it.first.id }
                .take(10)
                .map { (item, _) ->
                    EditorialCardData(
                        title = item.userPreferredName,
                        meta = listOfNotNull(
                            item.startDate?.year?.takeIf { it > 0 }?.toString(),
                            item.meanScore?.takeIf { it > 0 }?.let { "★ $it" }
                        ).joinToString(" · "),
                        imageUrl = item.cover,
                        externalUrl = item.idIMDB?.takeIf { it.startsWith("tt") }
                            ?.let { "https://www.imdb.com/title/$it/" }
                    )
                }.toList()
            return EditorialShelfData(title, note, cards, placeholderCount = 0)
        }

        val shelves = listOf(
            shelf("THE SLOW BURN CLUB", "The tension is the point. The payoff is earned.") { item, text ->
                ("romance" in item.genres.map { it.lowercase() } || "drama" in item.genres.map { it.lowercase() } ||
                    "slice of life" in item.genres.map { it.lowercase() }) &&
                    listOf("slow burn", "gradually", "slowly develops", "will they", "relationship develops",
                        "longing", "romantic tension", "unspoken feelings", "friends to lovers").any(text::contains)
            },
            shelf("FIVE STEPS AHEAD", "Master plans, mind games and people reading the room.") { item, text ->
                listOf("mastermind", "strateg", "manipulat", "mind game", "psychological", "deduction",
                    "outsmart", "schem", "detective", "tactical genius").any(text::contains)
            },
            shelf("THE VILLAIN HAS A POINT", "Messy motives. Questionable methods. A real argument.") { item, text ->
                listOf("anti-hero", "antihero", "morally grey", "morally gray", "revenge", "villain protagonist",
                    "tragic antagonist", "corruption", "justice", "vengeance").any(text::contains)
            },
            shelf("BEAUTIFULLY UNWELL", "Unstable minds, complicated feelings and emotional wreckage.") { item, text ->
                ("psychological" in item.genres.map { it.lowercase() } ||
                    "drama" in item.genres.map { it.lowercase() } ||
                    "thriller" in item.genres.map { it.lowercase() }) &&
                    listOf("obsession", "trauma", "mental", "descent", "unravel", "loneliness",
                        "grief", "identity", "paranoia", "inner turmoil").any(text::contains)
            },
            shelf("ONE MORE EPISODE", "The dangerously easy-to-binge queue.") { item, text ->
                item.popularity != null && item.popularity!! > 0 &&
                    (item.status == "RELEASING" || item.meanScore?.let { it >= 75 } == true)
            },
            EditorialShelfData(
                "TMDB ANIMATION RADAR", "Animation from TMDB adds another discovery lens; this shelf can include non-Japanese animation too.",
                tmdbAnimationPicks.map { item ->
                    EditorialCardData(
                        title = item.displayTitle,
                        meta = listOfNotNull(
                            item.year.takeIf { it.isNotBlank() },
                            item.type.uppercase(),
                            item.voteAverage.takeIf { it > 0.0 }?.let { "TMDB ★ ${"%.1f".format(java.util.Locale.US, it)}" }
                        ).joinToString(" · "),
                        imageUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                        externalUrl = "https://www.themoviedb.org/${item.type}/${item.id}"
                    )
                },
                placeholderCount = 0
            ),
            EditorialShelfData(
                "KITSU AFTER HOURS", "A second anime catalogue, ranked by Kitsu's own community activity.",
                kitsuPicks.map { pick ->
                    EditorialCardData(
                        title = pick.title,
                        meta = listOfNotNull(
                            pick.year?.toString(),
                            pick.score?.takeIf { it > 0 }?.let { "Kitsu ★ ${"%.1f".format(java.util.Locale.US, it)}" },
                            pick.userCount?.takeIf { it > 0 }?.let { "${it} members" }
                        ).joinToString(" · "),
                        imageUrl = pick.posterUrl,
                        externalUrl = "https://kitsu.io/anime/${pick.id}"
                    )
                },
                placeholderCount = 0
            ),
        )
        EditorialShelfRenderer.render(requireContext(), binding.discoverEditorialShelves, shelves)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        animeCategoryBackCallback?.isEnabled = false
        animeCategoryBackCallback = null
        renderedQueryId = -1
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        _binding?.discoverRecyclerView?.let { TmdbCards.applyGridSpan(it) }
        _binding?.discoverCategoryResultsGrid?.let { TmdbCards.applyGridSpan(it) }
        _binding?.discoverCategoryGrid?.post {
            if (_binding != null) {
                renderCategoryGrid()
                loadVisibleCategoryArtwork()
            }
        }
    }
}
