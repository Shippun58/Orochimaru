package ani.sanin.home

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ani.sanin.R
import ani.sanin.isClassicBanner
import ani.sanin.connections.anilist.Anilist
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.media.Media
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class BannerCarouselAdapter(
    private val items: List<Media>,
    private val scope: CoroutineScope,
    private val onItemClick: (Media) -> Unit,
    private var backdropUrls: Map<Int, String?> = emptyMap(),
    private var logoUrls: Map<Int, String?> = emptyMap(),
    var nextFocusDownId: Int = View.NO_ID,
    private val layoutRes: Int = R.layout.item_banner_carousel,
    private val cardMode: Boolean = false,
    private val hideDescription: Boolean = false,
    private val modernMode: Boolean = false,
) : RecyclerView.Adapter<BannerCarouselAdapter.ViewHolder>() {

    private var landscapeOverlay = false
    private var cardWidthPx = 0

    fun setLandscapeMode(enabled: Boolean, cardWidthPx: Int) {
        if (this.landscapeOverlay == enabled && this.cardWidthPx == cardWidthPx) return
        this.landscapeOverlay = enabled
        this.cardWidthPx = cardWidthPx
        notifyDataSetChanged()
    }

    val actualCount: Int get() = items.size

    fun realPosition(virtualPos: Int): Int = virtualPos % items.size

    fun updateUrls(backdrops: Map<Int, String?>, logos: Map<Int, String?>) {
        backdropUrls = backdrops
        logoUrls = logos
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[realPosition(position)]
        val ctx = holder.itemView.context

        if (modernMode) {
            bindModern(holder, media)
            return
        }

        val anizipUrl = backdropUrls[media.id]
        val imageUrl = if (!anizipUrl.isNullOrBlank()) anizipUrl else media.banner ?: media.cover
        val bannerImage = holder.bannerImage
        val bannerBgView = holder.bannerBg
        val loadOptions = bannerLoadOptions(currentBannerType(modernMode, isClassicBanner()))
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.visibility = View.VISIBLE
            bannerImage?.visibility = View.VISIBLE
            bannerImage?.scaleType = if (isClassicBanner()) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
            holder.bannerBg.setImageDrawable(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(holder.itemView.context, R.color.bg_black)
            )
            Glide.with(holder.itemView.context)
                .load(imageUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .apply(loadOptions)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        bannerImage?.scaleType = if (isClassicBanner() ||
                            resource.intrinsicHeight > resource.intrinsicWidth)
                            ImageView.ScaleType.CENTER_CROP
                        else
                            ImageView.ScaleType.FIT_CENTER
                        bannerBgView.setImageDrawable(resource)
                        return false
                    }
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        bannerImage?.scaleType = if (isClassicBanner()) ImageView.ScaleType.CENTER_CROP
                            else ImageView.ScaleType.FIT_CENTER
                        return false
                    }
                })
                .into(bannerImage ?: holder.bannerBg)
        }

        holder.title.text = media.userPreferredName ?: media.name
        holder.title.isVisible = true
        holder.clearlogo.isVisible = false
        holder.clearlogo.setImageDrawable(null)
        val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) logoUrls[media.id] else null
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.tag = media.id
            holder.clearlogo.isVisible = true
            holder.title.isVisible = false
            val logoReq = com.bumptech.glide.Glide.with(holder.clearlogo.context)
                .load(logoUrl)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean = false

                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (holder.clearlogo.tag == media.id) {
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
        }

        val formatText = media.format?.replace("_", " ")?.let { fmt ->
            when {
                fmt.equals("TV", true) -> "TV Series"
                fmt.equals("TV_SHORT", true) -> "TV Short"
                else -> fmt
            }
        }
        val statusText = media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
        val seasonText = media.anime?.season?.lowercase()
            ?.let { season ->
                media.anime?.seasonYear?.let { year -> "$season $year" } ?: season
            }
        val scoreText = media.meanScore?.let { "$it%" }

        if (isClassicBanner()) {
            bindClassicChips(
                holder.genresRow,
                holder.chipsTop,
                holder.chipsBottom,
                listOfNotNull(formatText, statusText, seasonText, scoreText) + media.genres,
            )
            holder.formatTag?.isVisible = false
            holder.statusTag?.isVisible = false
            holder.seasonTag?.isVisible = false
            holder.scoreTag?.isVisible = false
        } else {
            setChip(holder.formatTag, formatText)
            setChip(holder.statusTag, statusText)
            setChip(holder.seasonTag, seasonText)
            setChip(holder.scoreTag, scoreText)
        }

        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (hideDescription || isClassicBanner()) {
            holder.description.isVisible = false
        } else if (!desc.isNullOrBlank()) {
            holder.description.text = desc
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        val genresRow = holder.genresRow
        if (!isClassicBanner()) {
            genresRow?.removeAllViews()
            if (media.genres.isNotEmpty() && genresRow != null) {
                val density = ctx.resources.displayMetrics.density
                for (genre in media.genres.take(4)) {
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
                    genresRow.addView(chip, lp)
                }
                genresRow.isVisible = true
            } else {
                genresRow?.isVisible = false
            }
        }

        holder.playBtn.setOnClickListener { onItemClick(media) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.text = ctx.getString(
            if (media.userStatus == "CURRENT") R.string.continue_watching_short
            else R.string.watch_now
        )
        holder.playBtn.visibility = View.VISIBLE

        val favBtn = holder.favBtn
        val isFav = media.isFav
        favBtn?.setImageDrawable(
            ContextCompat.getDrawable(
                ctx,
                if (isFav) R.drawable.ic_round_favorite_24
                else R.drawable.ic_round_favorite_border_24
            )
        )
        favBtn?.setOnClickListener {
            val newState = !media.isFav
            media.isFav = newState
            favBtn.setImageDrawable(
                ContextCompat.getDrawable(
                    ctx,
                    if (newState) R.drawable.ic_round_favorite_24
                    else R.drawable.ic_round_favorite_border_24
                )
            )
            scope.launch(Dispatchers.IO) {
                Anilist.mutation.toggleFav(media.anime != null, media.id)
            }
        }

        favBtn?.visibility = View.GONE

        holder.itemView.setOnClickListener { onItemClick(media) }

        holder.itemView.isFocusable = cardMode
        holder.itemView.isFocusableInTouchMode = false

        if (nextFocusDownId != View.NO_ID) {
            holder.playBtn.nextFocusDownId = nextFocusDownId
            favBtn?.nextFocusDownId = nextFocusDownId
        }

        if (cardMode) {
            holder.description.isVisible = false
            holder.playBtn.isVisible = false
            favBtn?.isVisible = false
        }

        applyLandscapeOverlay(holder)

        for (offset in listOf(-1, 1)) {
            val pos = realPosition(position + offset)
            if (pos in items.indices) {
                val item = items[pos]
                val url = backdropUrls[item.id] ?: item.banner ?: item.cover
                if (!url.isNullOrBlank()) {
                    Glide.with(ctx)
                        .load(url)
                        .apply(bannerLoadOptions(currentBannerType(modernMode, isClassicBanner())))
                        .preload(BANNER_MAX_WIDTH, BANNER_MAX_WIDTH)
                }
            }
        }
    }

    override fun getItemCount() = if (items.isEmpty()) 0 else Int.MAX_VALUE

    private fun bindModern(holder: ViewHolder, media: Media) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        applyModernMetrics(holder.itemView, holder.description)
        applyModernContentWidth(holder.itemView, holder.modernContent)

        val imageUrl = backdropUrls[media.id]?.takeIf { it.isNotBlank() }
            ?: media.banner?.takeIf { it.isNotBlank() }
            ?: media.cover
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.isVisible = true
            holder.bannerBg.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(ctx)
                .load(imageUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .apply(bannerLoadOptions(BANNER_TYPE_MODERN))
                .into(holder.bannerBg)
        } else {
            holder.bannerBg.isVisible = false
        }

        val poster = holder.poster
        val posterUrl = media.cover
        if (!posterUrl.isNullOrBlank() && poster != null) {
            poster.isVisible = true
            Glide.with(ctx)
                .load(posterUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .into(poster)
        } else {
            poster?.isVisible = false
        }

        val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) logoUrls[media.id] else null
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = false
            Glide.with(ctx)
                .load(logoUrl)
                .override(520, 160)
                .into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = true
            holder.title.text = media.userPreferredName ?: media.name
        }

        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (!desc.isNullOrBlank()) {
            holder.description.text = desc
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        val metaRow = holder.metaRow
        metaRow?.removeAllViews()
        metaRow?.let { row ->
            for (genre in media.genres.take(MODERN_MAX_GENRES)) {
                addModernMetaItem(row, genre, density)
            }
            addModernMetaItem(row, media.anime?.seasonYear?.toString(), density)
            media.meanScore?.let { score ->

                addModernMetaItem(row, String.format("%.1f", score / 10f), density, withStar = true)
            }
            addModernMetaItem(
                row,
                media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() },
                density,
                accent = true,
            )
            addModernMetaItem(row, media.format?.replace("_", " "), density)
            addModernMetaItem(row, media.countryOfOrigin, density)
            row.isVisible = row.childCount > 0
        }

        holder.playBtn.setOnClickListener { onItemClick(media) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.isVisible = true

        holder.itemView.setOnClickListener { onItemClick(media) }

        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false

        if (nextFocusDownId != View.NO_ID) {
            holder.playBtn.nextFocusDownId = nextFocusDownId
        }
    }

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
        val bottomGradient: View? = view.findViewById(R.id.bannerBottomGradient)
    }

    private fun applyLandscapeOverlay(holder: ViewHolder) {
        val scrim = holder.scrim ?: return
        val content = holder.content ?: return
        val bottomGradient = holder.bottomGradient ?: return
        val density = holder.itemView.context.resources.displayMetrics.density

        if (isClassicBanner()) {
            scrim.isVisible = false
            bottomGradient.isVisible = true
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            content.gravity = Gravity.CENTER_HORIZONTAL
            val padH = if (landscapeOverlay) (24 * density).toInt() else (16 * density).toInt()
            val padB = if (landscapeOverlay) (10 * density).toInt() else (14 * density).toInt()
            content.setPadding(padH, 0, padH, padB)

            holder.clearlogo.maxWidth = Int.MAX_VALUE
            holder.clearlogo.maxHeight = Int.MAX_VALUE
            holder.favBtn?.isVisible = false
            return
        }

        if (!landscapeOverlay) {
            scrim.isVisible = false
            bottomGradient.isVisible = true
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            val pad = (12 * density).toInt()
            content.setPadding(pad, pad, pad, pad)
            holder.clearlogo.maxWidth = (160 * density).toInt()
            holder.clearlogo.maxHeight = (40 * density).toInt()
            return
        }
        val half = cardWidthPx / 2

        val largeBanner = isClassicBanner()
        scrim.isVisible = !largeBanner
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        bottomGradient.isVisible = largeBanner
        holder.clearlogo.isVisible = false
        holder.title.isVisible = false
        holder.formatTag?.isVisible = false
        holder.statusTag?.isVisible = false
        holder.seasonTag?.isVisible = false
        holder.scoreTag?.isVisible = false
        holder.description.isVisible = false
        holder.genresRow?.isVisible = false
    }
}

