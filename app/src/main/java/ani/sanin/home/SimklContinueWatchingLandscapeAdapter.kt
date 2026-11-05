package ani.sanin.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimklContinueWatchingLandscapeAdapter(
    private val items: List<Simkl.SimklWatchedItem>,
    private val onItemClick: (Simkl.SimklWatchedItem) -> Unit
) : RecyclerView.Adapter<SimklContinueWatchingLandscapeAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_landscape, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.card.radius =
            PrefManager.getVal<Int>(PrefName.ContinueWatchingCardRoundness).toFloat()

        holder.clearlogo.visibility = View.GONE
        holder.overlayTitle.visibility = View.GONE
        holder.title.isVisible = false
        val epStr = item.lastWatched

        val tmdbId = item.ids?.tmdb
        val mediaType = item.mediaType ?: "tv"
        if (tmdbId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val detail = Tmdb.detail(mediaType, tmdbId)
                val backdropUrl = detail?.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
                val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) Tmdb.logoUrl(mediaType, tmdbId) else null

                val isOngoing = when (detail?.status?.lowercase()) {
                    "returning series", "returning", "in production" -> true
                    else -> false
                }
                withContext(Dispatchers.Main) {
                    holder.episodeBroadcast.isVisible = isOngoing && epStr?.isNotBlank() == true
                    if (mediaType == "tv") {
                        val watched = item.totalWatched
                        val total = detail?.numberOfEpisodes?.takeIf { it > 0 }
                            ?: item.totalEpisodes.takeIf { it > 0 } ?: 0
                        if (total > 0) {
                            holder.progress.max = total
                            holder.progress.progress = watched.coerceIn(0, total)
                            holder.progress.visibility = View.VISIBLE
                        }
                    }
                    val imageUrl = backdropUrl ?: Simkl.imageUrl(item.poster)
                    if (!imageUrl.isNullOrBlank()) {
                        holder.image.loadImage(imageUrl)
                    } else {
                        holder.image.setImageResource(R.drawable.ic_round_person_24)
                    }

                    if (!logoUrl.isNullOrBlank()) {
                        holder.clearlogo.visibility = View.VISIBLE

                        val logoW = (holder.image.layoutParams.width * 0.7f).toInt().coerceIn(80, 200)
                        com.bumptech.glide.Glide.with(holder.clearlogo.context)
                            .load(logoUrl)
                            .override(logoW, (logoW * 0.4f).toInt())
                            .into(holder.clearlogo)
                        holder.overlayTitle.visibility = View.GONE
                        holder.title.isVisible = false
                    } else {
                        holder.clearlogo.visibility = View.GONE
                        holder.overlayTitle.visibility = View.VISIBLE
                        holder.title.text = item.title ?: ""
                        holder.title.isVisible = true
                    }
                }
            }
        } else {
            val posterUrl = Simkl.imageUrl(item.poster)
            if (!posterUrl.isNullOrBlank()) {
                holder.image.loadImage(posterUrl)
            } else {
                holder.image.setImageResource(R.drawable.ic_round_person_24)
            }
            holder.overlayTitle.visibility = View.VISIBLE
            holder.title.text = item.title ?: ""
            holder.title.isVisible = true
            if (mediaType == "tv") {
                val watched = item.totalWatched
                val total = item.totalEpisodes.takeIf { it > 0 } ?: 0
                if (total > 0) {
                    holder.progress.max = total
                    holder.progress.progress = watched.coerceIn(0, total)
                    holder.progress.visibility = View.VISIBLE
                }
            }
        }

        if (epStr.isNullOrBlank()) {
            holder.episodeNo.visibility = View.GONE
        } else {
            holder.episodeNo.visibility = View.VISIBLE
            holder.episodeNoText.text = epStr
        }

        val subtitle = buildString {
            val type = item.mediaType ?: "tv"
            append(type.replaceFirstChar { it.uppercase() })
            val watched = item.totalWatched
            if (watched > 0) append(" · $watched")
            if (item.year != null) append(" \u00B7 ${item.year}")
        }
        holder.subtitle.text = subtitle

        setGradient(holder.gradientOverlay)

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false
        holder.itemView.alpha = 0.85f
        FocusEffectUtil.applyFocusListener(holder.itemView, holder.card, fade = true)
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cwCard)
        val image: ShapeableImageView = view.findViewById(R.id.cwImage)
        val gradientOverlay: View = view.findViewById(R.id.cwGradientOverlay)
        val clearlogo: ImageView = view.findViewById(R.id.cwClearlogo)
        val overlayTitle: TextView = view.findViewById(R.id.cwOverlayTitle)
        val title: TextView = view.findViewById(R.id.cwTitle)
        val subtitle: TextView = view.findViewById(R.id.cwSubtitle)
        val episodeNo: android.view.View = view.findViewById(R.id.cwEpisodeNo)
        val episodeNoText: TextView = view.findViewById(R.id.cwEpisodeText)
        val episodeBroadcast: ImageView = view.findViewById(R.id.cwEpisodeBroadcast)
        val timeWatched: TextView = view.findViewById(R.id.cwTimeWatched)
        val progress: ProgressBar = view.findViewById(R.id.cwProgress)
    }
}
