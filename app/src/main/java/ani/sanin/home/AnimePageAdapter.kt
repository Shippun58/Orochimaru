package ani.sanin.home
import ani.sanin.settings.AnimUtils

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.LayoutAnimationController
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.home.BannerCarouselAdapter
import ani.sanin.R
import ani.sanin.bannerType
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import com.google.android.material.chip.Chip
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.databinding.ItemAnimePageBinding
import ani.sanin.databinding.LayoutTrendingBinding
import ani.sanin.getAppString
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaListViewActivity
import ani.sanin.openLinkInCustomTab
import ani.sanin.profile.ProfileActivity
import ani.sanin.px
import ani.sanin.setSafeOnClickListener
import ani.sanin.setSlideIn
import ani.sanin.setSlideUp
import ani.sanin.settings.SettingsDialogFragment
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.bannerCardSizePx
import ani.sanin.sizeBannerCard
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnimePageAdapter : RecyclerView.Adapter<AnimePageAdapter.AnimePageViewHolder>() {

    private companion object {
        const val PREVIOUS_SEASON = 0
        const val CURRENT_SEASON = 1
        const val NEXT_SEASON = 2

        const val SELECTED_SEASON_FILL_ALPHA = 0x4D
    }

    val ready = MutableLiveData(false)
    lateinit var binding: ItemAnimePageBinding
    private lateinit var trendingBinding: LayoutTrendingBinding
    var bannerAdapter: BannerCarouselAdapter? = null
        private set

    private var bannerAdapterType: Int = -1
    private var bannerSnap: PagerSnapHelper? = null
    private var trendingMedia: List<Media> = emptyList()
    private var trendingLogos: Map<Int, String?> = emptyMap()
    private var trendingAutoScrollHandler: android.os.Handler? = null
    private var trendingAutoScrollRunnable: Runnable? = null

    private var selectedSeason = CURRENT_SEASON

    private var unselectedChipBackground: ColorStateList? = null

    private var selectedSeasonChip: Chip? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimePageViewHolder {
        val binding =
            ItemAnimePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AnimePageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AnimePageViewHolder, position: Int) {
        binding = holder.binding
        trendingBinding = LayoutTrendingBinding.bind(binding.root)
        trendingBinding.trendingCard.sizeBannerCard(0.65f)
        applyTrendingBannerMode()
        trendingBinding.trendingViewPager.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        applySeasonSelectorSpacing()

        if (PrefManager.getVal(PrefName.SmallView)) trendingBinding.trendingContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = (-108f).px
        }

        val seasons = Anilist.currentSeasons
        listOf(
            binding.animePreviousSeason to PREVIOUS_SEASON,
            binding.animeThisSeason to CURRENT_SEASON,
            binding.animeNextSeason to NEXT_SEASON,
        ).forEach { (chip, index) ->
            val (season, year) = seasons[index]
            chip.text = seasonLabel(season, year)
            val selected = index == selectedSeason
            setSeasonSelected(chip, selected)
            if (selected) selectedSeasonChip = chip
            chip.setSafeOnClickListener {
                selectSeason(chip, index)
                onSeasonClick.invoke(index)
            }
            chip.setOnLongClickListener { onSeasonLongClick.invoke(index) }
            FocusEffectUtil.applyFocusListener(chip)
        }

        binding.animeFranchiseHeader.isVisible = true

        if (ready.value == false)
            ready.postValue(true)
    }

    lateinit var onSeasonClick: ((Int) -> Unit)
    lateinit var onSeasonLongClick: ((Int) -> Boolean)

    override fun getItemCount(): Int = 1

    fun updateHeight() {
        trendingBinding.trendingContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 0
        }
    }

    fun refreshBannerTypeIfChanged() {
        if (!::trendingBinding.isInitialized) return
        val type = bannerType()
        if (type == bannerAdapterType) return
        val media = trendingMedia
        if (media.isEmpty()) return

        val rv = trendingBinding.trendingViewPager
        bannerAdapter = BannerCarouselAdapter(
            items = media,
            scope = CoroutineScope(Dispatchers.Main),
            onItemClick = { item ->
                ContextCompat.startActivity(
                    binding.root.context,
                    Intent(
                        binding.root.context,
                        ani.sanin.media.MediaDetailsActivity::class.java
                    )
                        .putExtra("media", item)
                        .putExtra("anime", true),
                    null
                )
            },
            nextFocusDownId = R.id.animeSeasons,
            layoutRes = layoutForBannerType(isModernBanner()),
            cardMode = !isModernBanner() && !isClassicBanner(),
            hideDescription = !isModernBanner(),
            modernMode = isModernBanner(),
        )
        bannerAdapterType = type
        rv.adapter = bannerAdapter

        val start = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % media.size)
        rv.scrollToPosition(start)

        applyTrendingBannerMode()
        updateTrendingOverlayForCurrent()
    }

    fun resizeBanner() {
        if (::trendingBinding.isInitialized) {
            trendingBinding.trendingCard.sizeBannerCard(0.65f)
            trendingBinding.trendingContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = 0
            }
            applyTrendingBannerMode()
            applySeasonSelectorSpacing()

            bannerAdapter?.notifyDataSetChanged()
        }
    }

    private fun applySeasonSelectorSpacing() {
        val ctx = binding.root.context
        val density = ctx.resources.displayMetrics.density
        val topGap = if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            (108 * density).toInt() else (120 * density).toInt()
        binding.animeSeasons.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topGap
        }
    }

    private fun setupTrendingWatchBtn() {
        val btn = trendingBinding.trendingWatchBtn
        val activity = binding.root.context as? AppCompatActivity
        btn.setOnClickListener { currentTrendingMedia()?.let { openTrendingMedia(it) } }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    currentTrendingMedia()?.let { openTrendingMedia(it) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveTrendingCarousel(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    true
                }
                else -> false
            }
        }
        FocusEffectUtil.applyFocusListener(btn)
        btn.nextFocusUpId = R.id.mainCalendarContainer
        binding.animeRecently.isFocusable = true
        binding.animeRecently.nextFocusUpId = R.id.trendingWatchBtn
        btn.nextFocusDownId = R.id.animeRecently
        activity?.findViewById<View>(R.id.mainCalendarContainer)?.nextFocusDownId = R.id.trendingWatchBtn
        activity?.findViewById<View>(R.id.mainUserAvatarContainer)?.nextFocusDownId = R.id.trendingWatchBtn
    }

    private fun currentTrendingMedia(): Media? {
        if (trendingMedia.isEmpty()) return null
        val lm = trendingBinding.trendingViewPager.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos < 0) return null
        return trendingMedia[pos % trendingMedia.size]
    }

    private fun openTrendingMedia(media: Media) {
        val context = binding.root.context
        ContextCompat.startActivity(
            context,
            Intent(context, ani.sanin.media.MediaDetailsActivity::class.java)
                .putExtra("media", media)
                .putExtra("anime", true),
            null
        )
    }

    private fun moveTrendingCarousel(forward: Boolean) {
        val rv = trendingBinding.trendingViewPager
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        rv.smoothScrollToPosition(pos + (if (forward) 1 else -1))
    }

    private fun applyTrendingBannerMode() {
        val ctx = trendingBinding.root.context
        val isLandscape =
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (cardW, cardH) = trendingBinding.trendingCard.bannerCardSizePx(0.65f)

        val cardLp =
            trendingBinding.trendingCard.layoutParams as ConstraintLayout.LayoutParams
        cardLp.startToStart = if (isLandscape) ConstraintSet.UNSET
        else ConstraintLayout.LayoutParams.PARENT_ID
        cardLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        trendingBinding.trendingCard.layoutParams = cardLp

        val modern = isModernBanner()
        val classic = isClassicBanner()
        val fullBleed = modern || classic

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

    private fun updateTrendingOverlayForCurrent() {
        if (!::trendingBinding.isInitialized || trendingMedia.isEmpty()) return
        val rv = trendingBinding.trendingViewPager
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        val real = if (pos == RecyclerView.NO_POSITION || pos < 0) 0 else pos % trendingMedia.size
        updateTrendingOverlay(trendingMedia[real])
    }

    private fun updateTrendingOverlay(media: Media) {
        val logo = trendingBinding.trendingOverlayLogo
        val title = trendingBinding.trendingOverlayTitle
        val chips = trendingBinding.trendingOverlayChips
        val genres = trendingBinding.trendingOverlayGenres
        val synopsis = trendingBinding.trendingOverlaySynopsis

        val logoUrl = trendingLogos[media.id]
        if (!logoUrl.isNullOrBlank()) {
            logo.isVisible = true
            title.isVisible = false
            logo.loadImage(logoUrl)
        } else {
            logo.isVisible = false
            logo.setImageDrawable(null)
            title.isVisible = true
            title.text = media.userPreferredName ?: media.name
        }

        chips.removeAllViews()
        addTrendingChip(chips, trendingFormatText(media))
        addTrendingChip(chips, trendingStatusText(media))
        addTrendingChip(chips, trendingSeasonText(media))
        addTrendingChip(chips, trendingScoreText(media))

        genres.removeAllViews()
        for (g in media.genres.take(4)) addTrendingChip(genres, g)

        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        if (isClassicBanner()) {
            synopsis.isVisible = false
        } else if (!desc.isNullOrBlank()) {
            synopsis.text = desc
            synopsis.isVisible = true
        } else {
            synopsis.isVisible = false
        }
    }

    private fun addTrendingChip(container: LinearLayout, text: String?) {
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

    private fun trendingFormatText(media: Media): String? =
        media.format?.replace("_", " ")?.let { fmt ->
            when {
                fmt.equals("TV", true) -> "TV Series"
                fmt.equals("TV_SHORT", true) -> "TV Short"
                else -> fmt
            }
        }

    private fun selectSeason(chip: Chip, index: Int) {
        selectedSeason = index
        selectedSeasonChip?.takeIf { it !== chip }?.let { setSeasonSelected(it, false) }
        setSeasonSelected(chip, true)
        selectedSeasonChip = chip
    }

    private fun setSeasonSelected(chip: Chip, selected: Boolean) {
        if (unselectedChipBackground == null) {
            unselectedChipBackground = chip.chipBackgroundColor
        }
        chip.chipBackgroundColor = if (selected) {
            ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    chip.context.getThemeColor(
                        com.google.android.material.R.attr.colorPrimary
                    ),
                    SELECTED_SEASON_FILL_ALPHA,
                )
            )
        } else {

            unselectedChipBackground
                ?: ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }
        chip.isSelected = selected
    }

    private fun seasonLabel(season: String, year: Int): String {
        val name = season.lowercase().replaceFirstChar { it.uppercase() }

        return "$name '${"%02d".format(year % 100)}"
    }

    private fun trendingStatusText(media: Media): String? =
        media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }

    private fun trendingSeasonText(media: Media): String? {
        val season = media.anime?.season?.lowercase()
        val year = media.anime?.seasonYear
        return if (season != null && year != null) "$season $year" else null
    }

    private fun trendingScoreText(media: Media): String? =
        media.meanScore?.let { "$it%" }

    fun updateTrending(media: List<Media>) {
        trendingMedia = media
        trendingLogos = emptyMap()
        trendingBinding.trendingProgressBar.visibility = View.GONE
        val rv = trendingBinding.trendingViewPager
        rv.layoutManager = LinearLayoutManager(rv.context, LinearLayoutManager.HORIZONTAL, false)
        bannerSnap?.let { it.attachToRecyclerView(null) }
        bannerSnap = PagerSnapHelper()
        bannerSnap?.attachToRecyclerView(rv)
        rv.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        rv.isFocusable = true
        rv.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.nextFocusDownId = R.id.animeSeasons
        val scope = CoroutineScope(Dispatchers.Main)
        bannerAdapter = BannerCarouselAdapter(
            media, scope, { item ->
                val context = binding.root.context
                ContextCompat.startActivity(
                    context,
                    Intent(context, ani.sanin.media.MediaDetailsActivity::class.java)
                        .putExtra("media", item)
                        .putExtra("anime", true),
                    null
                )
            },
            nextFocusDownId = R.id.animeSeasons,
            layoutRes = layoutForBannerType(isModernBanner()),

            cardMode = !isModernBanner() && !isClassicBanner(),
            hideDescription = !isModernBanner(),
            modernMode = isModernBanner(),
        )
        bannerAdapterType = bannerType()
        rv.adapter = bannerAdapter
        applyTrendingBannerMode()
        val start = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % media.size)
        rv.scrollToPosition(start)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
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
                    updateTrendingOverlay(trendingMedia[target])
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
                }
            }
        })
        setupTrendingDots(rv, media.size)
        updateTrendingOverlayForCurrent()
        rv.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
        trendingBinding.titleContainer.startAnimation(setSlideUp())
        binding.animeSeasonsCont.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
        if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.TrendingScroller)) {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        trendingAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (media.isEmpty()) return

                val lm = rv.layoutManager as? LinearLayoutManager
                if (lm != null && lm.isSmoothScrolling()) {
                    trendingAutoScrollHandler?.postDelayed(this, BANNER_SETTLE_POLL_MS)
                    return
                }
                val focus = (binding.root.context as? AppCompatActivity)?.currentFocus
                val onTrendingControl = focus != null && (
                    focus.id == R.id.trendingWatchBtn ||
                    focus.id == R.id.trendingViewPager ||
                    trendingBinding.trendingViewPager.findContainingViewHolder(focus) != null
                )
                if (!onTrendingControl) {

                    val current = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    if (current != RecyclerView.NO_POSITION) rv.smoothScrollToPosition(current + 1)
                }
                trendingAutoScrollHandler?.postDelayed(this, 5000L)
            }
        }
        trendingAutoScrollHandler?.postDelayed(trendingAutoScrollRunnable!!, 5000L)
        }

        scope.launch(Dispatchers.IO) {
            val allImages = AniZip.getImagesBatch(media.map { it.id })
            val backdrops = mutableMapOf<Int, String?>()
            for (m in media) {
                val aUrl = allImages[m.id]?.backdropUrl
                if (!aUrl.isNullOrBlank()) {
                    backdrops[m.id] = aUrl
                } else {
                    backdrops[m.id] = try {
                        val results = ani.sanin.connections.tmdb.Tmdb.search(m.nameRomaji)
                        results.firstOrNull()?.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 1280) }
                    } catch (_: Exception) { null }
                }
            }
            val logos = ani.sanin.connections.LogoApi.getLogosBatch(media.map { it.id })
            withContext(Dispatchers.Main) {
                trendingLogos = logos
                bannerAdapter?.updateUrls(backdrops, logos)
                updateTrendingOverlayForCurrent()
            }
        }
    }

    private fun resetTrendingAutoScroll() {
        val runnable = trendingAutoScrollRunnable ?: return
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler?.postDelayed(runnable, 5000L)
    }

    fun pauseTrendingAutoScroll() {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
    }

    private fun setupTrendingDots(rv: RecyclerView, itemCount: Int) {
        val dots = trendingBinding.trendingDots
        dots.removeAllViews()
        val density = rv.context.resources.displayMetrics.density

        val isPortrait = rv.context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val shown = if (isPortrait) minOf(itemCount, 7) else itemCount
        val dotsList = mutableListOf<View>()
        for (i in 0 until shown) {
            val dot = View(rv.context)
            val w = if (i == 0) (32 * density).toInt() else (12 * density).toInt()
            val lp = LinearLayout.LayoutParams(w, (4 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            dot.layoutParams = lp
            dot.background = if (i == 0)
                ContextCompat.getDrawable(rv.context, R.drawable.banner_dot_active)
            else
                ContextCompat.getDrawable(rv.context, R.drawable.banner_dot_inactive)
            dot.setOnClickListener {
                val lm = rv.layoutManager as LinearLayoutManager
                val current = lm.findFirstVisibleItemPosition()
                val currentReal = current % itemCount
                val target = (currentReal / shown) * shown + i
                if (target == currentReal || target >= itemCount) return@setOnClickListener
                rv.smoothScrollToPosition(current + (target - currentReal))
            }
            dots.addView(dot)
            dotsList.add(dot)
        }
        dots.visibility = View.VISIBLE

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    resetTrendingAutoScroll()
                    return
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (itemCount == 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val raw = lm.findFirstVisibleItemPosition()
                    if (raw == RecyclerView.NO_POSITION) return
                    resetTrendingAutoScroll()
                    val pos = raw % itemCount % shown
                    for (i in 0 until dotsList.size) {
                        val dot = dotsList[i]
                        val lp = dot.layoutParams
                        lp.width = if (i == pos) (32 * density).toInt() else (12 * density).toInt()
                        dot.layoutParams = lp
                        dot.background = if (i == pos)
                            ContextCompat.getDrawable(rv.context, R.drawable.banner_dot_active)
                        else
                            ContextCompat.getDrawable(rv.context, R.drawable.banner_dot_inactive)
                    }
                    updateTrendingOverlayForCurrent()
                }
            }
        })
    }

    fun updateRecent(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeUpdatedRecyclerView,
                animeUpdatedProgressBar,
                animeRecently,
                animeRecentlyMore,
                getAppString(R.string.updated),
                media
            )
            animeFranchiseHeader.visibility = View.VISIBLE
            animeFranchiseHeader.startAnimation(setSlideUp())
            if (adaptor.itemCount == 0) {
                animeRecentlyContainer.visibility = View.GONE
            }
        }

    }

    fun updateMovies(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeMoviesRecyclerView,
                animeMoviesProgressBar,
                animeMovies,
                animeMoviesMore,
                getAppString(R.string.trending_movies),
                media
            )
        }
    }

    fun updateTopRated(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeTopRatedRecyclerView,
                animeTopRatedProgressBar,
                animeTopRated,
                animeTopRatedMore,
                getAppString(R.string.top_rated),
                media
            )
        }
    }

    fun updateMostFav(adaptor: MediaAdaptor, media: MutableList<Media>) {
        binding.apply {
            init(
                adaptor,
                animeMostFavRecyclerView,
                animeMostFavProgressBar,
                animeMostFav,
                animeMostFavMore,
                getAppString(R.string.most_favourite),
                media
            )
        }
    }

    fun init(
        adaptor: MediaAdaptor,
        recyclerView: RecyclerView,
        progress: View,
        title: View,
        more: View,
        string: String,
        media: MutableList<Media>
    ) {
        progress.visibility = View.GONE
        recyclerView.adapter = ConcatAdapter(adaptor, SectionMoreAdapter { v ->
            MediaListViewActivity.passedMedia = media.toCollection(ArrayList())
            ContextCompat.startActivity(
                v.context, Intent(v.context, MediaListViewActivity::class.java)
                    .putExtra("title", string),
                null
            )
        })
        recyclerView.layoutManager =
            LinearLayoutManager(
                recyclerView.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )

        more.setOnClickListener {
            MediaListViewActivity.passedMedia = media.toCollection(ArrayList())
            ContextCompat.startActivity(
                it.context, Intent(it.context, MediaListViewActivity::class.java)
                    .putExtra("title", string),
                null
            )
        }
        recyclerView.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        more.visibility = View.VISIBLE
        title.startAnimation(setSlideUp())
        more.startAnimation(setSlideUp())
        recyclerView.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
    }

    inner class AnimePageViewHolder(val binding: ItemAnimePageBinding) :
        RecyclerView.ViewHolder(binding.root)
}
