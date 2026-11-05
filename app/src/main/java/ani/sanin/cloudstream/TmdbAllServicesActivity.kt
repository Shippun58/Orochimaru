package ani.sanin.cloudstream

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.databinding.ActivityTmdbAllServicesBinding
import ani.sanin.getThemeColor
import ani.sanin.hideSystemBarsExtendView
import ani.sanin.initActivity
import ani.sanin.isTvDevice
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class TmdbAllServicesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTmdbAllServicesBinding
    private lateinit var scope: CoroutineScope

    private companion object {

        const val GRID_SIDE_PADDING_DP = 32f

        const val GRID_MAX_EXTENT_DP = 150f

        const val GRID_CROSS_SPACING_DP = 12f
        const val GRID_CROSS_SPACING_DP_TV = 16f

        const val GRID_MAIN_SPACING_DP = 14f
        const val GRID_MAIN_SPACING_DP_TV = 16f

        const val GRID_TILE_RATIO_W = 128f
        const val GRID_TILE_RATIO_H = 92f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbAllServicesBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.allServicesTitle.setTextColor(
            getThemeColor(com.google.android.material.R.attr.colorPrimary)
        )

        LensButtonBackground.apply(binding.mediaList)
        LensButtonBackground.apply(binding.mediaGrid)
        FocusEffectUtil.applyFocusListener(binding.mediaList, binding.mediaGrid)

        binding.allServicesTitle.setSafeOnClickListener { finish() }

        scope = CoroutineScope(Dispatchers.IO)
        setUpGrid()
        load()
    }

    private fun setUpGrid() {

        binding.allServicesRecyclerView.adapter = StreamingServicesAdapter(
            isTv = isTvDevice(this),
            onServiceClick = { provider -> openCatalogue(provider) },
            onSeeAll = { },
            showSeeAll = false,
            limit = Int.MAX_VALUE
        )
        val mode = PrefManager.getCustomVal("mediaView", 0)
        fun changeView(newMode: Int) {
            binding.mediaList.alpha = if (newMode == 1) 1f else 0.33f
            binding.mediaGrid.alpha = if (newMode == 1) 0.33f else 1f
            PrefManager.setCustomVal("mediaView", newMode)
            applyGrid(isGrid = newMode == 0)
        }
        binding.mediaList.setOnClickListener { changeView(1) }
        binding.mediaGrid.setOnClickListener { changeView(0) }
        changeView(if (mode == 1) 1 else 0)
    }

    private fun applyGrid(isGrid: Boolean) {
        val rv = binding.allServicesRecyclerView
        val adapter = rv.adapter as? StreamingServicesAdapter
        if (!isGrid) {
            adapter?.setGridTileSize(0, 0, 0, 0)
            rv.layoutManager = GridLayoutManager(this, 1)
            return
        }
        val tv = isTvDevice(this)
        val span = gridSpanCount()
        rv.layoutManager = GridLayoutManager(this, span)

        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val cross = dp(if (tv) GRID_CROSS_SPACING_DP_TV else GRID_CROSS_SPACING_DP)
        val main = dp(if (tv) GRID_MAIN_SPACING_DP_TV else GRID_MAIN_SPACING_DP)
        val sidePadding = dp(GRID_SIDE_PADDING_DP)
        val availablePx = resources.displayMetrics.widthPixels - sidePadding
        val cellPx = availablePx / span
        val tileWidthPx = cellPx - cross
        val tileHeightPx =
            (tileWidthPx * GRID_TILE_RATIO_H / GRID_TILE_RATIO_W).toInt()
        adapter?.setGridTileSize(tileWidthPx, tileHeightPx, cross, main)
    }

    private fun gridSpanCount(): Int {
        if (isTvDevice(this)) return 6
        val screenWidthDp = resources.displayMetrics.run { widthPixels / density }
        val availableDp = screenWidthDp - GRID_SIDE_PADDING_DP
        val columnDp = GRID_MAX_EXTENT_DP + GRID_CROSS_SPACING_DP
        return ceil(availableDp / columnDp).toInt().coerceAtLeast(2)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGrid(isGrid = PrefManager.getCustomVal("mediaView", 0) == 0)
    }

    private fun load() {
        scope.launch {
            val result = runCatching { Tmdb.watchProviders() }
            withContext(Dispatchers.Main) {
                result.onSuccess { providers ->
                    (binding.allServicesRecyclerView.adapter as? StreamingServicesAdapter)
                        ?.submit(providers)
                }.onFailure {
                    Snackbar.make(binding.root, R.string.error, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openCatalogue(provider: TmdbProvider) {
        startActivity(
            Intent(this, TmdbServiceCatalogueActivity::class.java)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_ID, provider.id)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_NAME, provider.displayName)
                .putExtra(TmdbServiceCatalogueActivity.ARG_MEDIA_TYPE, "movie")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
