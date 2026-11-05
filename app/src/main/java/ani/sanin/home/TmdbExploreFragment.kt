package ani.sanin.home
import ani.sanin.settings.AnimUtils

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.bannerCardSizePx
import ani.sanin.cloudstream.StreamingServicesAdapter
import ani.sanin.cloudstream.TmdbAllServicesActivity
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbServiceCatalogueActivity
import ani.sanin.cloudstream.TmdbWatchActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.databinding.FragmentTmdbExploreBinding
import ani.sanin.databinding.ItemExploreRowBinding
import ani.sanin.databinding.ItemTmdbExplorePageBinding
import ani.sanin.databinding.LayoutTrendingBinding
import ani.sanin.getThemeColor
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import ani.sanin.isTvDevice
import ani.sanin.limitedAsyncMap
import ani.sanin.settings.saving.PrefManager
import ani.sanin.snackString
import ani.sanin.connections.trakt.Trakt
import androidx.core.app.ActivityOptionsCompat
import ani.sanin.media.Franchise
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseActivity
import ani.sanin.media.FranchiseAdaptor
import ani.sanin.media.FranchiseListPrefs
import ani.sanin.media.FranchiseSort
import ani.sanin.media.FranchiseType
import ani.sanin.ui.InlineSearchBar
import ani.sanin.media.showFranchiseSortDialog
import ani.sanin.media.read
import ani.sanin.media.FranchiseSorter
import ani.sanin.media.onNearEnd
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaListViewActivity
import ani.sanin.setSafeOnClickListener
import ani.sanin.setSlideIn
import ani.sanin.setSlideUp
import ani.sanin.settings.saving.PrefName
import ani.sanin.sizeBannerCard
import ani.sanin.util.FocusEffectUtil
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

internal fun TmdbMedia.toExploreMedia(): Media = Media(
    id = id,
    name = displayTitle,
    nameRomaji = displayTitle,
    userPreferredName = displayTitle,
    isAdult = false,
    banner = Tmdb.imageUrl(backdropPath, 1280) ?: Tmdb.imageUrl(posterPath, 780),
    cover = Tmdb.imageUrl(posterPath, 500),
    description = overview,

    format = type.uppercase(),

    genres = ArrayList(Tmdb.genreNames(this)),

    meanScore = if (voteAverage > 0) (voteAverage * 10).toInt() else null,
    tmdbType = type
)

class TmdbExploreFragment : Fragment() {

    private var _binding: FragmentTmdbExploreBinding? = null
    private val binding get() = _binding!!

    private lateinit var trendingBinding: LayoutTrendingBinding
    private lateinit var streamingAdapter: StreamingServicesAdapter

    private var pageBinding: ItemTmdbExplorePageBinding? = null
    private val page get() = requireNotNull(pageBinding) { "explore page not bound" }
    private lateinit var pageAdapter: TmdbExplorePageAdapter

    private lateinit var franchiseAdapter: FranchiseAdaptor

    private var franchiseSearch: InlineSearchBar? = null

    private var allFranchiseCards: List<Franchise> = emptyList()

    private var franchiseSkip = 0
    private var franchiseHasMore = false

    private var franchiseLoading = false

    private var franchiseRowLoading = false

    private var franchiseLoadStarted = false

    private var loadedCollections = false

    private val rowAdapters = mutableMapOf<Tmdb.ExploreRow, MediaAdaptor>()
    private val rowMedia = mutableMapOf<Tmdb.ExploreRow, ArrayList<Media>>()

    private var pageBound = false

    private var loadGeneration = 0

    private var trendingMedia: List<TmdbMedia> = emptyList()
    private var bannerAdapter: BannerCarouselAdapter? = null
    private var bannerAdapterType = -1
    private var bannerSnap: PagerSnapHelper? = null
    private var bannerScrollListener: RecyclerView.OnScrollListener? = null
    private var bannerTitleShown = false
    private var trendingAutoScrollHandler: Handler? = null
    private var trendingAutoScrollRunnable: Runnable? = null
    private var trendingLogoJob: Job? = null
    private val trendingLogos = mutableMapOf<String, String?>()

    private var selectedType: ExploreType = ExploreType.MOVIE
    private var selectedChip: Chip? = null
    private var unselectedChipBackground: ColorStateList? = null

