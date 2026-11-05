package ani.sanin.home

import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.DataSource
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.BANNER_TYPE_MODERN
import ani.sanin.util.BANNER_MAX_WIDTH
import ani.sanin.util.bannerLoadOptions
import ani.sanin.util.bindClassicChips
import ani.sanin.util.currentBannerType
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.isClassicBanner
import ani.sanin.getThemeColor

class TmdbBannerCarouselAdapter(
    private val items: List<TmdbHomeFragment.BannerItem>,
    private val onItemClick: (TmdbHomeFragment.BannerItem) -> Unit,
    private val genreNames: Map<Int, String>,
    private var logoUrls: Map<Int, String?> = emptyMap(),
    private var statusByIndex: Map<Int, String?> = emptyMap(),
    private var scoreByIndex: Map<Int, String?> = emptyMap(),
    private val modernMode: Boolean = false,
) : RecyclerView.Adapter<TmdbBannerCarouselAdapter.ViewHolder>() {

    private var detailsByIndex: Map<Int, TmdbDetail?> = emptyMap()
    private var postersByIndex: Map<Int, String?> = emptyMap()

    var landscapeMode = false
    var cardWidthPx = 0

    fun setLandscapeMode(enabled: Boolean, widthPx: Int) {
        if (landscapeMode == enabled && cardWidthPx == widthPx) return
        landscapeMode = enabled
        cardWidthPx = widthPx
        notifyDataSetChanged()
    }

    val actualCount: Int get() = items.size
    fun realPosition(virtualPos: Int): Int = virtualPos % items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(
                layoutForBannerType(modernMode),
                parent,
                false,
            )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pos = realPosition(position)
        val item = items[pos]
        val ctx = holder.itemView.context

        if (modernMode) {
            bindModern(holder, item, pos)
            return
        }

        val imageUrl = item.bannerUrl
        val bannerImage = holder.bannerImage
        val loadOptions = bannerLoadOptions(currentBannerType(modernMode, isClassicBanner()))
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerImage?.isVisible = true
            holder.bannerBg.isVisible = true
            bannerImage?.scaleType = if (isClassicBanner()) android.widget.ImageView.ScaleType.CENTER_CROP
                else android.widget.ImageView.ScaleType.FIT_CENTER
            holder.bannerBg.setImageDrawable(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, R.color.bg_black)
            )
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black).error(R.drawable.ic_round_person_24)
                .apply(loadOptions)
                .listener(object : com.bumptech.glide.request.RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        holder.bannerBg.setImageDrawable(resource)
                        return false
                    }
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean = false
                })
                .into(bannerImage ?: holder.bannerBg)
        } else {
            bannerImage?.isVisible = false
            holder.bannerBg.isVisible = false
        }

        val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) logoUrls[pos] else null
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.tag = pos
            holder.clearlogo.isVisible = true
            holder.title.isVisible = false
            val logoReq = Glide.with(ctx).load(logoUrl)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean = false

                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (holder.clearlogo.tag == pos) {
                            holder.clearlogo.isVisible = false
                            holder.title.isVisible = true
                        }
                        return false
                    }
                })
            if (isClassicBanner()) {

                logoReq.override(
                    holder.clearlogo.layoutParams.width,
                    holder.clearlogo.layoutParams.height
                )
            } else {
                logoReq.override(240, 64)
            }
            logoReq.into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.title.isVisible = true
            holder.title.text = item.title
        }

        val typeText = when (item.type) {
            "tv" -> "TV"
            "movie" -> "Movie"
            else -> item.type.replaceFirstChar { it.uppercase() }
        }
        val statusText = statusByIndex[pos]

        val year = item.year.ifBlank { detailsByIndex[pos]?.year.orEmpty() }

        val score = scoreByIndex[pos]
            ?: detailsByIndex[pos]?.voteAverage?.takeIf { it > 0.0 }
                ?.let { String.format("%.1f", it) + "%" }

        val genres = when (item) {
            is TmdbHomeFragment.BannerItem.Tmdb ->
                item.media.genreIds.mapNotNull { genreNames[it] }
            is TmdbHomeFragment.BannerItem.Plugin ->
                detailsByIndex[pos]?.genres?.map { it.name }.orEmpty()
        }

        if (isClassicBanner()) {

            bindClassicChips(
                holder.genresRow,
                holder.chipsTop,
                holder.chipsBottom,
                listOfNotNull(
                    typeText.takeIf { it.isNotBlank() },
                    statusText,
                    year.takeIf { it.isNotBlank() },
                    score,
                ) + genres,
            )
            holder.formatTag?.isVisible = false
            holder.statusTag?.isVisible = false
            holder.seasonTag?.isVisible = false
            holder.scoreTag?.isVisible = false
        } else {
            setChip(holder.formatTag, typeText)
            setChip(holder.statusTag, statusText)
            setChip(holder.seasonTag, year)
            setChip(holder.scoreTag, score)

            val genresRow = holder.genresRow
            genresRow?.removeAllViews()
            val density = ctx.resources.displayMetrics.density
            for (genre in genres.take(4)) {
                val chip = TextView(ctx).apply {
                    text = genre
                    setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
                    textSize = 11f
                    setBackgroundResource(R.drawable.tag_chip_bg)
                    setPadding(
                        (10 * density).toInt(),
                        (3 * density).toInt(),
                        (10 * density).toInt(),
                        (3 * density).toInt()
                    )
                    maxLines = 1
                    isFocusable = false
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (6 * density).toInt()
                genresRow?.addView(chip, lp)
            }
            if (genresRow != null) genresRow.isVisible = genres.isNotEmpty()
        }

        holder.description.isVisible = false

        if (isClassicBanner()) {
            holder.playBtn.setOnClickListener { onItemClick(item) }
            holder.playBtn.isFocusable = true
            holder.playBtn.isFocusableInTouchMode = false
            holder.playBtn.isVisible = true
        } else {
            holder.playBtn.isVisible = false
        }
        holder.favBtn?.isVisible = false

        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false
        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener { onItemClick(item) }

        applyLandscapeOverlay(holder)

        for (offset in listOf(-1, 1)) {
            val adjPos = realPosition(position + offset)
            if (adjPos in items.indices) {
                items[adjPos].bannerUrl?.let {
                    Glide.with(ctx)
                        .load(it)
                        .apply(bannerLoadOptions(currentBannerType(modernMode, isClassicBanner())))
                        .preload(BANNER_MAX_WIDTH, BANNER_MAX_WIDTH)
                }
            }
        }
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bannerBg: ImageView = view.findViewById(R.id.bannerBg)
        val bannerImage: ImageView? = view.findViewById(R.id.bannerImage)
        val clearlogo: ImageView = view.findViewById(R.id.bannerClearlogo)
        val title: TextView = view.findViewById(R.id.bannerTitle)
        val formatTag: TextView? = view.findViewById(R.id.bannerFormatTag)
        val statusTag: TextView? = view.findViewById(R.id.bannerStatusTag)
        val seasonTag: TextView? = view.findViewById(R.id.bannerSeasonTag)
        val scoreTag: TextView? = view.findViewById(R.id.bannerScoreTag)
        val description: TextView = view.findViewById(R.id.bannerDescription)
        val genresRow: ViewGroup? = view.findViewById(R.id.bannerGenresRow)
        val chipsTop: ViewGroup? = view.findViewById(R.id.bannerChipsTop)
        val chipsBottom: ViewGroup? = view.findViewById(R.id.bannerChipsBottom)
        val metaRow: LinearLayout? = view.findViewById(R.id.bannerMetaRow)
        val playBtn: android.widget.Button = view.findViewById(R.id.bannerPlayBtn)
        val favBtn: ImageView? = view.findViewById(R.id.bannerFavBtn)
        val poster: ImageView? = view.findViewById(R.id.bannerModernPoster)
        val modernContent: LinearLayout? = view.findViewById(R.id.bannerModernContent)
        val scrim: View? = view.findViewById(R.id.bannerScrimLeft)
        val content: LinearLayout? = view.findViewById(R.id.bannerContent)
    }

    private fun applyLandscapeOverlay(holder: ViewHolder) {
        val scrim = holder.scrim ?: return
        val content = holder.content ?: return
        val density = holder.itemView.context.resources.displayMetrics.density

        if (isClassicBanner()) {
            scrim.isVisible = false
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            content.gravity = Gravity.CENTER_HORIZONTAL
            val padH = if (landscapeMode) (24 * density).toInt() else (16 * density).toInt()
            val padB = if (landscapeMode) (10 * density).toInt() else (14 * density).toInt()
            content.setPadding(padH, 0, padH, padB)
            holder.clearlogo.maxWidth = Int.MAX_VALUE
            holder.clearlogo.maxHeight = Int.MAX_VALUE
            holder.favBtn?.isVisible = false
            return
        }

        if (!landscapeMode) {
            scrim.isVisible = false
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            val pad = (12 * density).toInt()
            content.setPadding(pad, pad, pad, pad)
            holder.clearlogo.maxWidth = (160 * density).toInt()
            holder.clearlogo.maxHeight = (40 * density).toInt()
            holder.title.isVisible = true
            holder.formatTag?.isVisible = true
            holder.statusTag?.isVisible = true
            holder.seasonTag?.isVisible = true
            holder.scoreTag?.isVisible = true
            holder.genresRow?.isVisible = true
            return
        }

        val half = cardWidthPx / 2
        scrim.isVisible = !isClassicBanner()
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        holder.clearlogo.isVisible = false
        holder.title.isVisible = false
        holder.formatTag?.isVisible = false
        holder.statusTag?.isVisible = false
        holder.seasonTag?.isVisible = false
        holder.scoreTag?.isVisible = false
        holder.description.isVisible = false
        holder.genresRow?.isVisible = false
    }

    private fun bindModern(holder: ViewHolder, item: TmdbHomeFragment.BannerItem, pos: Int) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        applyModernMetrics(holder.itemView, holder.description)
        applyModernContentWidth(holder.itemView, holder.modernContent)
        val detail = detailsByIndex[pos]

        val imageUrl = item.bannerUrl
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.isVisible = true
            holder.bannerBg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .apply(bannerLoadOptions(BANNER_TYPE_MODERN))
                .into(holder.bannerBg)
        } else {
            holder.bannerBg.isVisible = false
        }

        val poster = holder.poster
        val posterUrl = postersByIndex[pos] ?: (item as? TmdbHomeFragment.BannerItem.Plugin)?.response?.posterUrl
        if (!posterUrl.isNullOrBlank() && poster != null) {
            poster.isVisible = true
            Glide.with(ctx).load(posterUrl).placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24).into(poster)
        } else {
            poster?.isVisible = false
        }

        val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) logoUrls[pos] else null
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = false
            Glide.with(ctx).load(logoUrl).override(520, 160).into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = true
            holder.title.text = item.title
        }

        val overview = detail?.overview?.replace(Regex("\\s+"), " ")?.trim()
        if (!overview.isNullOrBlank()) {
            holder.description.text = overview
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        val metaRow = holder.metaRow
        metaRow?.removeAllViews()
        metaRow?.let { row ->
            val genres = when (item) {
                is TmdbHomeFragment.BannerItem.Tmdb ->
                    item.media.genreIds.mapNotNull { genreNames[it] }
                is TmdbHomeFragment.BannerItem.Plugin ->
                    detail?.genres?.map { it.name }.orEmpty()
            }
            for (genre in genres.take(MODERN_MAX_GENRES)) {
                addModernMetaItem(row, genre, density)
            }
            addModernMetaItem(row, detail?.year?.takeIf { it.isNotBlank() }, density)
            val rating = detail?.voteAverage?.takeIf { it > 0.0 }
                ?: (item as? TmdbHomeFragment.BannerItem.Tmdb)?.media?.voteAverage?.takeIf { it > 0.0 }
            if (rating != null) {
                addModernMetaItem(row, String.format("%.1f", rating), density, withStar = true)
            }
            addModernMetaItem(row, statusByIndex[pos], density, accent = true)
            addModernMetaItem(
                row,
                (item as? TmdbHomeFragment.BannerItem.Tmdb)?.media?.type,
                density,
            )
            row.isVisible = row.childCount > 0
        }

        holder.playBtn.setOnClickListener { onItemClick(item) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.isVisible = true

        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    fun updateDetails(
        logos: Map<Int, String?>,
        statuses: Map<Int, String?>,
        scores: Map<Int, String?>,
        details: Map<Int, TmdbDetail?>,
        posters: Map<Int, String?>,
    ) {
        logoUrls = logos
        statusByIndex = statuses
        scoreByIndex = scores
        detailsByIndex = details
        postersByIndex = posters
        notifyDataSetChanged()
    }
}

private fun setChip(view: TextView?, text: String?) {
    if (view == null) return
    if (!text.isNullOrBlank()) {
        view.text = text
        view.isVisible = true
    } else {
        view.isVisible = false
    }
}