const val MODERN_MAX_GENRES = 3

internal const val BANNER_SETTLE_POLL_MS = 120L

private const val MODERN_CONTENT_WIDTH_FRACTION = 0.58f

internal fun applyModernMetrics(root: View, description: TextView?) {
    val res = root.resources
    val height = res.getDimensionPixelSize(R.dimen.banner_modern_height)
    val lp = root.layoutParams
    if (lp != null && lp.height != height) {
        lp.height = height
        root.layoutParams = lp
    }
    description?.setTextSize(
        android.util.TypedValue.COMPLEX_UNIT_PX,
        res.getDimension(R.dimen.banner_modern_synopsis_text),
    )
}

internal fun applyModernContentWidth(root: View, content: View?) {
    content ?: return
    val apply = {
        val total = root.width
        if (total > 0) {
            val target = (total * MODERN_CONTENT_WIDTH_FRACTION).toInt()
            val lp = content.layoutParams
            if (lp != null && lp.width != target) {
                lp.width = target
                content.layoutParams = lp
            }
        }
    }
    if (root.width > 0) apply() else root.post { apply() }
}

private val MODERN_META_MUTED = 0xCCFFFFFF.toInt()

internal fun addModernMetaItem(
    row: LinearLayout,
    value: String?,
    density: Float,
    withStar: Boolean = false,
    accent: Boolean = false,
) {
    if (value.isNullOrBlank()) return
    val ctx = row.context
    val res = ctx.resources

    val textPx = res.getDimension(R.dimen.banner_modern_meta_text)
    val gap = res.getDimension(R.dimen.banner_modern_meta_gap).toInt()

    if (row.childCount > 0) {
        val dot = TextView(ctx).apply {
            setText("·")
            setTextColor(MODERN_META_MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
            includeFontPadding = false
            isFocusable = false
        }
        row.addView(
            dot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = gap
                marginEnd = gap
            },
        )
    }

    val item = TextView(ctx).apply {
        setText(value)
        setTextColor(
            if (accent) ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
            else MODERN_META_MUTED
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        isFocusable = false
        if (withStar) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_star_rating, 0, 0, 0)
            compoundDrawablePadding = (3 * density).toInt()
        }
    }
    row.addView(
        item,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
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

fun layoutForBannerType(modern: Boolean): Int = when {
    modern -> R.layout.item_banner_modern
    isClassicBanner() -> R.layout.item_banner_classic
    else -> R.layout.item_banner_card
}