    private enum class ExploreType {
        MOVIE, TV, ANIMATION
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbExploreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onPause() {
        super.onPause()
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingLogoJob?.cancel()
        trendingLogoJob = null
        trendingLogos.clear()
        pageBinding = null

        rowAdapters.clear()
        rowMedia.clear()
        franchiseSearch?.release()
        franchiseSearch = null
        pageBound = false
        bannerTitleShown = false
        super.onDestroyView()
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeBanner()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pageBound = false
        loadGeneration++
        bannerTitleShown = false

        pageAdapter = TmdbExplorePageAdapter()

        franchiseAdapter = FranchiseAdaptor { card, view -> openFranchise(card, view) }
        binding.tmdbExploreRecyclerView.adapter =
            ConcatAdapter(pageAdapter, franchiseAdapter)

        binding.tmdbExploreRecyclerView.onNearEnd {
            loadFranchises(selectedType, loadGeneration, reset = false)
        }

        pageAdapter.onPageBound = { page ->
            pageBinding = page
            trendingBinding = LayoutTrendingBinding.bind(page.root)

            reattachRowAdapters()

            if (!pageBound) {
                pageBound = true
                setupChips()
                setupStreamingRail()
                setupFocusChain()
                setupFranchiseHeader()
                selectType(ExploreType.MOVIE)
                loadStreamingServices()
            }
        }
    }

    private fun reattachRowAdapters() {
        for (row in railRows) {
            if (row == Tmdb.ExploreRow.POPULAR) continue
            val adapter = rowAdapters[row] ?: continue
            val rail = rowFor(row)?.first ?: continue
            rail.rowRecyclerView.layoutManager =
                LinearLayoutManager(rail.root.context, LinearLayoutManager.HORIZONTAL, false)
            rail.rowRecyclerView.adapter =
                ConcatAdapter(adapter, SectionMoreAdapter { openRowMore(row) })
        }
    }

