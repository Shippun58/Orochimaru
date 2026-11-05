package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import ani.sanin.bannerCardSizePx
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsRuntime
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbWatchActivity
import ani.sanin.Refresh
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.FragmentTmdbHomeBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaListViewActivity
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.sizeBannerCard
import ani.sanin.px
import ani.sanin.util.FocusEffectUtil
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.sanin.limitedAsyncMap

class TmdbHomeFragment : Fragment() {

    sealed class BannerItem {
        data class Tmdb(val media: TmdbMedia) : BannerItem()
        data class Plugin(
            val response: SearchResponse,
            val sourceId: String,
            val backdropUrl: String? = null,
            val tmdbId: Int? = null,
            val tmdbType: String? = null
        ) : BannerItem()

        val title: String get() = when (this) {
            is Tmdb -> media.displayTitle
            is Plugin -> response.name
        }
        val posterUrl: String? get() = when (this) {
            is Tmdb -> media.posterPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
            is Plugin -> response.posterUrl
        }
        val bannerUrl: String? get() = when (this) {
            is Tmdb -> media.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
            is Plugin -> backdropUrl ?: response.posterUrl
        }
        val year: String get() = when (this) {
            is Tmdb -> media.year
            is Plugin -> ""
        }
        val type: String get() = when (this) {
            is Tmdb -> media.type
            is Plugin -> ""
        }
        val overview: String? get() = when (this) {
            is Tmdb -> media.overview
            is Plugin -> null
        }
    }

