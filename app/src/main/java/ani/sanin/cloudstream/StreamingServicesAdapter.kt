package ani.sanin.cloudstream

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.databinding.ItemStreamingServiceBinding
import ani.sanin.getThemeColor
import ani.sanin.setSafeOnClickListener
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil

class StreamingServicesAdapter(
    private val isTv: Boolean,
    private val onServiceClick: (TmdbProvider) -> Unit,
    private val onSeeAll: () -> Unit,

    private val showSeeAll: Boolean = true,

    private val limit: Int = MAX_SERVICES
) : RecyclerView.Adapter<StreamingServicesAdapter.ServiceViewHolder>() {

    private var providers: List<TmdbProvider> = emptyList()

    private var gridTileWidthPx = 0
    private var gridTileHeightPx = 0
    private var gridCrossSpacingPx = 0
    private var gridMainSpacingPx = 0

    fun setGridTileSize(widthPx: Int, heightPx: Int, crossSpacingPx: Int, mainSpacingPx: Int) {
        if (gridTileWidthPx == widthPx && gridTileHeightPx == heightPx) return
        gridTileWidthPx = widthPx
        gridTileHeightPx = heightPx
        gridCrossSpacingPx = crossSpacingPx
        gridMainSpacingPx = mainSpacingPx
        notifyDataSetChanged()
    }

    fun submit(list: List<TmdbProvider>) {
        providers = list.take(limit)
        notifyDataSetChanged()
    }

    fun clear() {
        if (providers.isEmpty()) return
        providers = emptyList()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = providers.size + if (showSeeAll) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (showSeeAll && position == providers.size) TYPE_SEE_ALL else TYPE_SERVICE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val binding = ItemStreamingServiceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ServiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        if (holder.itemViewType == TYPE_SEE_ALL) {
            holder.bindSeeAll()
        } else {
            holder.bindService(providers[position])
        }
    }

    override fun onViewRecycled(holder: ServiceViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ServiceViewHolder(
        val binding: ItemStreamingServiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {

        }

        private fun sizeTile() {
            val density = binding.root.resources.displayMetrics.density
            val lp = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            if (gridTileWidthPx > 0) {

                lp.width = gridTileWidthPx
                lp.height = gridTileHeightPx
                lp.marginEnd = gridCrossSpacingPx
                lp.bottomMargin = gridMainSpacingPx
            } else {
                val w = if (isTv) 168 else 128
                val h = if (isTv) 92 else 70
                lp.width = (w * density).toInt()
                lp.height = (h * density).toInt()
                lp.marginEnd = (GAP_DP * density).toInt()
                lp.bottomMargin = (RAIL_MARGIN_BOTTOM_DP * density).toInt()
            }
            binding.root.layoutParams = lp
        }

        private fun sizeArrow() {
            val density = binding.root.resources.displayMetrics.density
            val lp = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

            val w = if (isTv) 44 else 36
            val h = if (isTv) 92 else 70
            lp.width = (w * density).toInt()
            lp.height = (h * density).toInt()
            lp.marginEnd = (GAP_DP * density).toInt()
            lp.bottomMargin = (RAIL_MARGIN_BOTTOM_DP * density).toInt()
            binding.root.layoutParams = lp
        }

        fun bindService(provider: TmdbProvider) {
            val card = binding.streamingServiceCard
            val logo = binding.streamingServiceLogo
            sizeTile()
            card.contentDescription = provider.displayName

            card.isFocusable = true
            card.isFocusableInTouchMode = false
            FocusEffectUtil.applyFocusListener(card)
            binding.streamingServiceSeeAll.isVisible = false
            logo.isVisible = true
            logo.scaleX = 1f
            logo.scaleY = 1f
            card.setSafeOnClickListener { onServiceClick(provider) }

            val ctx = card.context

            applyPlate(card, ctx.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant))

            val logoUrl = Tmdb.imageUrl(provider.logoPath, LOGO_WIDTH) ?: return

            StreamingLogoTint.cached(logoUrl)?.let {
                applyTint(card, logo, it)
                return@let
            }
            if (!StreamingLogoTint.isKnown(logoUrl) && !StreamingLogoTint.beginLoad(logoUrl)) return

            Glide.with(logo)
                .asBitmap()
                .load(logoUrl)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(LOGO_SAMPLE_PX, LOGO_SAMPLE_PX)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(
                        resource: android.graphics.Bitmap,
                        transition: Transition<in android.graphics.Bitmap>?
                    ) {
                        val tint = StreamingLogoTint.sample(resource)
                        StreamingLogoTint.put(logoUrl, tint)
                        if (tint != null) applyTint(card, logo, tint)
                        logo.setImageBitmap(resource)
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        logo.setImageDrawable(null)
                    }

                    override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                        StreamingLogoTint.failLoad(logoUrl)
                    }
                })
        }

        fun bindSeeAll() {
            val card = binding.streamingServiceCard
            val logo = binding.streamingServiceLogo
            val arrow = binding.streamingServiceSeeAll
            sizeArrow()
            card.contentDescription = "See all streaming services"
            arrow.isVisible = true
            logo.isVisible = false

            card.isFocusable = true
            card.isFocusableInTouchMode = false
            FocusEffectUtil.applyFocusListener(card)
            arrow.isFocusable = false
            arrow.isFocusableInTouchMode = false
            card.setSafeOnClickListener { onSeeAll() }

            arrow.setOnClickListener { onSeeAll() }

            card.background = null
            LensButtonBackground.apply(arrow)
            FocusEffectUtil.applyFocusListener(arrow)
        }

        fun clear() {
            binding.streamingServiceLogo.setImageDrawable(null)
        }

        private fun applyPlate(card: android.view.View, colour: Int) {
            card.background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * card.resources.displayMetrics.density
                setColor(colour)
            }
        }

        private fun applyTint(card: android.view.View, logo: ImageView, tint: StreamingTint) {
            card.background = GradientDrawable(
                if (tint.vertical) GradientDrawable.Orientation.TOP_BOTTOM
                else GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(tint.start, tint.end)
            ).apply {
                cornerRadius = CORNER_DP * card.resources.displayMetrics.density
            }

            logo.scaleX = tint.contentScale
            logo.scaleY = tint.contentScale
        }
    }

    private companion object {
        const val TYPE_SERVICE = 0
        const val TYPE_SEE_ALL = 1
        const val MAX_SERVICES = 12
        const val GAP_DP = 10

        const val RAIL_MARGIN_BOTTOM_DP = 8
        const val CORNER_DP = 14f

        const val LOGO_WIDTH = 185

        const val LOGO_SAMPLE_PX = 320
    }
}