    private fun setupBanner(media: List<TmdbMedia>) {
        trendingMedia = media
        trendingBinding.trendingProgressBar.visibility = View.GONE
        val rv = trendingBinding.trendingViewPager
        rv.layoutManager = LinearLayoutManager(rv.context, LinearLayoutManager.HORIZONTAL, false)

        bannerSnap?.attachToRecyclerView(null)
        bannerSnap = PagerSnapHelper().also { it.attachToRecyclerView(rv) }
        rv.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        rv.isFocusable = true
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.nextFocusDownId = R.id.tmdbExploreTypeScroll

        val bannerMedia = media.map { it.toExploreMedia() }
        bannerAdapter = BannerCarouselAdapter(
            items = bannerMedia,
            scope = CoroutineScope(Dispatchers.Main),
            onItemClick = { item -> openDetails(item) },
            nextFocusDownId = R.id.tmdbExploreTypeScroll,
            layoutRes = layoutForBannerType(isModernBanner()),

            cardMode = !isModernBanner() && !isClassicBanner(),
            hideDescription = !isModernBanner(),
            modernMode = isModernBanner()
        )
        bannerAdapterType = ani.sanin.bannerType()
        rv.adapter = bannerAdapter
        trendingBinding.trendingCard.sizeBannerCard(0.65f)

        applyTrendingBannerMode()
        applyTypeSelectorSpacing()

        val start = if (media.isEmpty()) 0
        else Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % media.size)
        rv.scrollToPosition(start)
        bannerScrollListener?.let { rv.removeOnScrollListener(it) }
        bannerScrollListener = object : RecyclerView.OnScrollListener() {
            private var lastTarget = -1

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val overlay = trendingBinding.trendingOverlay
                if (!overlay.isVisible) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val child = lm.getChildAt(0) ?: return
                val pos = lm.getPosition(child)
                if (pos == RecyclerView.NO_POSITION) return
                val cardW = child.width
                val stripW = overlay.width - cardW / 4
                if (cardW <= 0 || stripW <= 0) return
                val progress = (-child.left).toFloat() / cardW
                val real = pos % media.size
                val target = if (progress < 0.5f) real else (real + 1) % media.size
                if (target != lastTarget) {
                    lastTarget = target
                    updateTrendingOverlay(media[target])
                }
                val scale = stripW.toFloat() / cardW
                overlay.translationX =
                    (if (progress < 0.5f) child.left else child.left + cardW) * scale
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    lastTarget = -1
                    trendingBinding.trendingOverlay.translationX = 0f
                    updateTrendingOverlayForCurrent()
                    resetTrendingAutoScroll()
                }
            }
        }
        rv.addOnScrollListener(bannerScrollListener!!)
        setupTrendingDots(media.size)

        updateTrendingOverlayForCurrent()

        if (media.isNotEmpty()) {

            if (rv.layoutAnimation == null) {
                rv.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
            }
            if (!bannerTitleShown) {
                bannerTitleShown = true
                trendingBinding.titleContainer.startAnimation(setSlideUp())
            }
            startTrendingAutoScroll(rv)
        }
        loadBannerLogos(bannerMedia)
    }

    private fun loadBannerLogos(bannerMedia: List<Media>) {
        if (bannerMedia.isEmpty()) return
        if (!PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) return
        viewLifecycleOwner.lifecycleScope.launch {
            val fetched = bannerMedia.limitedAsyncMap(concurrency = 6) { media ->
                val type = media.tmdbType ?: return@limitedAsyncMap null
                val url = runCatching { Tmdb.logoUrl(type, media.id) }.getOrNull()
                Triple(logoKey(type, media.id), media.id, url)
            }.mapNotNull { it }
            if (fetched.isEmpty() || _binding == null) return@launch
            val logosById = mutableMapOf<Int, String?>()
            for ((key, id, url) in fetched) {
                trendingLogos[key] = url
                if (!url.isNullOrBlank()) logosById[id] = url
            }
            if (logosById.isNotEmpty()) bannerAdapter?.updateUrls(emptyMap(), logosById)
            updateTrendingOverlayForCurrent()
        }
    }

    private fun logoKey(type: String, id: Int): String = "$type:$id"

    private fun applyTrendingBannerMode() {
        val ctx = trendingBinding.root.context
        val isLandscape =
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (cardW, cardH) = trendingBinding.trendingCard.bannerCardSizePx(0.65f)

        val cardLp = trendingBinding.trendingCard.layoutParams as ConstraintLayout.LayoutParams
        cardLp.startToStart = if (isLandscape) ConstraintSet.UNSET
        else ConstraintLayout.LayoutParams.PARENT_ID
        cardLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        trendingBinding.trendingCard.layoutParams = cardLp

        val fullBleed = isModernBanner() || isClassicBanner()

        trendingBinding.trendingLeftFade.isVisible = isLandscape && !fullBleed
        if (isLandscape) {
            trendingBinding.trendingLeftFade.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = ctx.resources.displayMetrics.widthPixels - cardW
                height = cardH
            }
        }

        val overlay = trendingBinding.trendingOverlay
        if (isLandscape && !fullBleed) {
            overlay.isVisible = true
            val density = ctx.resources.displayMetrics.density
            val sidePad = (24 * density).toInt()
            val stripW = ctx.resources.displayMetrics.widthPixels - cardW
            overlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = stripW + cardW / 4
                height = cardH
            }
            overlay.setPadding(sidePad, 0, sidePad, 0)
            trendingBinding.trendingOverlayLogo.maxWidth =
                (stripW - sidePad * 2).coerceAtLeast(1)
            trendingBinding.trendingOverlayLogo.maxHeight = (cardH * 0.30f).toInt()
            trendingBinding.trendingOverlaySynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = (stripW - sidePad * 2 + cardW / 4).coerceAtLeast(1)
            }
            updateTrendingOverlayForCurrent()
        } else {
            overlay.isVisible = false
        }

        bannerAdapter?.setLandscapeMode(isLandscape, cardW)
        trendingBinding.trendingWatchBtn.isVisible = isLandscape && !fullBleed
        setupTrendingWatchBtn()
    }

    private fun resizeBanner() {
        if (!::trendingBinding.isInitialized || trendingMedia.isEmpty()) return
        trendingBinding.trendingCard.sizeBannerCard(0.65f)
        trendingBinding.trendingContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 0
        }
        applyTrendingBannerMode()
        applyTypeSelectorSpacing()
        bannerAdapter?.notifyDataSetChanged()
    }

    private fun setupTrendingWatchBtn() {
        val btn = trendingBinding.trendingWatchBtn
        btn.setOnClickListener { currentTrendingMedia()?.let { openWatch(it) } }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    currentTrendingMedia()?.let { openWatch(it) }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveTrendingCarousel(keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    true
                }
                else -> false
            }
        }
        FocusEffectUtil.applyFocusListener(btn)
    }

    private fun currentTrendingMedia(): TmdbMedia? {
        if (trendingMedia.isEmpty()) return null
        val lm = trendingBinding.trendingViewPager.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos < 0) return null
        return trendingMedia[pos % trendingMedia.size]
    }

    private fun moveTrendingCarousel(forward: Boolean) {
        val rv = trendingBinding.trendingViewPager
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        rv.smoothScrollToPosition(pos + (if (forward) 1 else -1))
    }

    private fun applyTypeSelectorSpacing() {
        if (pageBinding == null) return
        page.tmdbExploreTypeScroll.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 0
        }
    }

    private fun updateTrendingOverlayForCurrent() {
        if (!::trendingBinding.isInitialized || trendingMedia.isEmpty()) return
        currentTrendingMedia()?.let { updateTrendingOverlay(it) }
    }

    private fun updateTrendingOverlay(media: TmdbMedia) {
        val overlay = trendingBinding.trendingOverlay
        if (!overlay.isVisible) return
        val title = trendingBinding.trendingOverlayTitle
        val logo = trendingBinding.trendingOverlayLogo

        title.text = media.displayTitle
        val cacheKey = logoKey(media.type, media.id)
        if (trendingLogos.containsKey(cacheKey)) {
            val cached = trendingLogos[cacheKey]
            if (!cached.isNullOrBlank()) {
                trendingLogoJob?.cancel()
                showTrendingLogo(logo, title, cached)
            } else {
                logo.isVisible = false
                logo.setImageDrawable(null)
                title.isVisible = true
            }
        } else {
            title.isVisible = true
            logo.isVisible = false
            logo.setImageDrawable(null)
            loadTrendingLogo(media, logo, title)
        }

        val chips = trendingBinding.trendingOverlayChips
        chips.removeAllViews()
        if (media.voteAverage > 0) {
            addOverlayChip(chips, "★ " + String.format(java.util.Locale.US, "%.1f", media.voteAverage))
        }
        if (media.year.isNotBlank()) addOverlayChip(chips, media.year)
        addOverlayChip(chips, media.type.replaceFirstChar { it.uppercase() })
        chips.isVisible = chips.childCount > 0

        val genres = trendingBinding.trendingOverlayGenres
        genres.removeAllViews()
        val genreNames = Tmdb.genreNames(media).take(3)
        val density = genres.resources.displayMetrics.density
        for (name in genreNames) {
            val chip = TextView(genres.context).apply {
                text = name
                textSize = 11f
                setTextColor(genres.context.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
                setBackgroundResource(R.drawable.tag_chip_bg)
                setPadding(
                    (10 * density).toInt(),
                    (3 * density).toInt(),
                    (10 * density).toInt(),
                    (3 * density).toInt()
                )
                maxLines = 1
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * density).toInt()
            genres.addView(chip, lp)
        }
        genres.isVisible = genreNames.isNotEmpty()

        val synopsis = trendingBinding.trendingOverlaySynopsis
        val desc = media.overview?.takeIf { it.isNotBlank() }
        synopsis.isVisible = !isModernBanner() && desc != null
        if (desc != null) synopsis.text = desc
    }

    private fun loadTrendingLogo(
        media: TmdbMedia,
        logo: android.widget.ImageView,
        title: TextView,
    ) {
        trendingLogoJob?.cancel()
        if (!PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) return
        trendingLogoJob = viewLifecycleOwner.lifecycleScope.launch {
            val url = runCatching { Tmdb.logoUrl(media.type, media.id) }.getOrNull()
            if (!isAdded || _binding == null) return@launch
            trendingLogos[logoKey(media.type, media.id)] = url
            if (url.isNullOrBlank()) return@launch
            showTrendingLogo(logo, title, url)
        }
    }

    private fun showTrendingLogo(
        logo: android.widget.ImageView,
        title: TextView,
        url: String,
    ) {
        val width = logo.maxWidth.coerceIn(200, 400)
        Glide.with(logo.context)
            .load(url)
            .override(width, (width * 0.4f).toInt())
            .into(logo)
        logo.isVisible = true
        title.isVisible = false
    }

    private fun addOverlayChip(container: LinearLayout, text: String?) {
        if (text.isNullOrBlank()) return
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val chip = TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
            textSize = 12f
            setBackgroundResource(R.drawable.tag_chip_bg)
            setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt()
            )
            maxLines = 1
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (6 * density).toInt()
        container.addView(chip, lp)
    }

    private fun setupTrendingDots(itemCount: Int) {
        val dots = trendingBinding.trendingDots
        dots.removeAllViews()
        if (itemCount <= 1) {
            dots.visibility = View.GONE
            return
        }
        val density = dots.resources.displayMetrics.density
        val isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val shown = if (isPortrait) min(itemCount, 7) else itemCount
        for (i in 0 until shown) {
            val dot = View(dots.context)
            val w = if (i == 0) (32 * density).toInt() else (12 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(w, (4 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            dot.layoutParams = lp
            dot.background = ContextCompat.getDrawable(
                dots.context,
                if (i == 0) R.drawable.banner_dot_active else R.drawable.banner_dot_inactive
            )
            dots.addView(dot)
        }
        dots.visibility = View.VISIBLE
    }

    private fun startTrendingAutoScroll(rv: RecyclerView) {
        if (!AnimUtils.enabled(ani.sanin.settings.saving.PrefName.TrendingScroller)) return
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler = Handler(Looper.getMainLooper())
        trendingAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (trendingMedia.isEmpty()) return

                val lm = rv.layoutManager as? LinearLayoutManager
                if (lm != null && lm.isSmoothScrolling()) {
                    trendingAutoScrollHandler?.postDelayed(this, BANNER_SETTLE_POLL_MS)
                    return
                }
                val focus = (binding.root.context as? androidx.appcompat.app.AppCompatActivity)?.currentFocus
                val onBannerControl = focus != null && (
                    focus.id == R.id.trendingWatchBtn ||
                        focus.id == R.id.trendingViewPager ||
                        trendingBinding.trendingViewPager.findContainingViewHolder(focus) != null
                    )
                if (!onBannerControl) {
                    val pos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION) rv.smoothScrollToPosition(pos + 1)
                }
                trendingAutoScrollHandler?.postDelayed(this, 5000L)
            }
        }
        trendingAutoScrollHandler?.postDelayed(trendingAutoScrollRunnable!!, 5000L)
    }

    private fun resetTrendingAutoScroll() {
        val runnable = trendingAutoScrollRunnable ?: return
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler?.postDelayed(runnable, 5000L)
    }

    private fun setupChips() {
        page.tmdbExploreChipMovie.setSafeOnClickListener { selectType(ExploreType.MOVIE) }
        page.tmdbExploreChipTv.setSafeOnClickListener { selectType(ExploreType.TV) }
        page.tmdbExploreChipAnimation.setSafeOnClickListener {
            selectType(ExploreType.ANIMATION)
        }
    }

    private fun selectType(type: ExploreType) {
        selectedType = type
        val chip = when (type) {
            ExploreType.MOVIE -> page.tmdbExploreChipMovie
            ExploreType.TV -> page.tmdbExploreChipTv
            ExploreType.ANIMATION -> page.tmdbExploreChipAnimation
        }
        selectedChip?.takeIf { it !== chip }?.let { setChipSelected(it, false) }
        setChipSelected(chip, true)
        selectedChip = chip

        val generation = ++loadGeneration
        loadBannerFor(type, generation)

        loadRows(type, generation)
        loadFranchises(type, generation)
    }

    private fun setChipSelected(chip: Chip, selected: Boolean) {
        if (unselectedChipBackground == null) {
            unselectedChipBackground = chip.chipBackgroundColor
        }
        chip.chipBackgroundColor = if (selected) {
            ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    chip.context.getThemeColor(com.google.android.material.R.attr.colorPrimary),
                    SELECTED_FILL_ALPHA
                )
            )
        } else {
            unselectedChipBackground
                ?: ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }
        chip.isSelected = selected
    }

    private fun loadBannerFor(type: ExploreType, generation: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val media = when (type) {
                ExploreType.MOVIE -> Tmdb.trending("movie")
                ExploreType.TV -> Tmdb.trending("tv")

                ExploreType.ANIMATION ->
                    Tmdb.discover("movie", genres = "16", sort = "popularity.desc") +
                        Tmdb.discover("tv", genres = "16", sort = "popularity.desc")
            }
            if (generation != loadGeneration) return@launch
            setupBanner(media)
        }
    }

    private fun setupStreamingRail() {
        streamingAdapter = StreamingServicesAdapter(
            isTv = isTvDevice(requireContext()),
            onServiceClick = { provider -> openServiceCatalogue(provider) },
            onSeeAll = {
                startActivity(Intent(requireContext(), TmdbAllServicesActivity::class.java))
            }
        )
        page.tmdbExploreStreamingRail.adapter = streamingAdapter
    }

    private fun openServiceCatalogue(provider: TmdbProvider) {
        startActivity(
            Intent(requireContext(), TmdbServiceCatalogueActivity::class.java)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_ID, provider.id)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_NAME, provider.displayName)
                .putExtra(
                    TmdbServiceCatalogueActivity.ARG_MEDIA_TYPE,
                    if (selectedType == ExploreType.TV) "tv" else "movie"
                )
        )
    }

    private fun loadStreamingServices() {
        viewLifecycleOwner.lifecycleScope.launch {
            val providers = Tmdb.watchProviders()

            page.tmdbExploreStreamingRail.isVisible = providers.isNotEmpty()
            streamingAdapter.submit(providers)
        }
    }

    private fun setupFocusChain() {

        var aboveId: Int = R.id.tmdbExploreStreamingRail
        for (row in railRows) {
            val (rowBinding, rowId) = when (row) {
                Tmdb.ExploreRow.IN_CINEMA -> page.rowInCinema to R.id.tmdbRowInCinema
                Tmdb.ExploreRow.TRENDING -> page.rowTrending to R.id.tmdbRowTrending
                Tmdb.ExploreRow.TOP_RATED -> page.rowTopRated to R.id.tmdbRowTopRated
                Tmdb.ExploreRow.LATEST_RELEASE ->
                    page.rowLatestRelease to R.id.tmdbRowLatestRelease
                Tmdb.ExploreRow.UPCOMING -> page.rowUpcoming to R.id.tmdbRowUpcoming
                Tmdb.ExploreRow.POPULAR -> continue
            }
            rowBinding.rowRecyclerView.id = rowId
            rowBinding.rowRecyclerView.nextFocusUpId = aboveId
            rowBinding.rowMore.nextFocusUpId = aboveId
            aboveId = rowId
        }

        page.tmdbFranchiseHeader.nextFocusUpId = aboveId

        page.tmdbFranchiseSearch.nextFocusUpId = aboveId
    }

    private fun swapAdapterList(
        list: MutableList<Media>,
        adapter: RecyclerView.Adapter<*>,
        items: List<Media>
    ) {
        val oldSize = list.size
        if (oldSize > 0) {
            list.clear()
            adapter.notifyItemRangeRemoved(0, oldSize)
        }
        if (items.isNotEmpty()) {
            list.addAll(items)
            adapter.notifyItemRangeInserted(0, items.size)
        }
    }

    private fun rowFor(row: Tmdb.ExploreRow): Pair<ItemExploreRowBinding, Int>? {
        val page = pageBinding ?: return null
        val binding = when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> page.rowInCinema
            Tmdb.ExploreRow.TRENDING -> page.rowTrending
            Tmdb.ExploreRow.TOP_RATED -> page.rowTopRated
            Tmdb.ExploreRow.LATEST_RELEASE -> page.rowLatestRelease
            Tmdb.ExploreRow.UPCOMING -> page.rowUpcoming
            Tmdb.ExploreRow.POPULAR -> return null
        }
        return binding to when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> R.string.in_cinema
            Tmdb.ExploreRow.TRENDING -> R.string.row_trending
            Tmdb.ExploreRow.TOP_RATED -> R.string.top_rated
            Tmdb.ExploreRow.LATEST_RELEASE -> R.string.latest_release
            Tmdb.ExploreRow.UPCOMING -> R.string.upcoming

            Tmdb.ExploreRow.POPULAR -> R.string.row_franchise
        }
    }

    private val railRows = listOf(
        Tmdb.ExploreRow.IN_CINEMA,
        Tmdb.ExploreRow.TRENDING,
        Tmdb.ExploreRow.TOP_RATED,
        Tmdb.ExploreRow.LATEST_RELEASE,
        Tmdb.ExploreRow.UPCOMING
    )

    private fun loadRows(type: ExploreType, generation: Int) {
        val tmdbType = when (type) {
            ExploreType.MOVIE -> "movie"
            ExploreType.TV -> "tv"
            ExploreType.ANIMATION -> "animation"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            railRows.forEach { row ->
                launch {
                    val media = runCatching { Tmdb.exploreRow(row, tmdbType) }.getOrDefault(emptyList())
                    if (generation != loadGeneration) return@launch
                    bindRow(row, media.map { it.toExploreMedia() })
                }
            }
        }
    }

    private fun bindRow(row: Tmdb.ExploreRow, media: List<Media>) {
        val target = rowFor(row) ?: return
        val (rowBinding, titleRes) = target
        if (media.isEmpty()) {
            rowBinding.root.isVisible = false
            return
        }

        val firstLoad = row !in rowAdapters
        val wasHidden = !rowBinding.root.isVisible
        rowBinding.root.isVisible = true
        rowBinding.rowProgress.visibility = View.GONE
        rowBinding.rowTitle.setText(titleRes)
        val list = rowMedia.getOrPut(row) { ArrayList() }
        val adapter = rowAdapters.getOrPut(row) {
            val created = MediaAdaptor(0, list, requireActivity())
            rowBinding.rowRecyclerView.layoutManager = LinearLayoutManager(
                rowBinding.root.context, LinearLayoutManager.HORIZONTAL, false
            )
            rowBinding.rowRecyclerView.adapter =
                ConcatAdapter(created, SectionMoreAdapter { openRowMore(row) })
            created
        }

        swapAdapterList(list, adapter, media)

        rowBinding.rowMore.setSafeOnClickListener {
            openRowMore(row)
        }
        rowBinding.rowTitle.isVisible = true
        rowBinding.rowMore.isVisible = true
        rowBinding.rowRecyclerView.isVisible = true
        if (firstLoad || wasHidden) {
            rowBinding.rowTitle.startAnimation(setSlideUp())
            rowBinding.rowMore.startAnimation(setSlideUp())
        }
    }

    private fun openRowMore(row: Tmdb.ExploreRow) {
        val target = rowFor(row) ?: return
        val list = rowMedia[row] ?: ArrayList()
        MediaListViewActivity.passedMedia = ArrayList(list)
        MediaListViewActivity.passedExploreRow = row
        MediaListViewActivity.passedExploreType = when (selectedType) {
            ExploreType.MOVIE -> "movie"
            ExploreType.TV -> "tv"
            ExploreType.ANIMATION -> "animation"
        }
        startActivity(
            Intent(requireContext(), MediaListViewActivity::class.java)
                .putExtra("title", getString(target.second))
        )
    }

    private fun setupFranchiseHeader() {
        page.tmdbFranchiseHeader.isVisible = true

        page.tmdbFranchiseSortPill.setSafeOnClickListener { openFranchiseSortDialog() }
        page.tmdbFranchiseSortDirection.setSafeOnClickListener { invertFranchiseSort() }
        updateFranchiseSortUi()

        setupFranchiseSearch()
    }

    private fun setupFranchiseSearch() {

        if (franchiseSearch?.button === page.tmdbFranchiseSearch) return
        franchiseSearch?.release()
        franchiseSearch = InlineSearchBar(
            button = page.tmdbFranchiseSearch,
            bar = page.tmdbFranchiseSearchBar,
            field = page.tmdbFranchiseSearchText,
            onQueryChanged = { query -> franchiseAdapter.setQuery(query) },
            onQuerySettled = { updateFranchiseState() },
            onOpened = { scrollTmdbFranchiseHeaderIntoView() },
        )
        updateFranchiseState()
    }

    private fun updateFranchiseState() {
        val page = pageBinding ?: return
        val cards = allFranchiseCards
        val loading = cards.isEmpty() && franchiseRowLoading
        page.tmdbFranchiseProgress.isVisible = loading
        val message = when {
            franchiseAdapter.isSearchMissed() -> R.string.franchise_search_empty
            cards.isEmpty() && !loading && franchiseLoadStarted -> R.string.franchise_none
            else -> null
        }
        page.tmdbFranchiseMessage.isVisible = message != null
        if (message != null) page.tmdbFranchiseMessage.setText(message)
    }

    private fun scrollTmdbFranchiseHeaderIntoView() {
        val page = pageBinding ?: return
        val rv = binding.tmdbExploreRecyclerView
        val rvTop = IntArray(2)
        val headerTop = IntArray(2)
        rv.getLocationInWindow(rvTop)
        page.tmdbFranchiseHeader.getLocationInWindow(headerTop)
        val delta = headerTop[1] - rvTop[1]
        if (delta > 0) rv.smoothScrollBy(0, -delta)
    }

    private fun franchisePrefs() = FranchiseListPrefs.read()

    private fun updateFranchiseSortUi() {
        page.tmdbFranchiseSortPill.setText(franchisePrefs().sort.labelRes())
    }

    private fun applyFranchiseSort(): Int {
        val prefs = franchisePrefs()
        val shuffled = if (prefs.sort == FranchiseSort.RANDOM) {
            allFranchiseCards.shuffled()
        } else {
            allFranchiseCards
        }
        val sorted = FranchiseSorter.apply(allFranchiseCards, prefs, shuffled)

        Logger.log(
            "Franchise(movie) sort=${prefs.sort} dir=${prefs.direction} " +
                "showSingle=${prefs.showSingleEntry}: ${allFranchiseCards.size} in, " +
                "${sorted.size} out, first=${sorted.take(5).map { it.name }}"
        )
        franchiseAdapter.submit(sorted)
        return sorted.size
    }

    private fun appendFranchiseCards(cards: List<Franchise>): Int {
        if (cards.isEmpty()) return 0
        val known = allFranchiseCards.mapTo(HashSet()) { it.name.lowercase() }
        val fresh = cards.filterNot { it.name.lowercase() in known }
        if (fresh.isEmpty()) return 0
        allFranchiseCards = allFranchiseCards + fresh

        franchiseAdapter.submit(
            FranchiseSorter.apply(allFranchiseCards, franchisePrefs(), allFranchiseCards)
        )

        updateFranchiseState()
        return fresh.size
    }

    private fun invertFranchiseSort() {
        val prefs = franchisePrefs()
        if (prefs.sort == FranchiseSort.RANDOM) {
            PrefManager.setVal(PrefName.FranchiseSortOrder, FranchiseSort.RANDOM.name)
            snackString(R.string.franchise_randomized, requireActivity())
        } else {
            val next = prefs.direction.inverted()
            PrefManager.setVal(PrefName.FranchiseSortDirectionPref, next.name)
            snackString(next.labelRes(), requireActivity())
        }
        applyFranchiseSort()
    }

    private fun openFranchiseSortDialog() {

        showFranchiseSortDialog(showCollections = true) {
            updateFranchiseSortUi()

            if (collectionsMode() == loadedCollections) applyFranchiseSort() else reloadFranchises()
        }
    }

    private fun collectionsMode() = franchisePrefs().sort == FranchiseSort.COLLECTIONS

    private fun tmdbTypeFor(type: ExploreType) = when (type) {
        ExploreType.MOVIE -> "movie"
        ExploreType.TV -> "tv"
        ExploreType.ANIMATION -> "animation"
    }

    private fun reloadFranchises() = loadFranchises(selectedType, ++loadGeneration, reset = true)

    private fun loadFranchises(
        type: ExploreType,
        generation: Int,
        reset: Boolean = true,
    ) {

        if (!reset && (franchiseHasMore.not() || franchiseLoading)) return
        val collections = collectionsMode()
        loadedCollections = collections
        if (reset) {
            franchiseSkip = 0
            franchiseHasMore = true
            allFranchiseCards = emptyList()
            franchiseLoadStarted = true
        }
        val skip = franchiseSkip
        viewLifecycleOwner.lifecycleScope.launch {
            val started = System.currentTimeMillis()
            Logger.log(
                "Franchise(movie) load: chip=${type.name} " +
                    "source=${if (collections) "collections" else "franchises"} " +
                    "generation=$generation reset=$reset skip=$skip pool=${allFranchiseCards.size}"
            )
            if (reset) setFranchiseProgress(true)
            franchiseLoading = true
            try {
                if (collections) {

                    val batch = try {
                        Trakt.franchiseCards(skip = skip)
                    } catch (e: Exception) {

                        Logger.log(e)
                        Logger.log("Franchise(movie) load threw: ${e.message}")
                        Trakt.TraktBatch(emptyList(), hasMore = false)
                    }

                    if (generation != loadGeneration) {
                        Logger.log(
                            "Franchise(movie) dropping ${batch.cards.size} stale cards " +
                                "for generation $generation"
                        )
                        return@launch
                    }

                    val known = allFranchiseCards.mapTo(HashSet()) { it.name.lowercase() }
                    val fresh = batch.cards.filterNot { it.name.lowercase() in known }
                    allFranchiseCards = allFranchiseCards + fresh

                    franchiseSkip = skip + Trakt.BATCH_CARDS
                    franchiseHasMore = batch.hasMore
                    val shown = applyFranchiseSort()
                    Logger.log(
                        "Franchise(movie) got ${batch.cards.size} cards (${fresh.size} new) in " +
                            "${System.currentTimeMillis() - started}ms, submitted $shown after " +
                            "sort from ${allFranchiseCards.size}, more=$franchiseHasMore"
                    )
                } else {

                    val batch = try {
                        Tmdb.franchiseCards(tmdbTypeFor(type), page = skip + 1) { card ->

                            withContext(Dispatchers.Main) {
                                if (generation == loadGeneration) appendFranchiseCards(listOf(card))
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log(e)
                        Logger.log("Franchise(movie) load threw: ${e.message}")
                        Tmdb.FranchiseBatch(emptyList(), hasMore = false, nextPage = skip + 1)
                    }
                    if (generation != loadGeneration) {
                        Logger.log(
                            "Franchise(movie) dropping ${batch.cards.size} stale franchises " +
                                "for generation $generation"
                        )
                        return@launch
                    }

                    franchiseSkip = batch.nextPage - 1
                    franchiseHasMore = batch.hasMore

                    val shown = applyFranchiseSort()
                    Logger.log(
                        "Franchise(movie) got ${batch.cards.size} franchises in " +
                            "${System.currentTimeMillis() - started}ms, submitted $shown after " +
                            "sort from ${allFranchiseCards.size}, more=$franchiseHasMore"
                    )
                }
            } finally {

                franchiseLoading = false
                if (reset) setFranchiseProgress(false)
            }
        }
    }

    private fun setFranchiseProgress(loading: Boolean) {
        franchiseRowLoading = loading
        updateFranchiseState()
    }

    private fun openFranchise(card: Franchise, view: View) {
        view.transitionName = FRANCHISE_TRANSITION
        startActivity(
            FranchiseActivity.intent(requireContext(), card, preselectTypeFor(selectedType)),
            ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(),
                view,
                FRANCHISE_TRANSITION,
            ).toBundle()
        )
    }

    private fun preselectTypeFor(type: ExploreType): FranchiseType? = when (type) {
        ExploreType.MOVIE -> FranchiseType.MOVIE
        ExploreType.TV -> FranchiseType.SEQUENCE
        ExploreType.ANIMATION -> null
    }

    private fun openDetails(media: Media) {
        val type = media.tmdbType ?: "movie"
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun openWatch(media: TmdbMedia) {
        startActivity(
            Intent(requireContext(), TmdbWatchActivity::class.java)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_TYPE, media.type)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private companion object {
        const val SELECTED_FILL_ALPHA = 0x4D

        const val FRANCHISE_TRANSITION = "franchiseCard"
    }
}