    private var _binding: FragmentTmdbHomeBinding? = null
    private val binding get() = _binding!!
    private val bannerItems = mutableListOf<BannerItem>()
    private var bannerIndex = 0
    private val bannerHandler = Handler(Looper.getMainLooper())
    private var genreNames: Map<Int, String> = emptyMap()
    private var logoJob: Job? = null
    private var loadJob: Job? = null
    private var bannerCarouselAdapter: TmdbBannerCarouselAdapter? = null
    private val bannerSnapHelper = PagerSnapHelper()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tmdbBannerFrame.setOnClickListener {
            val item = bannerItems.getOrNull(bannerIndex) ?: return@setOnClickListener
            when (item) {
                is BannerItem.Tmdb -> openDetails(item.media.type, item.media.id)
                is BannerItem.Plugin -> {
                    if (item.tmdbId != null && item.tmdbType != null) {
                        openDetails(item.tmdbType, item.tmdbId)
                    } else {
                        startActivity(
                            Intent(requireContext(), TmdbDetailsActivity::class.java)
                                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, item.sourceId)
                                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.response.url)
                        )
                    }
                }
            }
        }
        applyBannerLayout()
        setupWatchNowBtn()
        load()
        applyTmdbBannerFocusChain()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            applyBannerLayout()
            applyTmdbBannerFocusChain()

            bannerCarouselAdapter?.notifyDataSetChanged()
        }
    }

    private fun activeWatchBtn(): View? {
        val b = _binding ?: return null
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) b.tmdbBannerSideWatchBtn else b.tmdbBannerWatchBtn
    }

    private fun applyTmdbBannerFocusChain() {
        val b = _binding ?: return
        val mode = PrefManager.getVal<Int>(PrefName.HomeBannerMode)
        val bannerShown = mode == 0 || mode == 2
        val watchBtn = activeWatchBtn() ?: return
        val cal = activity?.findViewById<View>(R.id.mainCalendarContainer)
        val avatar = activity?.findViewById<View>(R.id.mainUserAvatarContainer)
        val carouselMode = mode == 0

        if (bannerShown) {

            b.tmdbBannerFrame.isFocusable = !carouselMode
            b.tmdbBannerFrame.isClickable = !carouselMode
            b.tmdbBannerFrame.nextFocusUpId = R.id.mainCalendarContainer
            watchBtn.isVisible = true
            watchBtn.isFocusable = true
            watchBtn.nextFocusUpId = R.id.mainCalendarContainer
            cal?.nextFocusDownId = watchBtn.id
            avatar?.nextFocusDownId = watchBtn.id
            watchBtn.nextFocusDownId = View.NO_ID
        } else {
            b.tmdbBannerFrame.nextFocusUpId = View.NO_ID
            watchBtn.nextFocusUpId = R.id.mainCalendarContainer
            cal?.nextFocusDownId = R.id.mainCalendarContainer
            avatar?.nextFocusDownId = R.id.mainUserAvatarContainer
            watchBtn.nextFocusDownId = View.NO_ID
        }

        binding.tmdbHomeSections.post {
            if (_binding == null) return@post
            val activeBtn = if (bannerShown) activeWatchBtn() else null
            val carousel = if (mode == 0) b.tmdbBannerCarousel else null
            var first = true
            for (i in 0 until b.tmdbHomeSections.childCount) {
                val child = b.tmdbHomeSections.getChildAt(i)
                if (child !is RecyclerView) continue
                for (j in 0 until child.childCount) {
                    val card = child.getChildAt(j)
                    val target = card.findViewById<View>(R.id.tmdbCardPoster) ?: card
                    if (first) {
                        if (activeBtn != null) {
                            activeBtn.nextFocusDownId = target.id
                            target.nextFocusUpId = activeBtn.id
                        } else {
                            target.nextFocusUpId = R.id.mainCalendarContainer
                        }

                        if (carousel != null) {
                            carousel.nextFocusDownId = target.id
                        }
                        first = false
                    }
                }
            }
        }
    }

    private fun setupWatchNowBtn() {
        val b = _binding ?: return
        val isCarouselMode = PrefManager.getVal<Int>(PrefName.HomeBannerMode) == 0
        for (btn in listOf(b.tmdbBannerWatchBtn, b.tmdbBannerSideWatchBtn)) {
            btn.setOnClickListener { openCurrentBanner() }
            btn.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        openCurrentBanner()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isCarouselMode) {
                            moveBanner(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                            true
                        } else false
                    }
                    else -> false
                }
            }
            FocusEffectUtil.applyFocusListener(btn)
        }
    }

    private fun openCurrentBanner() {
        val item = bannerItems.getOrNull(bannerIndex) ?: return
        when (item) {
            is BannerItem.Tmdb -> {
                startActivity(
                    Intent(requireContext(), TmdbWatchActivity::class.java)
                        .putExtra(TmdbWatchActivity.ARG_MEDIA_TYPE, item.media.type)
                        .putExtra(TmdbWatchActivity.ARG_MEDIA_ID, item.media.id)
                )
            }
            is BannerItem.Plugin -> {
                if (item.tmdbId != null && item.tmdbType != null) {
                    openDetails(item.tmdbType, item.tmdbId)
                } else {
                    startActivity(
                        Intent(requireContext(), TmdbDetailsActivity::class.java)
                            .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, item.sourceId)
                            .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.response.url)
                    )
                }
            }
        }
    }

    private fun moveBanner(forward: Boolean) {
        if (isCarouselMode()) {
            scrollBannerCarousel(forward, force = true)
            return
        }
        if (bannerItems.size < 2) return
        val next = (bannerIndex + (if (forward) 1 else -1) + bannerItems.size) % bannerItems.size
        showBanner(next)
    }

    override fun onResume() {
        super.onResume()
        startAutoAdvance()

        val live = Refresh.activity.getOrPut(requireActivity().hashCode()) { androidx.lifecycle.MutableLiveData(true) }
        live.observe(viewLifecycleOwner) { if (it == true) { load(); live.postValue(false) } }
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerHandler.removeCallbacksAndMessages(null)
        bannerSnapHelper.attachToRecyclerView(null)
        bannerCarouselAdapter = null
        logoJob?.cancel()
        _binding = null
    }

    private fun load() {
        val sourceId = PrefManager.getVal<String>(PrefName.ContentSource)
        val plugin = if (sourceId == "simkl" || sourceId == "tmdb") null
            else CsRepos.installed(requireContext()).firstOrNull { it.id == sourceId }
        loadJob?.cancel()
        binding.tmdbHomeSections.removeAllViews()
        binding.tmdbHomeSpinner.isVisible = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {

            loadBanner(plugin)

            val hasPluginSections = if (plugin != null) {
                loadPluginSections(plugin)
            } else false

            loadSimklContinueWatching()
            if (!hasPluginSections) {
                loadSimklHomeSections()
            }
            binding.tmdbHomeSpinner.isVisible = false
        }
    }

    private suspend fun loadBanner(plugin: CsInstalledSource? = null) {

        if (plugin != null) {
            genreNames = withContext(Dispatchers.IO) { Tmdb.genres().associate { it.id to it.name } }
        }
        if (_binding == null) return
        bannerItems.clear()
        if (plugin != null) {
            val apis = withContext(Dispatchers.IO) {
                CsRuntime.apisFor(requireContext(), plugin)
            }
            for (api in apis) {

                val pages = api.mainPage.filter { it.data.isNotBlank() }
                for (page in pages) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        list.list.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                    if (bannerItems.isNotEmpty()) break
                }

                if (bannerItems.isEmpty()) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest("Home", "", false))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        list.list.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                }

                if (bannerItems.isEmpty()) {
                    val quick = runCatching {
                        withContext(Dispatchers.IO) { api.quickSearch("") }
                    }.getOrNull()
                    if (!quick.isNullOrEmpty()) {
                        quick.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                }
                if (bannerItems.isNotEmpty()) break
            }

            val itemsToLookup = bannerItems.filterIsInstance<BannerItem.Plugin>().take(10)
            bannerItems.clear()
            for (pluginItem in itemsToLookup) {
                val tmdbResults = withContext(Dispatchers.IO) {
                    runCatching { Tmdb.search(pluginItem.response.name) }.getOrNull()
                }
                val match = tmdbResults?.firstOrNull()
                if (match != null) {
                    val backdrop = match.backdropPath?.let { Tmdb.imageUrl(it, 780) }
                    bannerItems.add(pluginItem.copy(
                        backdropUrl = backdrop,
                        tmdbId = match.id,
                        tmdbType = match.type
                    ))
                } else {
                    bannerItems.add(pluginItem)
                }
            }
        } else {

            val (g, trendingSeries, trendingMovies) = coroutineScope {
                val gd = async(Dispatchers.IO) { Tmdb.genres() }
                val sd = async(Dispatchers.IO) { Tmdb.trending("tv", "week") }
                val md = async(Dispatchers.IO) { Tmdb.trending("movie", "week") }
                Triple(gd.await(), sd.await(), md.await())
            }
            genreNames = g.associate { it.id to it.name }
            if (_binding == null) return
            bannerItems.addAll(trendingSeries.map { BannerItem.Tmdb(it) })
            bannerItems.addAll(trendingMovies.map { BannerItem.Tmdb(it) })
        }
        if (_binding == null) return
        val mode = PrefManager.getVal<Int>(PrefName.HomeBannerMode)
        if (mode == 1 || mode == 3) {

            binding.tmdbBannerFrame.visibility = View.GONE
            binding.tmdbBannerCarousel.isVisible = false
            binding.tmdbBannerDots.visibility = View.GONE
            return
        }
        binding.tmdbBannerFrame.visibility = View.VISIBLE
        if (bannerItems.isNotEmpty()) {
            val isCarouselMode = mode == 0
            if (isCarouselMode) setupTmdbBannerCarousel() else {
                binding.tmdbBannerImage.isVisible = true
                binding.tmdbBannerCarousel.isVisible = false
                binding.tmdbBannerDots.visibility = View.GONE
                showBanner(0)
            }
        }
    }

    private fun isCarouselMode(): Boolean =
        PrefManager.getVal<Int>(PrefName.HomeBannerMode) == 0

    private fun setupTmdbBannerCarousel() {
        val rv = binding.tmdbBannerCarousel
        rv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        rv.itemAnimator = null
        bannerSnapHelper.attachToRecyclerView(rv)

        val logos = mutableMapOf<Int, String?>()
        val statuses = mutableMapOf<Int, String?>()
        val scores = mutableMapOf<Int, String?>()
        val adapter = TmdbBannerCarouselAdapter(
            bannerItems.toList(),
            { item -> openBannerItem(item) },
            genreNames,
            logos,
            statuses,
            scores,
            modernMode = isModernBanner()
        )
        bannerCarouselAdapter = adapter
        rv.adapter = adapter
        binding.tmdbBannerSide.isVisible = false
        binding.tmdbBannerContent.isVisible = false
        binding.tmdbBannerImage.isVisible = false
        binding.tmdbBannerCarousel.isVisible = true
        val start = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % bannerItems.size)
        rv.scrollToPosition(start)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    startAutoAdvance()
                    return
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val pos = lm.findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION && bannerItems.isNotEmpty()) {
                        bannerIndex = pos % bannerItems.size
                        val isLandscape = resources.configuration.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE
                        if (isLandscape) showBanner(bannerIndex)
                    }
                    updateDots()
                    startAutoAdvance()
                }
            }
        })
        applyBannerLayout()
        setupBannerDots(rv, bannerItems.size)
        updateDots()
        startAutoAdvance()
        applyTmdbBannerFocusChain()

        val posters = mutableMapOf<Int, String?>()
        val details = mutableMapOf<Int, TmdbDetail?>()
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = bannerItems.toList()
            val enriched = snapshot.mapIndexed { idx, item -> idx to item }
                .limitedAsyncMap(concurrency = 6) { (idx, item) ->
                    idx to runCatching {
                        when (item) {
                            is BannerItem.Tmdb -> {
                                val d = Tmdb.detail(item.media.type, item.media.id)
                                val logo = d?.let { Tmdb.logoUrl(it) }
                                val st = d?.status?.let { statusLabel(it) }.orEmpty().ifBlank { null }
                                val score = if (item.media.voteAverage > 0) {
                                    String.format("%.1f", item.media.voteAverage) + "%"
                                } else null
                                val poster = d?.posterPath?.let { Tmdb.imageUrl(it, 342) }
                                BannerEnrichment(logo, st, score, d, poster)
                            }
                            is BannerItem.Plugin -> {
                                if (item.tmdbId != null && item.tmdbType != null) {
                                    val d = Tmdb.detail(item.tmdbType, item.tmdbId)
                                    val logo = d?.let { Tmdb.logoUrl(it) }
                                    val st = d?.status?.let { statusLabel(it) }.orEmpty().ifBlank { null }
                                    val poster = d?.posterPath?.let { Tmdb.imageUrl(it, 342) }
                                    BannerEnrichment(logo, st, null, d, poster)
                                } else null
                            }
                        }
                    }.getOrNull()
                }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                enriched.forEach { (idx, e) ->
                    e?.let {
                        if (it.logo != null) logos[idx] = it.logo
                        if (it.status != null) statuses[idx] = it.status
                        if (it.score != null) scores[idx] = it.score
                        if (it.detail != null) details[idx] = it.detail
                        if (it.poster != null) posters[idx] = it.poster
                    }
                }
                bannerCarouselAdapter?.updateDetails(logos, statuses, scores, details, posters)
            }
        }
    }

    private data class BannerEnrichment(
        val logo: String?,
        val status: String?,
        val score: String?,
        val detail: TmdbDetail?,
        val poster: String?,
    )

    private fun updateDots() {
        val dots = binding.tmdbBannerDots
        if (dots.visibility != View.VISIBLE || dots.childCount == 0) return
        val density = resources.displayMetrics.density

        val active = bannerIndex % dots.childCount
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val lp = dot.layoutParams
            lp.width = if (i == active) (32 * density).toInt() else (12 * density).toInt()
            dot.layoutParams = lp
            dot.background = if (i == active)
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_active)
            else
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_inactive)
        }
    }

    private fun setupBannerDots(rv: RecyclerView, itemCount: Int) {
        val dots = binding.tmdbBannerDots
        dots.removeAllViews()
        val density = resources.displayMetrics.density

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val shown = if (isPortrait) minOf(itemCount, 7) else itemCount
        for (i in 0 until shown) {
            val dot = View(requireContext())
            val w = if (i == 0) (32 * density).toInt() else (12 * density).toInt()
            val lp = LinearLayout.LayoutParams(w, (4 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            dot.layoutParams = lp
            dot.background = if (i == 0)
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_active)
            else
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_inactive)
            dot.setOnClickListener {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnClickListener
                val current = lm.findFirstVisibleItemPosition()
                val currentReal = current % itemCount
                val target = (currentReal / shown) * shown + i
                if (target == currentReal || target >= itemCount) return@setOnClickListener
                rv.smoothScrollToPosition(current + (target - currentReal))
            }
            dots.addView(dot)
        }
        dots.visibility = View.VISIBLE
    }

    private fun openBannerItem(item: BannerItem) {
        when (item) {

            is BannerItem.Tmdb -> openDetails(item.media.type, item.media.id)
            is BannerItem.Plugin -> {
                if (item.tmdbId != null && item.tmdbType != null) {
                    openDetails(item.tmdbType, item.tmdbId)
                } else {
                    startActivity(
                        Intent(requireContext(), TmdbDetailsActivity::class.java)
                            .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, item.sourceId)
                            .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.response.url)
                    )
                }
            }
        }
    }

    private suspend fun loadPluginSections(source: CsInstalledSource): Boolean {
        val apis = withContext(Dispatchers.IO) {
            CsRuntime.apisFor(requireContext(), source)
        }
        if (apis.isEmpty()) return false
        var added = false
        for (api in apis) {

            val pages = api.mainPage.filter { it.data.isNotBlank() }
            if (pages.isNotEmpty()) {
                for (page in pages) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        if (list.list.isNotEmpty()) {
                            addPluginSection(list.name, list.list, source)
                            added = true
                        }
                    }
                }
            } else {

                val resp = runCatching {
                    withContext(Dispatchers.IO) {
                        api.getMainPage(1, MainPageRequest("Home", "", false))
                    }
                }.getOrNull()
                resp?.items?.forEach { list ->
                    if (list.list.isNotEmpty()) {
                        addPluginSection(list.name, list.list, source)
                        added = true
                    }
                }
            }

            if (!added) {
                val quick = runCatching {
                    withContext(Dispatchers.IO) { api.quickSearch("") }
                }.getOrNull()
                if (!quick.isNullOrEmpty()) {
                    addPluginSection("Popular", quick, source)
                    added = true
                }
            }
        }
        return added
    }

    private fun Simkl.SimklWatchedItem.toExploreMedia(): Media? {
        val tmdbId = ids?.tmdb ?: return null
        val name = title ?: return null
        val type = mediaType ?: "tv"
        return Media(
            id = tmdbId,
            name = name,
            nameRomaji = name,
            userPreferredName = name,
            isAdult = false,

            cover = Simkl.imageUrl(poster),
            banner = Simkl.imageUrl(poster, "w"),
            format = type.uppercase(),
            tmdbType = type
        )
    }

    private fun simklMoreAdapter(items: List<Simkl.SimklWatchedItem>, title: String): SectionMoreAdapter {
        val exploreList = ArrayList(items.mapNotNull { it.toExploreMedia() })
        return SectionMoreAdapter {
            if (exploreList.isNotEmpty()) {
                MediaListViewActivity.passedMedia = exploreList
                startActivity(
                    Intent(requireContext(), MediaListViewActivity::class.java)
                        .putExtra("title", title)
                )
            }
        }
    }

    private suspend fun loadSimklContinueWatching() {
        if (Simkl.token == null) return
        val items = withContext(Dispatchers.IO) { Simkl.getContinueWatching() }
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = "Continue Watching"
            setPadding(24, 14, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            setTag(R.id.section_row_tag, true)
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = ConcatAdapter(
                SimklContinueWatchingLandscapeAdapter(items) { item ->
                    val tmdbId = item.ids?.tmdb ?: return@SimklContinueWatchingLandscapeAdapter
                    val mediaType = item.mediaType ?: "tv"
                    startActivity(
                        Intent(requireContext(), TmdbDetailsActivity::class.java)
                            .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                            .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, tmdbId)
                            .putExtra(TmdbDetailsActivity.ARG_OPEN_TAB, 1)
                    )
                },
                simklMoreAdapter(items, "Continue Watching")
            )
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11f.px }
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
    }

    private suspend fun loadSimklHomeSections() = coroutineScope {
        val all = if (Simkl.token != null) {
            withContext(Dispatchers.IO) { Simkl.getLibrary() }
                ?.let { it.movies.orEmpty() + it.shows.orEmpty() }.orEmpty()
        } else emptyList()

        val favourites = all.filter { (it.userRating ?: 0) >= 9 }
        if (favourites.isNotEmpty()) addSimklSection("Favourites", favourites)
        val planned = all.filter {
            val s = it.status?.lowercase()
            s == "plantowatch" || s == "planning"
        }
        if (planned.isNotEmpty()) addSimklSection("Planned", planned)
        loadRecommended(all)
        startAutoAdvance()
        applyTmdbBannerFocusChain()
    }

    private suspend fun loadRecommended(libraryItems: List<Simkl.SimklWatchedItem>) = coroutineScope {

        val seeds = libraryItems
            .mapNotNull { item ->
                val id = item.ids?.tmdb ?: return@mapNotNull null
                Triple(item, item.mediaType ?: "tv", id)
            }
            .distinctBy { it.second to it.third }
            .sortedWith(compareByDescending<Triple<Simkl.SimklWatchedItem, String, Int>> { it.first.userRating ?: 0 })
            .take(8)
        if (seeds.isEmpty()) return@coroutineScope

        val watchedGenres = async(Dispatchers.IO) {
            seeds.map { (_, mediaType, id) ->
                runCatching { Tmdb.detailGenres(mediaType, id).map { it.id } }.getOrDefault(emptyList())
            }.flatten().toSet()
        }

        val similar = seeds.map { (_, mediaType, id) ->
            async(Dispatchers.IO) {
                runCatching { Tmdb.similar(mediaType, id) }.getOrDefault(emptyList())
            }
        }.flatMap { it.await() }

        val genres = watchedGenres.await()
        val known = libraryItems.mapNotNull { it.ids?.tmdb }.toSet()
        val recs = similar
            .asReversed().distinctBy { it.type to it.id }.reversed()
            .filterNot { it.id in known }

            .filter { it.genreIds.any { g -> g in genres } }

            .sortedByDescending { it.genreIds.count { g -> g in genres } }
            .take(20)
        if (recs.isNotEmpty()) addSection("Recommended", recs)
    }

    private fun addSimklSection(title: String, items: List<Simkl.SimklWatchedItem>) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 14, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            setTag(R.id.section_row_tag, true)
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = ConcatAdapter(
                SimklRowAdapter(items) { item ->
                    val tmdbId = item.ids?.tmdb ?: return@SimklRowAdapter
                    openDetails(item.mediaType ?: "tv", tmdbId)
                },
                simklMoreAdapter(items, title)
            )
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11f.px }
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
        applyTmdbBannerFocusChain()
    }

    private fun addEmptyState(text: String) {
        binding.tmdbHomeSections.addView(
            TextView(requireContext()).apply {
                this.text = text
                setPadding(48, 24, 48, 24)
                textSize = 15f
                alpha = 0.7f
                setTextColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        )
    }

    private fun addPluginSection(
        title: String,
        items: List<SearchResponse>,
        source: CsInstalledSource
    ) {
        if (items.isEmpty()) return

        if (title.contains("Continue Watching", ignoreCase = true)) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 14, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            setTag(R.id.section_row_tag, true)
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = PluginRowAdapter(items) { item ->
                startActivity(
                    Intent(requireContext(), TmdbDetailsActivity::class.java)
                        .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, source.id)
                        .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.url)
                )
            }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11f.px }
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
    }

    private fun addSection(title: String, items: List<TmdbMedia>) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 14, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            setTag(R.id.section_row_tag, true)
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            val exploreList = ArrayList(items.map { it.toExploreMedia() })
            adapter = ConcatAdapter(
                TmdbRowAdapter(items) { media -> openDetails(media.type, media.id) },
                SectionMoreAdapter {
                    MediaListViewActivity.passedMedia = exploreList
                    startActivity(
                        Intent(requireContext(), MediaListViewActivity::class.java)
                            .putExtra("title", title)
                    )
                }
            )
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11f.px }
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
        applyTmdbBannerFocusChain()
    }

    private fun showBanner(index: Int) {
        val item = bannerItems.getOrNull(index) ?: return
        bannerIndex = index
        binding.tmdbBannerImage.loadImage(item.bannerUrl)

        binding.tmdbBannerImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        val meta = buildString {
            if (item is BannerItem.Tmdb && item.media.voteAverage > 0)
                append("★ ").append(String.format("%.1f", item.media.voteAverage)).append("  •  ")
            if (item.year.isNotBlank()) append(item.year).append("  •  ")
            append(item.type.replaceFirstChar { it.uppercase() })
        }
        binding.tmdbBannerTitle.text = item.title
        binding.tmdbBannerSideMeta.text = meta
        binding.tmdbBannerRating.text = meta
        val synopsis = item.overview?.takeIf { it.isNotBlank() } ?: ""
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val largeBanner = isClassicBanner()
        binding.tmdbBannerSynopsis.text = synopsis
        binding.tmdbBannerSynopsis.isVisible = !isPortrait && !largeBanner && synopsis.isNotBlank()
        binding.tmdbBannerSideSynopsis.text = synopsis
        binding.tmdbBannerSideSynopsis.isVisible = !largeBanner && synopsis.isNotBlank()
        val genreText = when (item) {
            is BannerItem.Tmdb -> item.media.genreIds.take(3).mapNotNull { genreNames[it] }.joinToString("  •  ")
            is BannerItem.Plugin -> ""
        }
        binding.tmdbBannerGenres.text = genreText
        binding.tmdbBannerGenres.isVisible = genreText.isNotBlank()
        binding.tmdbBannerStatus.isVisible = false
        binding.tmdbBannerStatusDivider.isVisible = false
        binding.tmdbBannerMetaDivider.isVisible = genreText.isNotBlank()
        when (item) {
            is BannerItem.Tmdb -> {
                populateSideChips(item.media)
                loadBannerLogo(item.media)
            }
            is BannerItem.Plugin -> {
                binding.tmdbBannerSideChips.removeAllViews()
                if (item.tmdbId != null && item.tmdbType != null) {
                    loadBannerLogoByType(item.tmdbType, item.tmdbId)
                } else {
                    logoJob?.cancel()
                    binding.tmdbBannerLogo.isVisible = false
                    binding.tmdbBannerPortraitLogo.isVisible = false
                }
            }
        }
    }

    private fun populateSideChips(item: TmdbMedia) {
        val group = binding.tmdbBannerSideChips
        group.removeAllViews()
        item.genreIds.take(3).mapNotNull { genreNames[it] }.forEach { name ->
            val chip = TextView(requireContext()).apply {
                text = name
                textSize = 11f
                setTextColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorOnBackground))
                setBackgroundResource(R.drawable.tag_chip_bg)
                val dp = resources.displayMetrics.density
                setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
                maxLines = 1
            }
            group.addView(chip)
        }
    }

    private fun loadBannerLogo(item: TmdbMedia) {
        logoJob?.cancel()
        logoJob = viewLifecycleOwner.lifecycleScope.launch {
            val detail = Tmdb.detail(item.type, item.id)
            val logo = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) detail?.let { Tmdb.logoUrl(it) } else null
            binding.tmdbBannerLogo.isVisible = logo != null
            if (logo != null) {
                val bannerW = binding.tmdbBannerLogo.maxWidth.coerceIn(200, 400)
                com.bumptech.glide.Glide.with(binding.tmdbBannerLogo.context)
                    .load(logo)
                    .override(bannerW, (bannerW * 0.4f).toInt())
                    .into(binding.tmdbBannerLogo)
            }
            val portraitLogo = binding.tmdbBannerPortraitLogo
            portraitLogo.isVisible = logo != null
            binding.tmdbBannerTitle.isVisible = logo == null
            if (logo != null) {
                val portraitW = portraitLogo.maxWidth.coerceIn(120, 240)
                com.bumptech.glide.Glide.with(portraitLogo.context)
                    .load(logo)
                    .override(portraitW, (portraitW * 0.4f).toInt())
                    .into(portraitLogo)
            }
            val status = detail?.status?.let { statusLabel(it) }.orEmpty()
            binding.tmdbBannerStatus.text = status
            binding.tmdbBannerStatus.isVisible = status.isNotBlank()
            binding.tmdbBannerStatusDivider.isVisible = status.isNotBlank()
        }
    }

    private fun loadBannerLogoByType(type: String, id: Int) {
        logoJob?.cancel()
        logoJob = viewLifecycleOwner.lifecycleScope.launch {
            val detail = Tmdb.detail(type, id)
            val logo = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) detail?.let { Tmdb.logoUrl(it) } else null
            binding.tmdbBannerLogo.isVisible = logo != null
            if (logo != null) {
                val bannerW = binding.tmdbBannerLogo.maxWidth.coerceIn(200, 400)
                com.bumptech.glide.Glide.with(binding.tmdbBannerLogo.context)
                    .load(logo)
                    .override(bannerW, (bannerW * 0.4f).toInt())
                    .into(binding.tmdbBannerLogo)
            }
            val portraitLogo = binding.tmdbBannerPortraitLogo
            portraitLogo.isVisible = logo != null
            binding.tmdbBannerTitle.isVisible = logo == null
            if (logo != null) {
                val portraitW = portraitLogo.maxWidth.coerceIn(120, 240)
                com.bumptech.glide.Glide.with(portraitLogo.context)
                    .load(logo)
                    .override(portraitW, (portraitW * 0.4f).toInt())
                    .into(portraitLogo)
            }
            val status = detail?.status?.let { statusLabel(it) }.orEmpty()
            binding.tmdbBannerStatus.text = status
            binding.tmdbBannerStatus.isVisible = status.isNotBlank()
            binding.tmdbBannerStatusDivider.isVisible = status.isNotBlank()
        }
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "returning series", "returning" -> "Ongoing"
        "released" -> "Released"
        "planned" -> "Upcoming"
        "in production" -> "In Production"
        "ended", "canceled", "cancelled" -> "Completed"
        else -> status
    }

    private fun centerStack(root: View, centered: Boolean) {
        if (root !is ViewGroup) return
        val gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            (child.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.gravity = gravity
                child.layoutParams = it
            }
            centerStack(child, centered)
        }
    }

    private fun applyBannerLayout() {

        val classic = isClassicBanner()
        centerStack(binding.tmdbBannerContent, classic)
        centerStack(binding.tmdbBannerSide, classic)
        if (classic) {

            binding.tmdbBannerMetaRow?.let { row ->

                (row as? LinearLayout)?.let { line ->
                    line.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                }
                (row as? ViewGroup)?.let { group ->
                    for (i in 0 until group.childCount) {
                        val child = group.getChildAt(i)
                        val lp = child.layoutParams as? LinearLayout.LayoutParams
                        if (lp != null && lp.weight > 0f) {

                            lp.weight = 0f
                            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                            child.layoutParams = lp
                        }
                    }
                }
            }

            binding.tmdbBannerSynopsis.isVisible = false
        }

        val ctx = requireContext()
        val isLandscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val card = binding.tmdbBannerCard
        val density = ctx.resources.displayMetrics.density
        val mode = PrefManager.getVal<Int>(PrefName.HomeBannerMode)
        if (mode == 1 || mode == 3) {
            binding.tmdbBannerFrame.visibility = View.GONE
            binding.tmdbBannerDots.visibility = View.GONE
            return
        }
        val carousel = isCarouselMode() && binding.tmdbBannerCarousel.adapter != null

        if (carousel) {
            bannerCarouselAdapter?.setLandscapeMode(isLandscape, card.bannerCardSizePx(0.65f).first)
            binding.tmdbBannerCarousel.isVisible = true
            binding.tmdbBannerImage.isVisible = false
            binding.tmdbBannerContent.isVisible = false
            binding.tmdbBannerDots.isVisible = bannerItems.size > 1

            if (isModernBanner()) {
                card.sizeBannerCard()
                card.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.CENTER or Gravity.TOP
                }
                binding.tmdbBannerFade.isVisible = false
                binding.tmdbBannerCardScrim.isVisible = false
                binding.tmdbBannerSide.isVisible = false
                return
            }

            if (isLandscape) {
                card.sizeBannerCard(0.65f)
                val (cardW, cardH) = card.bannerCardSizePx(0.65f)
                card.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.END or Gravity.TOP
                }

                if (isClassicBanner()) {
                    binding.tmdbBannerFade.isVisible = false
                    binding.tmdbBannerCardScrim.isVisible = false
                    binding.tmdbBannerSide.isVisible = false
                    return
                }
                val stripW = ctx.resources.displayMetrics.widthPixels - cardW
                binding.tmdbBannerFade.isVisible = true
                binding.tmdbBannerFade.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = stripW
                    height = cardH
                    gravity = Gravity.START or Gravity.TOP
                }
                binding.tmdbBannerFade.bringToFront()
                binding.tmdbBannerFade.z = 10f

                binding.tmdbBannerCardScrim.isVisible = !isClassicBanner()
                binding.tmdbBannerCardScrim.layoutParams = binding.tmdbBannerCardScrim.layoutParams.apply {
                    width = cardW / 2
                    height = cardH
                }
                binding.tmdbBannerSide.isVisible = true
                binding.tmdbBannerSide.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = stripW + cardW / 4
                    height = cardH
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                binding.tmdbBannerSide.clipToOutline = true
                binding.tmdbBannerSide.bringToFront()
                binding.tmdbBannerSide.z = 11f
                binding.tmdbBannerSideSynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    width = (stripW - 48 * density + cardW / 4).toInt().coerceAtLeast(1)
                }
                binding.tmdbBannerLogo.maxWidth = (stripW - 48 * density).toInt().coerceAtLeast(100)
                binding.tmdbBannerLogo.maxHeight = (cardH * 0.30f).toInt()
                showBanner(bannerIndex)
            } else {
                card.sizeBannerCard()
                val (cardW, cardH) = card.bannerCardSizePx()
                card.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.CENTER
                }
                binding.tmdbBannerFade.isVisible = false
                binding.tmdbBannerCardScrim.isVisible = false
                binding.tmdbBannerSide.isVisible = false
            }
            return
        }

        if (isLandscape) {
            card.sizeBannerCard(0.65f)
            val (cardW, cardH) = card.bannerCardSizePx(0.65f)
            card.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.END or Gravity.TOP
            }
            val stripW = ctx.resources.displayMetrics.widthPixels - cardW

            binding.tmdbBannerFade.isVisible = true
            binding.tmdbBannerFade.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stripW
                height = cardH
                gravity = Gravity.START or Gravity.TOP
            }
            binding.tmdbBannerFade.bringToFront()
            binding.tmdbBannerFade.z = 10f
            binding.tmdbBannerCardScrim.isVisible = !isClassicBanner()
            binding.tmdbBannerCardScrim.layoutParams = binding.tmdbBannerCardScrim.layoutParams.apply {
                width = cardW / 2
                height = cardH
            }
            binding.tmdbBannerSide.isVisible = true
            val largeBanner = isClassicBanner()
            binding.tmdbBannerSide.updateLayoutParams<FrameLayout.LayoutParams> {
                width = if (largeBanner) cardW else stripW + cardW / 4
                height = cardH
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            binding.tmdbBannerSide.clipChildren = true
            binding.tmdbBannerSide.bringToFront()
            binding.tmdbBannerSide.z = 11f
            binding.tmdbBannerSideSynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = (stripW - 48 * density + cardW / 4).toInt().coerceAtLeast(1)
            }
            binding.tmdbBannerLogo.maxWidth = (stripW - 48 * density).toInt().coerceAtLeast(100)
            binding.tmdbBannerLogo.maxHeight = (cardH * 0.30f).toInt()
            if (largeBanner) {
                binding.tmdbBannerSideWatchBtn.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = (2 * density).toInt() }

                binding.tmdbBannerSide.findViewById<android.view.View>(R.id.tmdbBannerLogo)?.let { v ->
                    v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = (5 * density).toInt() }
                }
            }
            binding.tmdbBannerImage.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.tmdbBannerContent.isVisible = false
        } else {
            card.sizeBannerCard()
            val (cardW, cardH) = card.bannerCardSizePx()
            card.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.CENTER
            }
            binding.tmdbBannerContent.updateLayoutParams<FrameLayout.LayoutParams> {
                width = cardW
                height = cardH
            }
            binding.tmdbBannerPortraitLogo.maxWidth = (cardW - 48 * density).toInt().coerceAtLeast(80)
            binding.tmdbBannerPortraitLogo.maxHeight = (cardH * 0.22f).toInt()
            binding.tmdbBannerFade.isVisible = false
            binding.tmdbBannerCardScrim.isVisible = false
            binding.tmdbBannerSide.isVisible = false
            binding.tmdbBannerContent.isVisible = true
            binding.tmdbBannerImage.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun startAutoAdvance() {
        bannerHandler.removeCallbacksAndMessages(null)
        val mode = PrefManager.getVal<Int>(PrefName.HomeBannerMode)
        if (mode != 0 || bannerItems.size < 2) return
        bannerHandler.postDelayed(object : Runnable {
            override fun run() {
                val lm = binding.tmdbBannerCarousel.layoutManager as? LinearLayoutManager
                if (isCarouselMode() && lm != null && lm.isSmoothScrolling()) {

                    bannerHandler.postDelayed(this, BANNER_SETTLE_POLL_MS)
                    return
                }
                if (isCarouselMode()) {
                    scrollBannerCarousel(forward = true)
                } else {
                    showBanner((bannerIndex + 1) % bannerItems.size)
                }
                bannerHandler.postDelayed(this, 6000)
            }
        }, 6000)
    }

    private fun scrollBannerCarousel(forward: Boolean, force: Boolean = false) {
        val rv = binding.tmdbBannerCarousel
        if (rv.adapter == null || rv.visibility != View.VISIBLE) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        val focus = requireActivity().currentFocus
        val onBanner = focus != null && (
            binding.tmdbBannerCarousel.findContainingViewHolder(focus) != null ||
            focus == binding.tmdbBannerWatchBtn || focus == binding.tmdbBannerSideWatchBtn
        )

        if (!onBanner || force) {
            rv.smoothScrollToPosition(pos + (if (forward) 1 else -1))
        }
    }

    private fun openDetails(mediaType: String, id: Int) {
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
        )
    }

    class TmdbRowAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbRowAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            TmdbCards.applyCardStyle(holder.binding, item)
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class PluginRowAdapter(
        private val items: List<SearchResponse>,
        private val onClick: (SearchResponse) -> Unit
    ) : RecyclerView.Adapter<PluginRowAdapter.VH>() {

        private val cardScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        private val titlePos: Int get() = PrefManager.getVal(PrefName.CardTitlePosition)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val isLive = item is LiveSearchResponse
            val (w, h) = if (landscape || isLive) {
                (260f * size).toInt() to (148f * size).toInt()
            } else {
                (102f * size).toInt() to (154f * size).toInt()
            }
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            b.tmdbCard.radius = TmdbCards.roundness()
            b.tmdbCardPoster.loadImage(item.posterUrl, if (landscape || isLive) 780 else 300)

            b.tmdbCardLogo.isVisible = false
            b.tmdbCardOverlayTitle.isVisible = false

            if (landscape) {
                b.tmdbCardPoster.loadImage(item.posterUrl, 780)
                b.tmdbCardPoster.tag = item.name
                when (titlePos) {
                    0 -> {
                        b.tmdbCardGradient.isVisible = true
                        b.tmdbCardGradient.updateLayoutParams<ViewGroup.LayoutParams> {
                            width = w; height = h
                        }
                        TmdbCards.setCardGradient(b.tmdbCardGradient)
                        b.tmdbCardOverlayTitle.isVisible = true
                        b.tmdbCardOverlayTitle.text = item.name
                        b.tmdbCardOverlayTitle.updateLayoutParams<ViewGroup.LayoutParams> { width = w }
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    2 -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    else -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardTitle.isVisible = true
                        b.tmdbCardTitle.text = item.name
                        b.tmdbCardYear.isVisible = false
                    }
                }

                cardScope.launch {
                    val name = item.name ?: return@launch
                    val results = runCatching { ani.sanin.connections.tmdb.Tmdb.search(name) }.getOrNull()
                    val match = results?.firstOrNull() ?: return@launch
                    val backdrop = match.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
                    val logoUrl = runCatching { ani.sanin.connections.tmdb.Tmdb.logoUrl(match.type, match.id) }.getOrNull()
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

                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
                if (titlePos == 2) {
                    b.tmdbCardTitle.isVisible = false
                    b.tmdbCardYear.isVisible = false
                } else {
                    b.tmdbCardTitle.isVisible = true
                    b.tmdbCardTitle.text = item.name
                    b.tmdbCardYear.isVisible = false
                }
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class SimklRowAdapter(
        private val items: List<Simkl.SimklWatchedItem>,
        private val onClick: (Simkl.SimklWatchedItem) -> Unit
    ) : RecyclerView.Adapter<SimklRowAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val (w, h) = if (landscape) {
                (260f * size).toInt() to (148f * size).toInt()
            } else {
                (102f * size).toInt() to (154f * size).toInt()
            }
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            b.tmdbCard.radius = TmdbCards.roundness()

            val posterUrl = Simkl.imageUrl(item.poster, if (landscape) "w" else "m")
            if (!posterUrl.isNullOrBlank()) {
                b.tmdbCardPoster.loadImage(posterUrl)
            } else {
                b.tmdbCardPoster.setImageResource(R.drawable.ic_round_person_24)
            }
            if (landscape) {

                b.tmdbCardTitle.isVisible = false
                b.tmdbCardYear.isVisible = false
                b.tmdbCardLogo.isVisible = false
                b.tmdbCardGradient.isVisible = true
                b.tmdbCardOverlayTitle.isVisible = true
                b.tmdbCardOverlayTitle.text = item.title ?: ""
            } else {

                b.tmdbCardTitle.isVisible = true
                b.tmdbCardTitle.text = item.title ?: ""
                b.tmdbCardYear.isVisible = item.year != null
                b.tmdbCardYear.text = item.year?.toString() ?: ""
                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
