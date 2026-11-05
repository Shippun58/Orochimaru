package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.ui.LensButtonBackground
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.settings.saving.PrefName
import ani.sanin.settings.saving.PrefManager
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbSearchActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.FragmentTmdbDiscoveryBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.reels.ReelFeed
import ani.sanin.reels.TmdbReelFeed
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.youtube.ReelShortsPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TmdbDiscoveryFragment : Fragment() {

    private var _binding: FragmentTmdbDiscoveryBinding? = null
    private val binding get() = _binding!!
    private val tmdbCategoryItems = mutableListOf<TmdbMedia>()
    private val tmdbCategoryItemsAll = mutableListOf<TmdbMedia>()
    private val tmdbCategoryRowCards = mutableListOf<DiscoveryStandardCardAdapter.Card>()
    private val tmdbCategoryRowCardsAll = mutableListOf<DiscoveryStandardCardAdapter.Card>()
    private var tmdbTypeFilter = 0
    private val adapter = TmdbGridAdapter(tmdbCategoryItems) { openDetails(it) }
    private lateinit var tmdbListRowAdapter: DiscoveryListRowAdapter
    private var tmdbGridButton: ImageButton? = null
    private var tmdbListButton: ImageButton? = null
    private var genres: List<TmdbGenre> = emptyList()

    private var shelvesScrollY = 0
    private var selectedCategory: TmdbDiscoveryCategory? = null
    private val tmdbPool = mutableListOf<TmdbMedia>()
    private val tmdbSeen = HashSet<Pair<String, Int>>()
    private var tmdbMoviePage = 1
    private var tmdbTvPage = 1
    private var tmdbMoviePhase = 0
    private var tmdbTvPhase = 0
    private var tmdbMovieExhausted = false
    private var tmdbTvExhausted = false
    private var tmdbMovieSkips = 0
    private var tmdbTvSkips = 0
    private var tmdbExhaustedAnnounced = false
    private var tmdbCategoryLoading = false
    private var tmdbReloadUsed = false
    private var tmdbCategoryEmptyView: TextView? = null
    private val keywordCache = java.util.concurrent.ConcurrentHashMap<String, String?>()
    private val artworkCache = mutableMapOf<String, String?>()
    private val artworkRenderedUrls = mutableMapOf<String, String>()
    private val artworkLoading = mutableSetOf<String>()
    private val artworkFailedAt = mutableMapOf<String, Long>()
    private var artworkLimit = 12
    private var backCallback: OnBackPressedCallback? = null

    private var tmdbReelLoaded = false
    private var tmdbReelEntries: List<ReelFeed.ReelItem> = emptyList()
    private var tmdbReelCards: List<TmdbReelCard> = emptyList()

    private data class TmdbReelCard(val title: String, val key: String, val startIndex: Int)

    private val categorySemaphore = Semaphore(3)
    private val fallbackArtworkMutex = Mutex()
    private var tmdbFallbackArtwork: List<String> = emptyList()

    private companion object {

        const val RAIL_MEDIA_COUNT = 12
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTmdbDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        artworkCache.putAll(DiscoveryArtworkStore.tmdbPosters(requireContext()))
        val dm = resources.displayMetrics
        binding.tmdbDiscoveryGrid.layoutManager = GridLayoutManager(
            requireContext(), TmdbCards.gridSpan(dm.widthPixels / dm.density)
        )
        binding.tmdbDiscoveryGrid.isNestedScrollingEnabled = false
        tmdbListRowAdapter = DiscoveryListRowAdapter(tmdbCategoryRowCards) { card ->
            val item = tmdbCategoryItems.firstOrNull { it.id == -card.id }
            if (item != null) openDetails(item)
        }
        binding.tmdbDiscoveryGrid.adapter = adapter
        binding.tmdbDiscoveryProgress.isVisible = false
        binding.tmdbGenreChips.isVisible = false
        binding.tmdbCategoryChips.isVisible = false
        binding.tmdbDiscoveryGrid.isVisible = false

        val openSearch = { startActivity(Intent(requireContext(), TmdbSearchActivity::class.java)) }
        binding.tmdbDiscoverySearchText.setOnClickListener { openSearch() }
        binding.tmdbDiscoverySearchBar.isClickable = true
        binding.tmdbDiscoverySearchBar.isFocusableInTouchMode = true
        binding.tmdbDiscoverySearchBar.setOnClickListener { openSearch() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoverySearchBar)

        val avatarUrl = ani.sanin.connections.simkl.Simkl.avatar
            ?: ani.sanin.connections.anilist.Anilist.avatar
        if (!avatarUrl.isNullOrBlank()) binding.tmdbDiscoveryAvatar.loadImage(avatarUrl)
        binding.tmdbDiscoveryAvatar.setOnClickListener {
            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(Gravity.END)) {
                    act.findViewById<android.widget.FrameLayout>(
                        act.resources.getIdentifier("mainAvatarContainer", "id", act.packageName)
                    )?.let {
                        runCatching {
                            val method = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                            method.isAccessible = true
                            method.invoke(act)
                        }
                    }
                    drawer.openDrawer(Gravity.END)
                }
            }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoveryAvatar)

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                showCategories()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback!!)

        
        
        binding.tmdbDiscoveryGrid.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || selectedCategory == null) return
                val count = rv.adapter?.itemCount ?: return
                val lm = rv.layoutManager
                val last = when (lm) {
                    is androidx.recyclerview.widget.GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is androidx.recyclerview.widget.LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (last >= count - 8) loadTmdbCategoryPage()
            }
        })
        binding.tmdbDiscoveryScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (selectedCategory != null) {
                val content = binding.tmdbDiscoveryScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
                if (scrollY + binding.tmdbDiscoveryScroll.height >= content - dp(600)) {
                    loadTmdbCategoryPage()
                }
                return@setOnScrollChangeListener
            }
            if (selectedCategory == null) {

                val rowsAhead = (scrollY + binding.tmdbDiscoveryScroll.height) / dp(118) + 2
                val needed = (rowsAhead * 2).coerceAtMost(TmdbDiscoveryCategories.all.size)
                if (needed > artworkLimit) {
                    
                    
                    artworkLimit = needed
                    Logger.log("TMDB shelf artwork scroll catch-up scrollY=" + scrollY + " requested=" + artworkLimit + "/" + TmdbDiscoveryCategories.all.size)
                    loadCategoryArtwork()
                }
            }
        }

        renderCategories()
        loadCategoryArtwork()
        loadTmdbReelRail()
        viewLifecycleOwner.lifecycleScope.launch {
            genres = runCatching { Tmdb.genres() }.getOrDefault(emptyList())
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun openDetails(item: TmdbMedia) {
        startActivity(Intent(requireContext(), TmdbDetailsActivity::class.java)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, item.type)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, item.id)
            .putExtra(TmdbDetailsActivity.ARG_OPEN_TAB, TmdbDetailsActivity.TAB_INFO))
    }

    private fun loadTmdbReelRail() {
        if (tmdbReelLoaded) {
            renderTmdbReelRail()
            return
        }
        tmdbReelLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbReelFeed.buildInterleaved(TmdbReelFeed.railPool(RAIL_MEDIA_COUNT))
                }.getOrNull()
            }
            tmdbReelEntries = entries ?: emptyList()

            val seen = HashSet<String>()
            tmdbReelCards = tmdbReelEntries.mapIndexedNotNull { index, item ->
                if (seen.add(item.animeTitle)) TmdbReelCard(item.animeTitle, item.key, index) else null
            }
            if (_binding != null) renderTmdbReelRail()
        }
    }

    private fun renderTmdbReelRail() {
        val host = _binding?.tmdbTrailerCarousel ?: return
        host.visibility = View.VISIBLE
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
        if (tmdbReelCards.isEmpty()) {
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
                    text = if (Tmdb.apiKey.isBlank())
                        "Add TMDB API key" else "Loading trailers ${index + 1}"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, dp(8), 0, 0)
                })
                row.addView(placeholder)
            }
        } else {
            tmdbReelCards.forEach { card ->
                val holder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(dp(116), dp(190)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { openTmdbReelPlayer(card.startIndex) }
                }
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = card.title
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(145)
                    )
                }
                thumb.loadTmdbReelThumbnail(card.key)
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

    private fun openTmdbReelPlayer(startIndex: Int) {
        if (tmdbReelEntries.isEmpty() || !isAdded) return
        val intent = Intent(requireContext(), ReelShortsPlayerActivity::class.java).apply {
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_VIDEO_IDS,
                ArrayList(tmdbReelEntries.map { it.key })
            )

            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TITLES,
                ArrayList(tmdbReelEntries.map { it.videoName })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_ANIME_TITLES,
                ArrayList(tmdbReelEntries.map { it.animeTitle })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_SYNOPSIS,
                ArrayList(tmdbReelEntries.map { it.synopsis ?: "" })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_GENRES,
                ArrayList(tmdbReelEntries.map { it.genres.joinToString("|") })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_COVERS,
                ArrayList(tmdbReelEntries.map { it.cover ?: "" })
            )

            putIntegerArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TMDB_IDS,
                ArrayList(tmdbReelEntries.map { it.tmdbId })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TMDB_TYPES,
                ArrayList(tmdbReelEntries.map { it.mediaType ?: "movie" })
            )
            putExtra(ReelShortsPlayerActivity.EXTRA_START_INDEX, startIndex)
            putExtra(ReelShortsPlayerActivity.EXTRA_REEL_MODE, true)
            putExtra(ReelShortsPlayerActivity.EXTRA_REEL_SOURCE, ReelShortsPlayerActivity.SOURCE_TMDB)
        }
        startActivity(intent)
    }

    private fun ImageView.loadTmdbReelThumbnail(key: String) {
        val view = this
        Glide.with(view)
            .load(ReelFeed.thumbnailUrl(key))
            .error(Glide.with(view).load(ReelFeed.thumbnailFallbackUrl(key)))
            .into(view)
    }

    private fun renderCategories() {
        val host = _binding?.tmdbEditorialShelves ?: return
        artworkRenderedUrls.clear()
        host.removeAllViews()
        host.orientation = LinearLayout.VERTICAL
        host.setPadding(dp(3), dp(4), dp(3), dp(18))
        host.addView(TextView(requireContext()).apply {
            text = "FIND YOUR NEXT OBSESSION"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        })
        host.addView(TextView(requireContext()).apply {
            text = "150 moods, tropes, worlds, and rabbit holes. Pick a shelf and follow it wherever it goes."
            textSize = 12.5f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), 0, dp(12), dp(16))
        })

        val measuredWidth = host.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableWidthDp = (measuredWidth / resources.displayMetrics.density).toInt()
        val columnCount = ((availableWidthDp + 18) / (140 + 18)).coerceIn(1, 2)
        TmdbDiscoveryCategories.all.chunked(columnCount).forEachIndexed { rowIndex, rowCategories ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(18) }
            }
            rowCategories.forEachIndexed { columnIndex, category ->
                val card = LinearLayout(requireContext()).apply {
                    tag = category.id
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    clipChildren = false
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                        if (columnIndex < columnCount - 1) marginEnd = dp(18)
                    }
                    contentDescription = "${category.title}. ${category.description}"
                    setOnClickListener { openCategory(category) }
                }
                val artworkFrame = FrameLayout(requireContext()).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(82), dp(82))
                }
                val image = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                        }
                    }
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = "${category.title} poster"
                    rotation = if (columnIndex % 2 == 0) -8f else 8f
                    layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
                    tag = category.id
                    artworkCache[category.id]?.let { loadImage(it, dp(82)) }
                }
                artworkFrame.addView(image)
                val surface = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
                fun addFade(orientation: GradientDrawable.Orientation, colors: IntArray, params: FrameLayout.LayoutParams) {
                    artworkFrame.addView(View(requireContext()).apply {
                        background = GradientDrawable(orientation, colors)
                        isClickable = false
                        isFocusable = false
                    }, params)
                }
                val fadeSize = dp(30)
                addFade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
                addFade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
                addFade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.TOP))
                addFade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.BOTTOM))

                val textColumn = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }
                textColumn.addView(TextView(requireContext()).apply {
                    text = category.title
                    textSize = 13.5f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                })
                textColumn.addView(TextView(requireContext()).apply {
                    text = category.description
                    textSize = 10.5f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    alpha = 0.9f
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
                row.addView(card)
            }
            if (rowCategories.size == 1) {
                row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            }
            host.addView(row)
        }
    }

    private fun loadCategoryArtwork() {
        
        
        val categories = TmdbDiscoveryCategories.all
        Logger.log("TMDB shelf artwork coverage requested=" + categories.size + "/" + TmdbDiscoveryCategories.all.size + " cached=" + categories.count { !artworkCache[it.id].isNullOrBlank() })
        val now = System.currentTimeMillis()
        val pending = categories.filter { category ->
            val failedAt = artworkFailedAt[category.id]
            val failedRecently = failedAt != null && now - failedAt < 60000
            val hasPoster = !artworkCache[category.id].isNullOrBlank()
            !hasPoster && !failedRecently && artworkLoading.add(category.id)
        }
        if (pending.isEmpty()) return
        Logger.log("TMDB shelves artwork: requesting " + pending.size + " category grid previews")
        
        
        pending.forEach { category ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                val url = runCatching { categorySemaphore.withPermit { fetchCategoryGridPoster(category) } }
                    .onFailure { Logger.log("TMDB shelf grid poster failed category=" + category.id + ": " + it.message) }
                    .getOrNull()
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    artworkLoading.remove(category.id)
                    if (!url.isNullOrBlank()) {
                        artworkCache[category.id] = url
                        artworkFailedAt.remove(category.id)
                        DiscoveryArtworkStore.putTmdb(requireContext(), category.id, url)
                        Logger.log("TMDB shelf grid poster resolved category=" + category.id + " elapsedMs=" + (System.currentTimeMillis() - startedAt))
                    } else {
                        artworkFailedAt[category.id] = System.currentTimeMillis()
                        Logger.log("TMDB shelf grid poster missing category=" + category.id + "; category grid queries returned no poster")
                    }
                    updateArtworkViews()
                }
            }
        }
    }

    
    private suspend fun fetchCategoryGridPoster(category: TmdbDiscoveryCategory): String? {
        for (phase in 0..2) {
            for (type in listOf("movie", "tv")) {
                val results = fetchTmdbType(type, category, phase, 1)
                val posterPath = results.firstNotNullOfOrNull { item ->
                    item.posterPath?.takeIf { it.isNotBlank() }
                }
                if (!posterPath.isNullOrBlank()) return Tmdb.imageUrl(posterPath, 342)
            }
        }
        return null
    }

    private fun updateArtworkViews() {
        val host = _binding?.tmdbEditorialShelves ?: return
        for (i in 0 until host.childCount) {
            val row = host.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val card = row.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until card.childCount) {
                    val frame = card.getChildAt(k) as? FrameLayout ?: continue
                    val image = frame.getChildAt(0) as? ImageView ?: continue
                    val id = image.tag as? String ?: continue
                    val url = artworkCache[id]
                    if (!url.isNullOrBlank() && artworkRenderedUrls[id] != url) {
                        image.loadImage(url, dp(82))
                        artworkRenderedUrls[id] = url
                    }
                }
            }
        }
    }

    private fun openCategory(category: TmdbDiscoveryCategory) {
        selectedCategory = category
        backCallback?.isEnabled = true

        shelvesScrollY = binding.tmdbDiscoveryScroll.scrollY
        binding.tmdbDiscoveryScroll.scrollTo(0, 0)
        binding.tmdbDiscoveryGrid.isVisible = true
        binding.tmdbDiscoveryProgress.isVisible = true
        binding.tmdbDiscoverySearchBar.contentDescription = "Search movies and TV"
        resetTmdbCategoryPaging()
        val host = binding.tmdbEditorialShelves
        host.removeAllViews()
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        val back = Chip(requireContext()).apply {
            text = "‹  All discovery shelves"
            isFocusable = true
            setOnClickListener { showCategories() }
            FocusEffectUtil.applyFocusListener(this)
        }
        header.addView(back)
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = category.title
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val gridButton = ImageButton(requireContext()).apply {
            setImageResource(ani.sanin.R.drawable.ic_round_grid_view_24)
            setColorFilter(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            LensButtonBackground.apply(this)
            contentDescription = context.getString(ani.sanin.R.string.grid)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val listButton = ImageButton(requireContext()).apply {
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
        tmdbGridButton = gridButton
        tmdbListButton = listButton
        gridButton.setOnClickListener { applyTmdbCategoryLayout(0) }
        listButton.setOnClickListener { applyTmdbCategoryLayout(1) }
        header.addView(titleRow)
        header.addView(TextView(requireContext()).apply {
            text = category.description
            textSize = 13f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        val typePill = ani.sanin.ui.components.LibraryStatusPill(requireContext())
        val pillParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        pillParams.topMargin = dp(10)
        pillParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        typePill.layoutParams = pillParams
        typePill.isFocusable = true
        typePill.isFocusable = true
        typePill.bind(
            tabs = listOf(
                ani.sanin.ui.components.LibraryStatusTab(label = "All", count = 0),
                ani.sanin.ui.components.LibraryStatusTab(label = "Movie", count = 0),
                ani.sanin.ui.components.LibraryStatusTab(label = "TV show", count = 0),
            ),
            selectedIndex = tmdbTypeFilter,
            onTabSelected = { index -> applyTmdbTypeFilter(index) },
            fillWidth = false,
            compact = true,
        )
        header.addView(typePill)
        host.addView(header)
        if (isTvDevice(requireContext())) back.post { back.requestFocus() }
        applyTmdbCategoryLayout(PrefManager.getCustomVal("discover_category_view", 0))
        
        
        pinTmdbCategoryGrid()
        binding.tmdbDiscoveryScroll.doOnLayout { pinTmdbCategoryGrid() }
        loadTmdbCategoryPage()
    }

    
    
    
    
    
    private fun pinTmdbCategoryGrid() {
        if (_binding == null || selectedCategory == null) return
        val parent = binding.tmdbDiscoveryGrid.parent as android.view.ViewGroup
        val grid = binding.tmdbDiscoveryGrid
        val scrollHost = binding.tmdbDiscoveryScroll
        if (scrollHost.height <= 0) return
        var others = 0
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child !== grid && child.visibility != android.view.View.GONE) others += child.height
        }
        val remaining = scrollHost.height - others
        if (remaining > dp(240)) {
            grid.isNestedScrollingEnabled = false
            if (grid.layoutParams.height != remaining) {
                grid.layoutParams.height = remaining
                grid.requestLayout()
            }
        } else {
            
            grid.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            grid.isNestedScrollingEnabled = true
            grid.requestLayout()
        }
    }

    private fun resetTmdbCategoryPaging() {
        tmdbTypeFilter = 0
        tmdbCategoryItemsAll.clear()
        tmdbCategoryRowCardsAll.clear()
        tmdbCategoryItems.clear()
        tmdbPool.clear()
        tmdbSeen.clear()
        tmdbMoviePage = 1
        tmdbTvPage = 1
        tmdbMoviePhase = 0
        tmdbTvPhase = 0
        tmdbMovieExhausted = false
        tmdbTvExhausted = false
        tmdbMovieSkips = 0
        tmdbTvSkips = 0
        tmdbExhaustedAnnounced = false
        tmdbCategoryLoading = false
        tmdbCategoryEmptyView = null
        tmdbReloadUsed = false
    }

    private fun tmdbAllExhausted(): Boolean =
        tmdbMovieExhausted && tmdbTvExhausted && tmdbPool.isEmpty()

    private fun applyTmdbCategoryLayout(mode: Int) {
        val b = _binding ?: return
        PrefManager.setCustomVal("discover_category_view", mode)
        tmdbGridButton?.alpha = if (mode == 0) 1f else 0.33f
        tmdbListButton?.alpha = if (mode == 1) 1f else 0.33f
        b.tmdbDiscoveryGrid.adapter = if (mode == 1) tmdbListRowAdapter else adapter
        b.tmdbDiscoveryGrid.layoutManager =
            if (mode == 1) {
                LinearLayoutManager(requireContext())
            } else {
                GridLayoutManager(
                    requireContext(),
                    TmdbCards.gridSpan(resources.displayMetrics.widthPixels / resources.displayMetrics.density)
                )
            }
        pinTmdbCategoryGrid()
    }

    private fun loadTmdbCategoryPage() {
        val category = selectedCategory ?: return
        val b = _binding ?: return
        if (tmdbCategoryLoading || tmdbAllExhausted()) return
        tmdbCategoryLoading = true
        val firstLoad = tmdbCategoryItems.isEmpty()
        val want = if (firstLoad) 20 else 30
        b.tmdbDiscoveryProgress.isVisible = firstLoad
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                runLoadTmdbCategoryPage(category, want, firstLoad)
            } finally {
                tmdbCategoryLoading = false
                if (_binding != null && selectedCategory?.id == category.id) {
                    _binding?.tmdbDiscoveryProgress?.isVisible = false
                }
            }
        }
    }

    private suspend fun runLoadTmdbCategoryPage(category: TmdbDiscoveryCategory, want: Int, firstLoad: Boolean) {
        val b = _binding ?: return
        run {
            var emitted = 0
            var failures = 0
            while (emitted < want && !tmdbAllExhausted() && failures < 3 &&
                _binding != null && selectedCategory?.id == category.id
            ) {
                if (tmdbPool.isEmpty()) {
                    val outcome = runCatching { refillTmdbPool(category) }
                    if (outcome.isFailure) {
                        failures++
                        Logger.log("TMDB shelf load failed category=" + category.id + " attempt=" + failures + " error=" + outcome.exceptionOrNull()?.message)
                    } else if (tmdbPool.isEmpty()) {
                        failures++
                        Logger.log("TMDB shelf made no progress category=" + category.id + " attempt=" + failures + " moviePage=" + tmdbMoviePage + " tvPage=" + tmdbTvPage)
                    } else failures = 0
                }
                if (tmdbPool.isEmpty()) {
                    if (tmdbAllExhausted() || failures >= 3) break
                    continue
                }
                val take = minOf(tmdbPool.size, want - emitted)
                val chunk = tmdbPool.take(take)
                repeat(take) { tmdbPool.removeAt(0) }
                if (chunk.isEmpty()) break
                
                val fresh = chunk
                emitted += fresh.size
                Logger.log("TMDB shelf page category=" + category.id + " emitted=" + fresh.size + " total=" + (tmdbCategoryItems.size + fresh.size) + " poolRemaining=" + tmdbPool.size)
                tmdbCategoryItemsAll.addAll(fresh)
                tmdbCategoryItems.addAll(fresh.filter { tmdbTypeMatches(it) })

                
                
                
                val firstShelfPoster = fresh.firstNotNullOfOrNull { item ->
                    item.posterPath?.takeIf { it.isNotBlank() }
                        ?.let { Tmdb.imageUrl(it, 300) }
                }
                if (!firstShelfPoster.isNullOrBlank() && _binding != null) {
                    artworkCache[category.id] = firstShelfPoster
                    artworkFailedAt.remove(category.id)
                    DiscoveryArtworkStore.putTmdb(requireContext(), category.id, firstShelfPoster)
                    updateArtworkViews()
                    Logger.log("TMDB shelf thumbnail sourced from first grid poster category=" + category.id)
                }

                val freshRows = fresh.map { item ->
                    DiscoveryStandardCardAdapter.Card(
                        id = -item.id,
                        title = item.displayTitle,
                        posterUrl = (item.posterPath ?: item.backdropPath)?.let { Tmdb.imageUrl(it, 300) },
                        year = item.year.takeIf { y -> y.isNotBlank() },
                    )
                }
                tmdbCategoryRowCardsAll.addAll(freshRows)
                val itemStart = tmdbCategoryItems.size
                val rowStart = tmdbCategoryRowCards.size
                val accepted = fresh.zip(freshRows).filter { tmdbTypeMatches(it.first) }
                accepted.forEach { tmdbCategoryRowCards.add(it.second) }
                if (accepted.isNotEmpty()) {
                    adapter.notifyItemRangeInserted(itemStart, accepted.size)
                    tmdbListRowAdapter.notifyItemRangeInserted(rowStart, accepted.size)
                }
            }
            if (_binding == null || selectedCategory?.id != category.id) return
            b.tmdbDiscoveryProgress.isVisible = false
            b.tmdbDiscoveryGrid.isVisible = tmdbCategoryItems.isNotEmpty()
            pinTmdbCategoryGrid()
            if (tmdbCategoryItems.isNotEmpty()) {
                tmdbCategoryEmptyView?.let { if (it.parent != null) (it.parent as? android.view.ViewGroup)?.removeView(it) }
                tmdbCategoryEmptyView = null
            }
            if (tmdbAllExhausted() && tmdbCategoryItems.isNotEmpty() && !tmdbExhaustedAnnounced) {
                tmdbExhaustedAnnounced = true
                com.google.android.material.snackbar.Snackbar.make(
                    b.tmdbDiscoveryScroll,
                    "That\'s all for \'" + category.title + "\'",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
            if (tmdbCategoryItems.isEmpty() && (tmdbAllExhausted() || failures >= 3)) {
                val reason = Tmdb.lastError
                val message: String = if (reason != null) "TMDB can" + "\u2019" + "t be reached from your network right now ($reason). Anime shelves still work - try Wi-Fi or another network, then go back and retry."
                else "No reliable matches found for this shelf yet. Try another category."
                showTmdbCategoryEmpty(message)
                if (firstLoad && !tmdbReloadUsed) {
                    tmdbReloadUsed = true
                    b.tmdbDiscoveryScroll.postDelayed({
                        if (_binding != null && selectedCategory?.id == category.id) {
                            resetTmdbCategoryPaging()
                            loadTmdbCategoryPage()
                        }
                    }, 6000)
                }
            }
            tmdbCategoryLoading = false
            if (emitted > 0 && !tmdbAllExhausted() && tmdbCategoryItemsAll.size < 160) {
                
                
                b.tmdbDiscoveryScroll.postDelayed({
                    if (_binding != null && selectedCategory?.id == category.id && !tmdbAllExhausted()) {
                        loadTmdbCategoryPage()
                    }
                }, 3000)
            }
        }
    }

    private fun tmdbTypeMatches(item: TmdbMedia): Boolean =
        when (tmdbTypeFilter) {
            1 -> item.type == "movie"
            2 -> item.type != "movie"
            else -> true
        }

    private fun applyTmdbTypeFilter(filter: Int) {
        if (filter == tmdbTypeFilter) return
        tmdbTypeFilter = filter
        tmdbCategoryItems.clear()
        tmdbCategoryRowCards.clear()
        tmdbCategoryItemsAll.zip(tmdbCategoryRowCardsAll).forEach { (item, row) ->
            if (tmdbTypeMatches(item)) {
                tmdbCategoryItems.add(item)
                tmdbCategoryRowCards.add(row)
            }
        }
        adapter.notifyDataSetChanged()
        tmdbListRowAdapter.notifyDataSetChanged()
    }

    private suspend fun refillTmdbPool(category: TmdbDiscoveryCategory) {
        if (tmdbMoviePhase == 0 && tmdbMoviePage == 1 && resolveKeyword(category) == null) tmdbMoviePhase = 1
        if (tmdbTvPhase == 0 && tmdbTvPage == 1 && resolveKeyword(category) == null) tmdbTvPhase = 1
        if (!tmdbMovieExhausted) refillMovieLane(category)
        if (!tmdbTvExhausted) refillTvLane(category)
    }

    private suspend fun refillMovieLane(category: TmdbDiscoveryCategory) {
        
        
        
        repeat(3) { attempt ->
            val outcome = runCatching { fetchTmdbType("movie", category, tmdbMoviePhase, tmdbMoviePage) }
            val movie = outcome.getOrNull()
            if (movie != null && Tmdb.lastError == null) {
                if (movie.isEmpty()) {
                    if (category.adult) tmdbMovieExhausted = true else advanceTmdbPhase(movie = true)
                } else {
                    tmdbMovieSkips = 0
                    addToTmdbPool(category, movie)
                    tmdbMoviePage++
                }
                return
            }
            delay(900L * (attempt + 1))
        }
        tmdbMoviePage++; tmdbMovieSkips++
        if (tmdbMovieSkips >= 4) {
            tmdbMovieExhausted = true
            Logger.log("TMDB shelf movie lane exhausted after " + tmdbMovieSkips + " bad pages category=" + category.id)
        } else {
            Logger.log("TMDB shelf movie page skipped category=" + category.id)
        }
    }

    private suspend fun refillTvLane(category: TmdbDiscoveryCategory) {
        
        repeat(3) { attempt ->
            val outcome = runCatching { fetchTmdbType("tv", category, tmdbTvPhase, tmdbTvPage) }
            val tv = outcome.getOrNull()
            if (tv != null && Tmdb.lastError == null) {
                if (tv.isEmpty()) {
                    if (category.adult) tmdbTvExhausted = true else advanceTmdbPhase(movie = false)
                } else {
                    tmdbTvSkips = 0
                    addToTmdbPool(category, tv)
                    tmdbTvPage++
                }
                return
            }
            delay(900L * (attempt + 1))
        }
        tmdbTvPage++; tmdbTvSkips++
        if (tmdbTvSkips >= 4) {
            tmdbTvExhausted = true
            Logger.log("TMDB shelf tv lane exhausted after " + tmdbTvSkips + " bad pages category=" + category.id)
        } else {
            Logger.log("TMDB shelf tv page skipped category=" + category.id)
        }
    }

    private fun advanceTmdbPhase(movie: Boolean) {
        if (movie) {
            if (tmdbMoviePhase < 2) {
                tmdbMoviePhase++
                tmdbMoviePage = 1
            } else {
                tmdbMovieExhausted = true
            }
        } else {
            if (tmdbTvPhase < 2) {
                tmdbTvPhase++
                tmdbTvPage = 1
            } else {
                tmdbTvExhausted = true
            }
        }
    }

    private fun addToTmdbPool(category: TmdbDiscoveryCategory, list: List<TmdbMedia>) {
        var received = list.size
        val accepted = list.filter { category.adult || !it.adult }
        var added = 0
        accepted.forEach { if (tmdbSeen.add(it.type to it.id)) { tmdbPool.add(it); added++ } }
        val rejected = received - accepted.size
        Logger.log("TMDB shelf pool received=" + received + " adultFlagged=" + rejected + " added=" + added + " duplicates=" + (accepted.size - added) + " queued=" + tmdbPool.size)
    }

    private suspend fun fetchTmdbType(
        mediaType: String,
        category: TmdbDiscoveryCategory,
        phase: Int,
        page: Int,
    ): List<TmdbMedia> {
        val keyword = if (phase == 0) resolveKeyword(category)?.toString() else null
        val useGenres = phase <= 1
        val sort = if (phase == 0 && (category.id == "hidden-gems" || category.id == "underrated-masterpieces")) {
            "vote_average.desc"
        } else {
            "popularity.desc"
        }
        val adult = category.adult || PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery)
        val extra = mutableListOf<Pair<String, String>>("include_adult" to if (adult) "true" else "false")
        category.originalLanguage?.let { extra.add("with_original_language" to it) }
        if (phase == 0 && (category.id == "hidden-gems" || category.id == "underrated-masterpieces")) {
            extra.add("vote_count.gte" to "250")
            extra.add("vote_average.gte" to "7.0")
            if (mediaType == "movie") extra.add("release_date.lte" to todayIso())
            else extra.add("first_air_date.lte" to todayIso())
        }
        val genres: String? = if (useGenres && category.genres != null) {
            if (mediaType == "movie") {
                category.genres
            } else {
                category.genres?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                    ?.map { id ->
                        when (id) {
                            28, 12 -> 10759
                            14, 878 -> 10765
                            else -> id
                        }
                    }?.distinct()?.joinToString(",")
            }
        } else {
            null
        }
        return Tmdb.discover(
            mediaType = mediaType,
            genres = genres,
            sort = sort,
            keywords = keyword,
            page = page,
            extra = extra
        )
    }

    private fun showTmdbCategoryEmpty(message: String) {
        val host = _binding?.tmdbEditorialShelves ?: return
        if (tmdbCategoryEmptyView?.parent == null) tmdbCategoryEmptyView = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        tmdbCategoryEmptyView?.let { view ->
            view.text = message
            if (view.parent == null) host.addView(view)
        }
    }

    private fun showCategories() {
        selectedCategory = null
        resetTmdbCategoryPaging()
        backCallback?.isEnabled = false
        binding.tmdbDiscoveryProgress.isVisible = false
        binding.tmdbDiscoveryGrid.isVisible = false
        binding.tmdbDiscoveryGrid.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.tmdbDiscoveryGrid.isNestedScrollingEnabled = true
        tmdbCategoryItems.clear()
        tmdbCategoryRowCards.clear()
        tmdbCategoryItemsAll.clear()
        tmdbCategoryRowCardsAll.clear()
        adapter.notifyDataSetChanged()
        tmdbListRowAdapter.notifyDataSetChanged()
        renderCategories()

        val target = shelvesScrollY
        binding.tmdbDiscoveryScroll.post { if (_binding != null) binding.tmdbDiscoveryScroll.scrollTo(0, target) }
        loadCategoryArtwork()
    }

    private suspend fun fetchArtworkPoster(category: TmdbDiscoveryCategory): String? {
        val keyword = resolveKeyword(category)?.toString()
        val adult = category.adult || PrefManager.getVal<Boolean>(PrefName.IncludeAdultDiscovery)
        val extra = mutableListOf<Pair<String, String>>("include_adult" to if (adult) "true" else "false")
        category.originalLanguage?.let { extra.add("with_original_language" to it) }
        suspend fun poster(type: String): String? = Tmdb.discover(
            mediaType = type,
            genres = if (keyword == null) category.genres else null,
            sort = "popularity.desc",
            keywords = keyword,
            page = 1,
            extra = extra
        ).firstOrNull { !it.posterPath.isNullOrBlank() }
            ?.posterPath
            ?.let { Tmdb.imageUrl(it, 342) }
        val categoryMatch = poster("movie") ?: poster("tv")
        if (!categoryMatch.isNullOrBlank()) return categoryMatch
        
        
        val fallback = runCatching {
            fallbackArtworkMutex.withLock {
                if (tmdbFallbackArtwork.isEmpty()) {
                    tmdbFallbackArtwork = Tmdb.trending("all", "week")
                        .filter { it.mediaType != "person" }
                        .mapNotNull { it.posterPath?.let { path -> Tmdb.imageUrl(path, 342) } }
                    Logger.log("TMDB shelf fallback pool loaded posters=" + tmdbFallbackArtwork.size)
                }
                if (tmdbFallbackArtwork.isEmpty()) null
                else tmdbFallbackArtwork[Math.floorMod(category.id.hashCode(), tmdbFallbackArtwork.size)]
            }
        }.onFailure {
            Logger.log("TMDB shelf thumbnail fallback failed category=" + category.id + ": " + it.message)
        }.getOrNull()
        if (fallback.isNullOrBlank()) Logger.log("TMDB shelf thumbnail fallback returned no artwork category=" + category.id)
        else Logger.log("TMDB shelf thumbnail using shared fallback category=" + category.id)
        return fallback
    }

    private suspend fun resolveKeyword(category: TmdbDiscoveryCategory): String? {
        val query = category.keyword?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = query.lowercase(Locale.ROOT)
        if (keywordCache.containsKey(key)) return keywordCache[key]
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val resolvedIds = parts.map { part ->
            runCatching {
                val matches = Tmdb.searchKeywords(part)
                (matches.firstOrNull { normalize(it.name) == normalize(part) }
                    ?: matches.firstOrNull {
                        normalize(it.name).contains(normalize(part)) ||
                            normalize(part).contains(normalize(it.name))
                    })?.id
            }.getOrNull().let { it?.toString() }
        }.filterNotNull().joinToString("|")
        val resolved: String? = resolvedIds.takeIf { it.isNotEmpty() }
        keywordCache[key] = resolved
        return resolved
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.tmdbDiscoveryGrid?.let { TmdbCards.applyGridSpan(it) }
        if (_binding != null && selectedCategory == null) renderCategories()
    }

    override fun onDestroyView() {
        backCallback?.isEnabled = false
        backCallback = null
        _binding = null
        super.onDestroyView()
    }

    class TmdbGridAdapter(
        private val items: MutableList<TmdbMedia>,
        private val onOpen: (TmdbMedia) -> Unit,
    ) : RecyclerView.Adapter<TmdbGridAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val rv = holder.itemView.parent as? RecyclerView
            val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
            TmdbCards.applyCardStyle(holder.binding, item, cell)
            if (rv != null) holder.binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = TmdbCards.gridRowGapPx(rv)
            }
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.root.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.itemView, holder.binding.tmdbCard)
        }
        override fun getItemCount(): Int = items.size
        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.root.isFocusable = true
                binding.root.isFocusableInTouchMode = false
            }
        }
    }
}
