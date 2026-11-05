package ani.sanin.media

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.databinding.ActivityMediaListViewBinding
import ani.sanin.getThemeColor
import ani.sanin.hideSystemBarsExtendView
import ani.sanin.home.toExploreMedia
import ani.sanin.initActivity
import ani.sanin.others.getSerialized
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MediaListViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaListViewBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
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
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        val mediaList =
            passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
        if (passedMedia != null) passedMedia = null
        val view = PrefManager.getCustomVal("mediaView", 0)
        var mediaView: View = when (view) {
            1 -> binding.mediaList
            0 -> binding.mediaGrid
            else -> binding.mediaGrid
        }
        mediaView.alpha = 1f
        fun changeView(mode: Int, current: View) {
            mediaView.alpha = 0.33f
            mediaView = current
            current.alpha = 1f
            PrefManager.setCustomVal("mediaView", mode)
            binding.mediaRecyclerView.adapter = MediaAdaptor(mode, mediaList, this)
            binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                this,
                if (mode == 1) 1 else (screenWidth / 120f).toInt()
            )
        }
        binding.mediaList.setOnClickListener {
            changeView(1, binding.mediaList)
        }
        binding.mediaGrid.setOnClickListener {
            changeView(0, binding.mediaGrid)
        }
        LensButtonBackground.apply(binding.mediaList)
        LensButtonBackground.apply(binding.mediaGrid)
        FocusEffectUtil.applyFocusListener(binding.mediaList, binding.mediaGrid)
        val text = "${intent.getStringExtra("title")} (${mediaList.count()})"
        binding.listTitle.text = text
        binding.mediaRecyclerView.adapter = MediaAdaptor(view, mediaList, this)
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(
            this,
            if (view == 1) 1 else (screenWidth / 120f).toInt()
        )

        val exploreRow = passedExploreRow
        val exploreType = passedExploreType
        passedExploreRow = null
        passedExploreType = null
        if (exploreRow != null && exploreType != null) {
            var nextPage = 2
            var loading = false
            var done = false
            binding.mediaRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (loading || done || mediaList.size >= 60) return
                    val lastVisible =
                        (recyclerView.layoutManager as? GridLayoutManager)?.findLastVisibleItemPosition()
                            ?: return
                    if (lastVisible < mediaList.size - 8) return
                    loading = true
                    lifecycleScope.launch {
                        val more = runCatching {
                            Tmdb.exploreRow(exploreRow, exploreType, nextPage++)
                        }.getOrDefault(emptyList())
                        if (more.isNotEmpty()) {
                            val start = mediaList.size
                            mediaList.addAll(more.map { it.toExploreMedia() })
                            recyclerView.adapter?.notifyItemRangeInserted(start, more.size)
                            binding.listTitle.text =
                                "${intent.getStringExtra("title")} (${mediaList.size})"
                        } else {
                            done = true
                        }
                        loading = false
                    }
                }
            })
        }
    }

    companion object {
        var passedMedia: ArrayList<Media>? = null
        var passedExploreRow: Tmdb.ExploreRow? = null
        var passedExploreType: String? = null
    }
}
