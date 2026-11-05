package ani.sanin.cloudstream

import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ActivityTmdbServiceCatalogueBinding
import ani.sanin.getThemeColor
import ani.sanin.hideSystemBarsExtendView
import ani.sanin.initActivity
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbServiceCatalogueActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTmdbServiceCatalogueBinding
    private lateinit var mediaAdaptor: MediaAdaptor
    private lateinit var scope: CoroutineScope

    private val media = ArrayList<Media>()
    private var providerId = 0
    private var providerName = ""
    private var mediaType = TYPE_MOVIE
    private var page = 0
    private var loading = false

    private var pillTabs by mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var selectedPillIndex by mutableIntStateOf(0)

    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityTmdbServiceCatalogueBinding.inflate(layoutInflater)
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)

        scope = CoroutineScope(Dispatchers.IO)

        providerId = intent.getIntExtra(ARG_PROVIDER_ID, 0)
        providerName = intent.getStringExtra(ARG_PROVIDER_NAME).orEmpty()
        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE)?.takeIf { it == TYPE_TV } ?: TYPE_MOVIE

        setUpTypeToggle()
        setUpGrid()
        updateTitle()
        load()
    }

    private fun setUpTypeToggle() {
        selectedPillIndex = if (mediaType == TYPE_TV) 1 else 0
        pillTabs = listOf(
            LibraryStatusTab(label = "Movie", count = 0),
            LibraryStatusTab(label = "TV show", count = 0),
        )
        FocusEffectUtil.applyFocusListener(binding.mediaList)
        FocusEffectUtil.applyFocusListener(binding.mediaGrid)
        LensButtonBackground.apply(binding.mediaList)
        LensButtonBackground.apply(binding.mediaGrid)
        binding.catalogueTypePill.bind(
            tabs = pillTabs,
            selectedIndex = selectedPillIndex,
            onTabSelected = { index -> selectType(index) },
            fillWidth = false,
            compact = false,
        )
    }

    private fun selectType(index: Int) {
        if (index == selectedPillIndex) return
        selectedPillIndex = index
        mediaType = if (index == 1) TYPE_TV else TYPE_MOVIE

        page = 0
        hasMore = true
        if (media.isNotEmpty()) {
            val old = media.size
            media.clear()
            mediaAdaptor.notifyItemRangeRemoved(0, old)
        }
        updateTitle()
        load()
    }

    private fun setUpGrid() {
        val mode = PrefManager.getCustomVal("mediaView", 0)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        fun changeView(newMode: Int) {
            binding.mediaList.alpha = if (newMode == 1) 1f else 0.33f
            binding.mediaGrid.alpha = if (newMode == 1) 0.33f else 1f
            PrefManager.setCustomVal("mediaView", newMode)
            mediaAdaptor = MediaAdaptor(newMode, media, this)
            binding.mediaRecyclerView.adapter = mediaAdaptor
            binding.mediaRecyclerView.layoutManager =
                GridLayoutManager(this, if (newMode == 1) 1 else (screenWidth / 120f).toInt())
        }
        binding.mediaList.setOnClickListener { changeView(1) }
        binding.mediaGrid.setOnClickListener { changeView(0) }
        changeView(if (mode == 1) 1 else 0)

        binding.mediaRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - PREFETCH_DISTANCE) {
                    load()
                }
            }
        })
    }

    private fun updateTitle() {
        binding.listTitle.text = "$providerName (${media.size})"
    }

    private fun load() {
        if (loading || !hasMore || providerId <= 0) return
        loading = true
        val next = page + 1
        scope.launch {
            val result = runCatching {
                Tmdb.providerTitles(providerId, mediaType, next)
            }
            withContext(Dispatchers.Main) {
                loading = false
                result.onSuccess { results ->
                    page = next
                    hasMore = results.isNotEmpty()
                    val start = media.size
                    for (item in results) {
                        media.add(item.toGridMedia())
                    }

                    if (results.isNotEmpty()) {
                        mediaAdaptor.notifyItemRangeInserted(start, results.size)
                    }
                    updateTitle()
                    if (results.isEmpty() && media.isEmpty()) {
                        Snackbar.make(binding.root, R.string.nothing_here, Snackbar.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    hasMore = false
                    Snackbar.make(binding.root, R.string.error, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mediaAdaptor.refreshCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun TmdbMedia.toGridMedia(): Media = Media(
        id = id,
        name = displayTitle,
        nameRomaji = displayTitle,
        userPreferredName = displayTitle,
        isAdult = false,
        banner = Tmdb.imageUrl(backdropPath, 1280) ?: Tmdb.imageUrl(posterPath, 780),
        cover = Tmdb.imageUrl(posterPath, 500),
        description = overview,

        meanScore = if (voteAverage > 0) (voteAverage * 10).toInt() else null,
        tmdbType = type
    )

    companion object {
        const val ARG_PROVIDER_ID = "providerId"
        const val ARG_PROVIDER_NAME = "providerName"
        const val ARG_MEDIA_TYPE = "mediaType"

        private const val TYPE_MOVIE = "movie"
        private const val TYPE_TV = "tv"

        private const val PREFETCH_DISTANCE = 8
    }
}
