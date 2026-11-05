package ani.sanin.home

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.AnilistAnimeViewModel
import ani.sanin.connections.anilist.getUserId
import ani.sanin.databinding.FragmentAnimeBinding
import ani.sanin.databinding.ItemAnimePageBinding
import ani.sanin.connections.anilist.AniMangaSearchResults
import ani.sanin.connections.kitsu.Kitsu
import androidx.core.app.ActivityOptionsCompat
import ani.sanin.connections.anilist.AnilistFranchiseRanks
import ani.sanin.media.Franchise
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseActivity
import ani.sanin.media.FranchiseAdaptor
import ani.sanin.media.FranchiseListPrefs
import ani.sanin.media.FranchiseSort
import ani.sanin.media.showFranchiseSortDialog
import ani.sanin.media.read
import ani.sanin.media.FranchiseSorter
import ani.sanin.media.FranchiseStub
import ani.sanin.media.onNearEnd
import ani.sanin.ui.InlineSearchBar
import ani.sanin.setSafeOnClickListener
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.ProgressAdapter
import ani.sanin.media.SearchActivity
import ani.sanin.navBarHeight
import ani.sanin.px
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.statusBarHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class AnimeFragment : Fragment() {
    private var _binding: FragmentAnimeBinding? = null
    private val binding get() = _binding!!
    private lateinit var animePageAdapter: AnimePageAdapter

    private var animeFranchiseAdapter: FranchiseAdaptor? = null

    private var animeFranchiseSortWired = false

    private var animeFranchiseSearch: InlineSearchBar? = null

    private var animeFranchiseResolving = false

    private var animePageBinding: ItemAnimePageBinding? = null

    private var animeFranchiseSortGeneration = 0

    private var animeFranchiseCursor = 0

    private fun publishFranchises(adaptor: FranchiseAdaptor, trigger: String) {

        val snapshot = model.aniMangaSearchResults.results.toList()
        Logger.log(
            "Franchise(anime) publish on $trigger: cursor=$animeFranchiseCursor of " +
                "${snapshot.size} loaded results"
        )
        if (snapshot.isEmpty()) {
            adaptor.submit(emptyList())
            updateAnimeFranchiseState()
            Logger.log("Franchise(anime) publish: no results at all, submitted empty row")
            return
        }

        if (snapshot.size < animeFranchiseCursor) {
            Logger.log(
                "Franchise(anime) publish: results at ${snapshot.size} but cursor is at " +
                    "$animeFranchiseCursor, so this is a new list; restarting from the top"
            )
            animeFranchiseCursor = 0
            adaptor.clear()

            animeFranchiseSearch?.clearQuery()
        }
        if (animeFranchiseCursor >= snapshot.size) {
            Logger.log("Franchise(anime) publish: cursor $animeFranchiseCursor already covers ${snapshot.size}, nothing to do")
            return
        }

        val end = min(animeFranchiseCursor + SEED_BATCH, snapshot.size)
        val window = (animeFranchiseCursor until end).mapNotNull { snapshot.getOrNull(it) }
        animeFranchiseCursor = end

        val seeds = window.filterNot { adaptor.hasCardFor(it.id) || adaptor.isPending(it.id) }
        if (seeds.isEmpty()) {
            Logger.log("Franchise(anime) publish: ${window.size} in window, all already done or pending")
            return
        }

        val claimed = adaptor.markPending(seeds.map { it.id })
            .mapNotNull { id -> seeds.firstOrNull { it.id == id } }
        if (claimed.isEmpty()) {
            Logger.log("Franchise(anime) publish: ${seeds.size} seeds, none could be claimed")
            return
        }
        Logger.log("Franchise(anime) publish: resolving ${claimed.size} seeds ${claimed.map { it.id }}")
        animeFranchiseResolving = true
        updateAnimeFranchiseState()

        val stubs = claimed.mapNotNull { seed ->
            FranchiseStub.fromMedia(seed)?.let { card -> seed.id to card }
        }
        val stubByName = stubs.associate { (_, card) -> card.name to card }
        val stubNames = stubs.associate { (id, card) -> id to card.name }
        if (stubNames.isNotEmpty()) {

            adaptor.upsert(stubByName)
            updateAnimeFranchiseState()
            Logger.log("Franchise(anime) published ${stubNames.size} placeholder cards immediately")
        }

        lifecycleScope.launch {
            var resolvedCount = 0
            claimed.chunked(KITSU_CONCURRENCY * 2).forEach { chunk ->

                val gate = Semaphore(KITSU_CONCURRENCY)
                val pending = chunk.map { seed ->

                    async(Dispatchers.IO) { gate.withPermit { Kitsu.franchiseCard(seed.id) } }
                }

                pending.forEachIndexed { index, job ->
                    val card = job.await() ?: return@forEachIndexed
                    resolvedCount++

                    val provisional = stubNames[chunk[index].id] ?: card.name
                    adaptor.upsert(mapOf(provisional to card))
                }
                Logger.log(
                    "Franchise(anime) resolved $resolvedCount/${claimed.size}, " +
                        "row now ${adaptor.currentCards().size} cards"
                )
            }
            adaptor.markDone(claimed.map { it.id })
            animeFranchiseResolving = false
            updateAnimeFranchiseState()
            Logger.log("Franchise(anime) built row of ${adaptor.currentCards().size} cards from ${claimed.size} seeds")

            sortAnimeFranchises()
        }
    }

    private fun animeFranchisePrefs() = FranchiseListPrefs.read()

    private fun applyAnimeFranchiseSort(ranks: AnilistFranchiseRanks.Ranks? = null) {
        val adaptor = animeFranchiseAdapter ?: return
        val prefs = animeFranchisePrefs()
        val ranked = when {
            ranks == null -> adaptor.currentCards()
            prefs.sort == FranchiseSort.POPULAR ->
                AnilistFranchiseRanks.applyPopular(adaptor.currentCards(), ranks)
            else -> AnilistFranchiseRanks.applyTrending(adaptor.currentCards(), ranks)
        }
        val shuffled = if (prefs.sort == FranchiseSort.RANDOM) ranked.shuffled() else ranked
        val sorted = FranchiseSorter.apply(ranked, prefs, shuffled)

        Logger.log(
            "Franchise(anime) sort=${prefs.sort} dir=${prefs.direction} " +
                "showSingle=${prefs.showSingleEntry} ranked=${ranks != null}: ${ranked.size} in, " +
                "${sorted.size} out, first=${sorted.take(5).map { it.name }}"
        )
        adaptor.submit(sorted)
    }

    private fun sortAnimeFranchises() {
        val wanted = animeFranchisePrefs().sort
        val needsRanks = wanted == FranchiseSort.TRENDING || wanted == FranchiseSort.POPULAR
        if (!needsRanks) return applyAnimeFranchiseSort()

        val generation = ++animeFranchiseSortGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            val ranks = when (wanted) {
                FranchiseSort.TRENDING -> AnilistFranchiseRanks.trending()
                FranchiseSort.POPULAR -> AnilistFranchiseRanks.popular()
                else -> return@launch
            }

            if (generation != animeFranchiseSortGeneration) return@launch
            if (animeFranchisePrefs().sort != wanted) return@launch
            applyAnimeFranchiseSort(ranks)
        }
    }

    private fun setupAnimeFranchiseSort() {

        animeFranchiseAdapter?.let { setupAnimeFranchiseSearch(it) }
        if (animeFranchiseSortWired) return

        val adaptor = animeFranchiseAdapter ?: return
        animeFranchiseSortWired = true
        val pill = animePageAdapter.binding.animeFranchiseSortPill
        val direction = animePageAdapter.binding.animeFranchiseSortDirection

        fun updatePill() {
            pill.setText(animeFranchisePrefs().sort.labelRes())
        }

        pill.setSafeOnClickListener {
            showFranchiseSortDialog {
                updatePill()
                sortAnimeFranchises()
            }
        }

        direction.setSafeOnClickListener {
            val current = animeFranchisePrefs()
            if (current.sort == FranchiseSort.RANDOM) {

                snackString(R.string.franchise_randomized, requireActivity())
            } else {
                val next = current.direction.inverted()
                PrefManager.setVal(PrefName.FranchiseSortDirectionPref, next.name)
                snackString(next.labelRes(), requireActivity())
            }
            sortAnimeFranchises()
        }

        updatePill()
    }

    private fun setupAnimeFranchiseSearch(adaptor: FranchiseAdaptor) {
        val binding = animePageAdapter.binding

        if (animeFranchiseSearch?.button === binding.animeFranchiseSearch) return
        animeFranchiseSearch?.release()
        animeFranchiseSearch = InlineSearchBar(
            button = binding.animeFranchiseSearch,
            bar = binding.animeFranchiseSearchBar,
            field = binding.animeFranchiseSearchText,
            onQueryChanged = { query -> adaptor.setQuery(query) },
            onQuerySettled = { updateAnimeFranchiseState() },
            onOpened = { scrollAnimeFranchiseHeaderIntoView() },
        )
        updateAnimeFranchiseState()
    }

    private fun updateAnimeFranchiseState() {
        val adaptor = animeFranchiseAdapter ?: return
        val binding = animePageBinding ?: return
        val cards = adaptor.currentCards()
        val loading = cards.isEmpty() && animeFranchiseStillLoading()
        binding.animeFranchiseProgress.isVisible = loading
        val message = when {
            adaptor.isSearchMissed() -> R.string.franchise_search_empty
            cards.isEmpty() && !loading -> R.string.franchise_none
            else -> null
        }
        binding.animeFranchiseMessage.isVisible = message != null
        if (message != null) binding.animeFranchiseMessage.setText(message)
    }

    private fun animeFranchiseStillLoading(): Boolean =
        animeFranchiseResolving ||
            model.aniMangaSearchResults.results.isEmpty() ||
            model.aniMangaSearchResults.hasNextPage

    private fun scrollAnimeFranchiseHeaderIntoView() {
        val rv = binding.animePageRecyclerView
        val header = animePageBinding?.animeFranchiseHeader ?: return
        val rvTop = IntArray(2)
        val headerTop = IntArray(2)
        rv.getLocationInWindow(rvTop)
        header.getLocationInWindow(headerTop)
        val delta = headerTop[1] - rvTop[1]
        if (delta > 0) rv.smoothScrollBy(0, -delta)
    }

    private fun openFranchise(card: Franchise, view: View) {
        view.transitionName = FRANCHISE_TRANSITION
        startActivity(
            FranchiseActivity.intent(requireContext(), card),
            ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(),
                view,
                FRANCHISE_TRANSITION,
            ).toBundle()
        )
    }

    private companion object {

        const val FRANCHISE_TRANSITION = "franchiseCard"

        const val KITSU_CONCURRENCY = 6

        const val SEED_BATCH = 29
    }

    val model: AnilistAnimeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onPause() {
        super.onPause()
        animePageAdapter.pauseTrendingAutoScroll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        animeFranchiseSortWired = false
        animeFranchiseAdapter = null
        animePageBinding = null
        animeFranchiseSearch?.release()
        animeFranchiseSearch = null
        animeFranchiseSortGeneration++

        animeFranchiseCursor = 0
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scope = viewLifecycleOwner.lifecycleScope

        var height = statusBarHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = activity?.window?.decorView?.rootWindowInsets?.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    height = max(
                        statusBarHeight,
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height()
                        )
                    )
                }
            }
        }
        binding.animeRefresh.setSlingshotDistance(height + 128)
        binding.animeRefresh.setProgressViewEndTarget(false, height + 128)
        binding.animeRefresh.setOnRefreshListener {
            Refresh.activity[this.hashCode()]!!.postValue(true)
        }

        binding.animePageRecyclerView.updatePaddingRelative(bottom = navBarHeight + 160f.px)

        animePageAdapter = AnimePageAdapter()

        var loading = true
        if (model.notSet) {
            model.notSet = false
            model.aniMangaSearchResults = AniMangaSearchResults(
                "ANIME",
                isAdult = false,
                onList = false,
                results = mutableListOf(),
                hasNextPage = true,
                sort = Anilist.sortBy[1]
            )
        }

        val franchiseAdaptor = FranchiseAdaptor { card, view -> openFranchise(card, view) }
        animeFranchiseAdapter = franchiseAdaptor
        val progressAdaptor = ProgressAdapter(searched = model.searched)
        val adapter = ConcatAdapter(animePageAdapter, franchiseAdaptor, progressAdaptor)
        binding.animePageRecyclerView.adapter = adapter
        val layout = LinearLayoutManager(requireContext())
        binding.animePageRecyclerView.layoutManager = layout

        binding.animePageRecyclerView.onNearEnd {
            publishFranchises(franchiseAdaptor, trigger = "scroll")
        }

        var visible = false

        val hideScrollTop = PrefManager.getVal<Boolean>(PrefName.HideScrollTopButtons)
        if (hideScrollTop) binding.animePageScrollTop.visibility = View.GONE
        fun animate() {
            val start = if (visible) 0f else 1f
            val end = if (!visible) 0f else 1f
            ObjectAnimator.ofFloat(binding.animePageScrollTop, "scaleX", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
            ObjectAnimator.ofFloat(binding.animePageScrollTop, "scaleY", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
        }

        binding.animePageScrollTop.setOnClickListener {
            binding.animePageRecyclerView.scrollToPosition(4)
            binding.animePageRecyclerView.smoothScrollToPosition(0)
        }

        model.getPopular().observe(viewLifecycleOwner) {
            if (it != null) {
                val page = model.aniMangaSearchResults.results.size
                model.aniMangaSearchResults.results.addAll(it.results)

                publishFranchises(franchiseAdaptor, trigger = "page $page")
                model.aniMangaSearchResults.onList = it.onList
                model.aniMangaSearchResults.hasNextPage = it.hasNextPage
                model.aniMangaSearchResults.page = it.page

                updateAnimeFranchiseState()
                if (it.hasNextPage)
                    progressAdaptor.bar?.visibility = View.VISIBLE
                else {
                    if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                        snackString(getString(R.string.jobless_message))
                    }
                    progressAdaptor.bar?.visibility = View.GONE
                }
                loading = false
            }
        }

        binding.animePageRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (!v.canScrollVertically(1)) {
                    if (model.aniMangaSearchResults.hasNextPage && model.aniMangaSearchResults.results.isNotEmpty() && !loading) {
                        loading = true
                        scope.launch(Dispatchers.IO) {
                            model.loadNextPage(model.aniMangaSearchResults)
                        }
                    }
                }
                if (!hideScrollTop && layout.findFirstVisibleItemPosition() > 1 && !visible) {
                    binding.animePageScrollTop.visibility = View.VISIBLE
                    visible = true
                    animate()
                }

                if (!v.canScrollVertically(-1)) {
                    visible = false
                    animate()
                    scope.launch {
                        delay(300)
                        binding.animePageScrollTop.visibility = View.GONE
                    }
                }

                super.onScrolled(v, dx, dy)
            }
        })

        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && _binding != null) {
                val rv = binding.animePageRecyclerView
                if (rv.canScrollVertically(-1)) {
                    var v: View? = newFocus
                    while (v != null && v != rv) {
                        if (v.id == R.id.trendingViewPager || v.id == R.id.trendingContainer || v.id == R.id.animeSeasons) {
                            rv.post { rv.smoothScrollToPosition(0) }
                            break
                        }
                        v = v.parent as? View
                    }
                }
            }
        }
        animePageAdapter.ready.observe(viewLifecycleOwner) { i ->
            if (i) {

                animePageBinding = animePageAdapter.binding
                setupAnimeFranchiseSort()
                model.getUpdated().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateRecent(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getMovies().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateMovies(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getTopRated().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateTopRated(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getMostFav().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateMostFav(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                animePageAdapter.updateHeight()
                model.getTrending().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateTrending(it)
                    }
                }
                binding.animePageScrollTop.translationY = -(navBarHeight).toFloat()
            }
        }

        fun load() = scope.launch(Dispatchers.Main) {
        }

        animePageAdapter.onSeasonClick = { i ->
            scope.launch(Dispatchers.IO) {
                model.loadTrending(i)
            }
        }

        animePageAdapter.onSeasonLongClick = { i ->
            val (season, year) = Anilist.currentSeasons[i]
            ContextCompat.startActivity(
                requireContext(),
                Intent(requireContext(), SearchActivity::class.java)
                    .putExtra("type", "ANIME")
                    .putExtra("season", season)
                    .putExtra("seasonYear", year.toString())
                    .putExtra("search", true),
                null
            )
            true
        }

        var running = false
        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(false) }
        live.observe(viewLifecycleOwner) {
            if (it && !running) {
                running = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                        if (rescueMode) {
                            withContext(Dispatchers.Main) { load() }
                        } else {
                            Anilist.userid =
                                PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
                                    ?.toIntOrNull()
                            if (Anilist.userid == null) {
                                getUserId(requireContext()) {
                                    load()
                                }
                            } else {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getUserId(requireContext()) {
                                        load()
                                    }
                                }
                            }
                        }
                    }
                    val loadTrending = async(Dispatchers.IO) { model.loadTrending(1) }
                    val loadAll = async(Dispatchers.IO) { model.loadAll() }
                    val loadPopular = async(Dispatchers.IO) {
                        model.loadPopular(
                            "ANIME",
                            sort = Anilist.sortBy[1],
                            onList = PrefManager.getVal(PrefName.PopularAnimeList)
                        )
                    }
                    loadTrending.await()
                    loadAll.await()
                    loadPopular.await()
                    model.loaded = true
                    live.postValue(false)
                    _binding?.animeRefresh?.isRefreshing = false
                    running = false
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::animePageAdapter.isInitialized) {
            animePageAdapter.resizeBanner()
        }
    }

    override fun onResume() {
        if (!model.loaded) Refresh.activity[this.hashCode()]!!.postValue(true)
        if (this::animePageAdapter.isInitialized && _binding != null) {

            animePageAdapter.refreshBannerTypeIfChanged()
            binding.root.requestApplyInsets()
            binding.root.requestLayout()
        }
        super.onResume()
    }
}