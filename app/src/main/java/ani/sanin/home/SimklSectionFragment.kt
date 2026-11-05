package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.SimklWatchCache
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext

class SimklSectionFragment : Fragment() {

    private var items: List<Simkl.SimklWatchedItem> = emptyList()
    private var originalItems: List<Simkl.SimklWatchedItem> = emptyList()
    private var recyclerView: RecyclerView? = null

    var onExitUp: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            @Suppress("UNCHECKED_CAST")
            items = (it.getSerializable(ARG_ITEMS_LIST) as? ArrayList<Simkl.SimklWatchedItem>) ?: emptyList()
        }
        originalItems = items
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return RecyclerView(requireContext()).apply {
            val dm = resources.displayMetrics

            val cols = TmdbCards.gridSpan(dm.widthPixels / dm.density)
            layoutManager = GridLayoutManager(requireContext(), cols)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(16, 8, 16, 16)
            recyclerView = this
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                dpadUpToHeader()
            } else {
                false
            }
        }
        updateAdapter(items)
    }

    fun focusFirstCard(): Boolean {
        val rv = recyclerView ?: return false
        rv.stopScroll()
        rv.scrollToPosition(0)
        val holder = rv.findViewHolderForAdapterPosition(0)
        val poster = holder?.itemView?.findViewById<View>(R.id.tmdbCardPoster)
        if (poster != null && poster.requestFocus()) return true
        if (holder != null && holder.itemView.requestFocus()) return true
        rv.post {
            val h = rv.findViewHolderForAdapterPosition(0)
            val p = h?.itemView?.findViewById<View>(R.id.tmdbCardPoster)
            if (p != null) p.requestFocus() else h?.itemView?.requestFocus()
        }
        return true
    }

    private fun dpadUpToHeader(): Boolean {
        val rv = recyclerView ?: return false
        val focused = rv.findFocus() ?: return false
        val holder = rv.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val span = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        if (position >= span) return false
        return onExitUp?.invoke() ?: false
    }

    fun filter(query: String) {
        if (query.isBlank()) {
            items = originalItems
        } else {
            items = originalItems.filter { item ->
                item.title?.contains(query, ignoreCase = true) == true ||
                item.year?.toString()?.contains(query) == true
            }
        }
        updateAdapter(items)
    }

    fun sort(by: String) {
        items = when (by) {
            "title" -> items.sortedBy { it.title?.lowercase() }
            "year" -> items.sortedByDescending { it.year ?: 0 }
            "updated" -> items.sortedByDescending { it.lastWatchedAt ?: "" }
            else -> items
        }
        updateAdapter(items)
    }

    private fun updateAdapter(list: List<Simkl.SimklWatchedItem>) {
        if (!isAdded) return
        recyclerView?.adapter = if (list.isEmpty()) null else SimklGridAdapter(list, viewLifecycleOwner.lifecycleScope) { item ->
            val tmdbId = item.ids?.tmdb ?: return@SimklGridAdapter
            val mediaType = item.mediaType ?: "tv"
            startActivity(
                Intent(requireContext(), TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, tmdbId)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        recyclerView?.let { TmdbCards.applyGridSpan(it) }
    }

    class SimklGridAdapter(
        private val items: List<Simkl.SimklWatchedItem>,
        private val scope: CoroutineScope,
        private val onClick: (Simkl.SimklWatchedItem) -> Unit
    ) : RecyclerView.Adapter<SimklGridAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b, scope)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val (baseW, baseH) = if (landscape) 260f to 148f else 102f to 154f

            val rv = holder.itemView.parent as? RecyclerView
            val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else 0
            val w = if (cell > 0) cell else (baseW * size).toInt()
            val h = if (cell > 0) (cell * baseH / baseW).toInt() else (baseH * size).toInt()
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }

            (holder.itemView.parent as? RecyclerView)?.let { rv ->
                b.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = TmdbCards.gridRowGapPx(rv)
                }
            }
            b.tmdbCard.radius = TmdbCards.roundness()

            b.tmdbCardTitle.isVisible = true
            b.tmdbCardTitle.text = item.title
            b.tmdbCardYear.text = item.year?.toString() ?: ""
            b.tmdbCardYear.isVisible = item.year != null

            val rating = b.tmdbCardRating
            val ratingText = b.tmdbCardRatingText
            val userRating = item.userRating
            if (userRating != null && userRating > 0) {
                rating.isVisible = true
                ratingText.text = String.format("%.1f", userRating.toDouble())
            } else {
                rating.isVisible = false

                val tmdbId = item.ids?.tmdb
                val mt = item.mediaType ?: "tv"
                if (tmdbId != null && tmdbId > 0) {
                    holder.scope?.launch(Dispatchers.IO) {
                        val vote = runCatching {
                            ani.sanin.connections.tmdb.Tmdb.detail(mt, tmdbId)?.voteAverage
                        }.getOrNull()
                        if (vote != null && vote > 0) {
                            withContext(Dispatchers.Main) {
                                if (holder.adapterPosition == position) {
                                    rating.isVisible = true
                                    ratingText.text = String.format("%.1f", vote)
                                }
                            }
                        }
                    }
                }
            }

            if (landscape) {
                b.tmdbCardGradient.isVisible = true
                setGradient(b.tmdbCardGradient)
                b.tmdbCardOverlayTitle.isVisible = true
                b.tmdbCardOverlayTitle.text = item.title
                b.tmdbCardLogo.isVisible = false
            } else {
                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
            }

            val tmdbId = item.ids?.tmdb
            val mediaType = item.mediaType ?: "tv"
            if (tmdbId != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val detail = Tmdb.detail(mediaType, tmdbId)
                    val backdropUrl = detail?.backdropPath?.let { Tmdb.imageUrl(it, 780) }
                    val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) Tmdb.logoUrl(mediaType, tmdbId) else null

                    withContext(Dispatchers.Main) {
                        if (!holder.bindingEquals(item)) return@withContext

                        val imageUrl = if (landscape) {
                            backdropUrl ?: Simkl.imageUrl(item.poster, "w")
                        } else {
                            Simkl.imageUrl(item.poster, "m")
                        }
                        b.tmdbCardPoster.loadImage(imageUrl)

                        if (landscape && !logoUrl.isNullOrBlank()) {
                            b.tmdbCardLogo.isVisible = true
                            val logoW = (b.tmdbCardPoster.layoutParams.width * 0.7f).toInt().coerceIn(60, 180)
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

                val imageUrl = if (landscape) {
                    Simkl.imageUrl(item.poster, "w")
                } else {
                    Simkl.imageUrl(item.poster, "m")
                }
                b.tmdbCardPoster.loadImage(imageUrl)
            }

            val progressBadge = b.root.findViewById<View>(R.id.progressBadge)
            val wantProgress = PrefManager.getVal<Int>(PrefName.CardMetadataBottom) == 2 &&
                !(landscape && true)
            if (!wantProgress) {
                progressBadge?.isVisible = false
            } else if (progressBadge != null) {
                val isMovie = item.mediaType == "movie"
                progressBadge.isVisible = true
                val watchedIcon = progressBadge.findViewById<View>(R.id.progressWatchedIcon)
                val watchedCount = progressBadge.findViewById<TextView>(R.id.progressWatchedCount)
                val releasedIcon = progressBadge.findViewById<View>(R.id.progressReleasedIcon)
                val releasedCount = progressBadge.findViewById<TextView>(R.id.progressReleasedCount)
                val midDivider = progressBadge.findViewById<View>(R.id.progressDividerMid)
                val ttDivider = progressBadge.findViewById<View>(R.id.progressDividerTT)
                val ttText = progressBadge.findViewById<TextView>(R.id.progressTT)
                progressBadge.tag = "${item.mediaType}:${item.ids?.tmdb}"

                if (isMovie) {
                    watchedIcon.isVisible = false
                    watchedCount.text = "~"
                    releasedIcon.isVisible = false
                    releasedCount.isVisible = true
                    releasedCount.text = "1"
                    midDivider.isVisible = true
                    ttDivider.isVisible = false
                    ttText.isVisible = false
                    val tmdbId = item.ids?.tmdb
                    if (tmdbId != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val watched = SimklWatchCache.watched("movie", tmdbId) ?: 0
                            withContext(Dispatchers.Main) {
                                if (progressBadge.tag != "${item.mediaType}:${item.ids?.tmdb}") return@withContext
                                if (watched > 0) {
                                    watchedIcon.isVisible = true
                                    watchedCount.text = "1"
                                }
                            }
                        }
                    }
                } else {

                    watchedIcon.isVisible = false
                    watchedCount.text = "~"
                    releasedIcon.isVisible = false
                    releasedCount.isVisible = false
                    midDivider.isVisible = false
                    ttDivider.isVisible = false
                    ttText.isVisible = false
                    val tmdbId = item.ids?.tmdb
                    if (tmdbId != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val detail = runCatching { Tmdb.detail("tv", tmdbId) }.getOrNull()
                            val simklWatched = SimklWatchCache.watched("tv", tmdbId)
                            withContext(Dispatchers.Main) {
                                if (progressBadge.tag != "${item.mediaType}:${item.ids?.tmdb}") return@withContext
                                val hasWatched = simklWatched != null && simklWatched > 0
                                watchedIcon.isVisible = hasWatched
                                if (hasWatched) watchedCount.text = simklWatched.toString()
                                val released = detail?.numberOfEpisodes
                                val hasReleased = released != null && released > 0
                                releasedIcon.isVisible = hasReleased && !hasWatched
                                releasedCount.isVisible = hasReleased
                                if (hasReleased) releasedCount.text = released.toString()
                                var hasTT = false
                                val nextAirDate = detail?.nextEpisodeToAir?.airDate
                                if (!nextAirDate.isNullOrBlank()) {
                                    runCatching {
                                        val airDate = java.time.LocalDate.parse(nextAirDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), airDate)
                                        if (days > 0) {
                                            hasTT = true
                                            ttText.text = "${days}d"
                                            ttText.isVisible = true
                                            ttDivider.isVisible = hasReleased
                                        }
                                    }
                                }
                                midDivider.isVisible = hasReleased || hasTT
                            }
                        }
                    }
                }
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount() = items.size

        private fun setGradient(view: View) {
            val intensity = PrefManager.getVal<Float>(PrefName.CardGradientIntensity)
            if (intensity <= 0f) {
                view.background = null
                return
            }
            val endAlpha = 255
            val startColor = Color.argb(0, 0, 0, 0)
            val endColor = Color.argb(
                (endAlpha * intensity).toInt().coerceIn(0, 255),
                0, 0, 0
            )
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(endColor, startColor)
            )
            view.background = gradient
        }

        private fun VH.bindingEquals(item: Simkl.SimklWatchedItem): Boolean {
            return binding.tmdbCardTitle.text == item.title
        }

        class VH(val binding: ItemTmdbCardBinding, val scope: CoroutineScope) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val ARG_ITEMS_LIST = "items_list"

        fun newInstance(items: List<Simkl.SimklWatchedItem>): SimklSectionFragment {
            return SimklSectionFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ITEMS_LIST, ArrayList(items))
                }
            }
        }
    }
}
