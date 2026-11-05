package ani.sanin.media

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.blurImage
import ani.sanin.connections.LogoApi
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.currActivity
import ani.sanin.databinding.ItemMediaCompactBinding
import ani.sanin.databinding.ItemMediaCompactLandBinding
import ani.sanin.databinding.ItemMediaLargeBinding
import ani.sanin.databinding.ItemMediaPageBinding
import ani.sanin.databinding.ItemMediaPageSmallBinding
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.subdub.SubDubCache
import ani.sanin.subdub.SubDubInfo
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import java.io.Serializable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaAdaptor(
    var type: Int,
    private val mediaList: MutableList<Media>?,
    private val activity: FragmentActivity,
    private val matchParent: Boolean = false,
    private val viewPager: ViewPager2? = null,
    private val fav: Boolean = false,
    private val isOtherUser: Boolean = false,

    private val onItemClickOverride: ((Media) -> Boolean)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rawCardStyle = 0
    private var isLandscape = false
    private var cachedCardRoundness = PrefManager.getVal<Int>(PrefName.StandardCardRoundness).toFloat()
    private var cachedCardSize = PrefManager.getVal<Float>(PrefName.CardSize)
    private var cachedBannerAnimations = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.BannerAnimations)
    private var cachedAnimationSpeed = PrefManager.getVal<Float>(PrefName.AnimationSpeed)
    private var cachedCardTitlePosition = PrefManager.getVal<Int>(PrefName.CardTitlePosition)

    fun refreshCache() {
        cachedCardRoundness = PrefManager.getVal<Int>(PrefName.StandardCardRoundness).toFloat()
        cachedCardSize = PrefManager.getVal(PrefName.CardSize)
        cachedBannerAnimations = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.BannerAnimations)
        cachedAnimationSpeed = PrefManager.getVal(PrefName.AnimationSpeed)
        cachedCardTitlePosition = PrefManager.getVal(PrefName.CardTitlePosition)
        cachedAnimationSpeed = PrefManager.getVal(PrefName.AnimationSpeed)
    }

    init {
        if (type == 0) {
            rawCardStyle = PrefManager.getVal<Int>(PrefName.CardStyle)
            type = when (rawCardStyle) {
                0, 4, 6 -> 0
                1 -> 1
                2, 5 -> 2
                3 -> 3
                else -> 0
            }
        }
        isLandscape = if (type == 0) PrefManager.getVal<Int>(PrefName.CardOrientation) == 0 else false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (type == 0 && isLandscape) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_compact_land, parent, false)
            return MediaLandscapeViewHolder(
                ani.sanin.databinding.ItemMediaCompactLandBinding.bind(view)
            )
        }
        return when (type) {
            0 -> MediaViewHolder(
                ItemMediaCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> MediaLargeViewHolder(
                ItemMediaLargeBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> MediaPageViewHolder(
                ItemMediaPageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            3 -> MediaPageSmallViewHolder(
                ItemMediaPageSmallBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }

    }

    private var logoJobs = mutableMapOf<Int, Job>()
    private var backdropJobs = mutableMapOf<Int, Job>()
    private var tmdbArtworkJobs = mutableMapOf<Int, Job>()

    private fun bindMediaCover(image: ImageView, media: Media, position: Int) {
        image.loadImage(media.cover)
        image.tag = media.id
        tmdbArtworkJobs[position]?.cancel()
        val type = media.tmdbType ?: return
        tmdbArtworkJobs[position] = activity.lifecycleScope.launch {
            val detail = runCatching { Tmdb.detail(type, media.id) }.getOrNull()
            val posterUrl = detail?.posterPath?.let { Tmdb.imageUrl(it, 500) }
            if (!posterUrl.isNullOrBlank() && image.tag == media.id) {
                image.loadImage(posterUrl)
            }
        }
    }

    private fun bindLogo(
        clearlogo: ImageView,
        overlayTitle: TextView,
        media: Media,
        position: Int
    ) {
        logoJobs[position]?.cancel()
        logoJobs[position] = activity.lifecycleScope.launch(Dispatchers.Main) {
            val logoUrl = LogoApi.getLogoUrl(media.id)
            if (!logoUrl.isNullOrBlank()) {
                clearlogo.visibility = View.VISIBLE
                clearlogo.loadImage(logoUrl)
                overlayTitle.visibility = View.GONE
            } else {
                clearlogo.visibility = View.GONE
                overlayTitle.visibility = View.VISIBLE
                overlayTitle.text = media.userPreferredName
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val cardRoundness = cachedCardRoundness

        if (holder is MediaLandscapeViewHolder) {
            bindLandscape(holder, position, cardRoundness)
            return
        }
        when (type) {
             0 -> {
                val b = (holder as MediaViewHolder).binding
                val media = mediaList?.getOrNull(position)
                if (media != null) {
                    bindMediaCover(b.itemCompactImage, media, position)
                    val cardSize = cachedCardSize
                    val finalW = (102 * cardSize).toInt()
                    val finalH = (154 * cardSize).toInt()
                    b.itemCompactImage.updateLayoutParams {
                        width = finalW
                        height = finalH
                    }
                    val styleRadius = when (rawCardStyle) {
                        4 -> 24f
                        6 -> 4f
                        else -> cardRoundness
                    }
                    b.itemCompactCard.radius = styleRadius

                    b.itemCompactScoreBG.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        val m = (styleRadius * 0.3f).toInt().coerceAtLeast(4)
                        topMargin = m
                        marginEnd = m
                    }
                    val showScore = shouldShowTopBadge(1) && (media.userScore > 0 || (media.meanScore ?: 0) > 0)
                    val showAiring = shouldShowTopBadge(2) && media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactScoreBG.isVisible = showScore || showAiring
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScore.isVisible = showScore
                    b.itemCompactScoreBG.findViewById<View>(R.id.itemCompactBroadcast).isVisible = showAiring
                    b.itemCompactScoreBG.findViewById<View>(R.id.imageView2)?.isVisible = showScore
                    b.itemCompactTitle.text = media.userPreferredName

                    if (shouldShowBottomBadge(1)) {
                        val badge0 = b.root.findViewById<View>(R.id.subDubBadge)
                        if (badge0 != null) {
                            SubDubCache.get(media.nameRomaji, activity.lifecycleScope, media.id) { info ->
                                bindSubDubBadge(badge0, info, media)
                            }
                        }
                    } else {
                        b.root.findViewById<View>(R.id.subDubBadge)?.visibility = View.GONE
                    }

                    b.itemCompactOverlay.visibility = View.GONE
                    b.itemCompactClearlogo.visibility = View.GONE
                    b.itemCompactOverlayTitle.visibility = View.GONE
                    logoJobs[position]?.cancel()
                    b.itemCompactTitle.visibility =
                        if (cachedCardTitlePosition == 2) View.GONE else View.VISIBLE

                    if (shouldShowBottomBadge(2)) {
                        val progressBadge = b.root.findViewById<View>(R.id.progressBadge)
                        if (progressBadge != null) bindProgressBadge(progressBadge, media)
                    } else {
                        b.root.findViewById<View>(R.id.progressBadge)?.visibility = View.GONE
                    }

                }
            }

             1 -> {
                val b = (holder as MediaLargeViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {
                    bindMediaCover(b.itemCompactImage, media, position)

                    b.itemCompactBanner.setImageDrawable(null)
                    if (!media.banner.isNullOrBlank()) b.itemCompactBanner.loadImage(media.banner)

                    b.itemCompactScoreBG.visibility = View.GONE
                    b.itemCompactBroadcast.visibility = View.GONE
                    b.itemCompactScore.visibility = View.GONE
                    b.itemCompactImageOverlay.visibility = View.GONE
                    b.itemCompactClearlogo.visibility = View.GONE
                    b.itemCompactOverlayTitle.visibility = View.GONE
                    b.itemCompactTitle.visibility = View.VISIBLE
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactCard.radius = 9f
                    b.itemCompactBanner.setImageDrawable(null)
                    if (!media.banner.isNullOrBlank()) b.itemCompactBanner.loadImage(media.banner)
                    b.root.findViewById<View>(R.id.subDubBadge)?.visibility = View.GONE
                    b.root.findViewById<View>(R.id.progressBadge)?.visibility = View.GONE
                    if (media.anime != null) {
                        val itemTotal = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural) else currActivity()!!.getString(
                            R.string.episode_singular
                        )
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    }
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val start = mediaList.size
                        mediaList.addAll(mediaList)
                        val end = mediaList.size - start
                        notifyItemRangeInserted(start, end)
                    }
                }
            }

            2 -> {
                val b = (holder as MediaPageViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {

                    val bannerAnimations = cachedBannerAnimations
                    bindMediaCover(b.itemCompactImage, media, position)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * cachedAnimationSpeed).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    val showBottom2 = cachedCardTitlePosition != 0
                    val showScore2 = shouldShowTopBadge(1) && (media.userScore > 0 || (media.meanScore ?: 0) > 0)
                    val showAiring2 = shouldShowTopBadge(2) && media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactScoreBG.isVisible = showScore2 || showAiring2
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScore.isVisible = showScore2
                    b.itemCompactScoreBG.findViewById<View>(R.id.itemCompactBroadcast).isVisible = showAiring2
                    b.itemCompactScoreBG.findViewById<View>(R.id.imageView2)?.isVisible = showScore2
                    b.itemCompactTitle.text = media.userPreferredName
                    if (showBottom2 && shouldShowBottomBadge(1)) {
                        val badge2 = b.root.findViewById<View>(R.id.subDubBadge)
                        if (badge2 != null) {
                            SubDubCache.get(media.nameRomaji, activity.lifecycleScope, media.id) { info ->
                                bindSubDubBadge(badge2, info, media)
                            }
                        }
                    } else {
                        b.root.findViewById<View>(R.id.subDubBadge)?.visibility = View.GONE
                    }
                    if (showBottom2 && shouldShowBottomBadge(2)) {
                        val pb2 = b.root.findViewById<View>(R.id.progressBadge)
                        if (pb2 != null) bindProgressBadge(pb2, media)
                    } else {
                        b.root.findViewById<View>(R.id.progressBadge)?.visibility = View.GONE
                    }
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList!!.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }

            3 -> {
                val b = (holder as MediaPageSmallViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {
                    val bannerAnimations = cachedBannerAnimations
                    bindMediaCover(b.itemCompactImage, media, position)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * cachedAnimationSpeed).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    val showBottom3 = cachedCardTitlePosition != 0
                    val showScore3 = shouldShowTopBadge(1) && (media.userScore > 0 || (media.meanScore ?: 0) > 0)
                    val showAiring3 = shouldShowTopBadge(2) && media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactScoreBG.isVisible = showScore3 || showAiring3
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScore.isVisible = showScore3
                    b.itemCompactScoreBG.findViewById<View>(R.id.itemCompactBroadcast).isVisible = showAiring3
                    b.itemCompactScoreBG.findViewById<View>(R.id.imageView2)?.isVisible = showScore3
                    b.itemCompactTitle.text = media.userPreferredName
                    if (showBottom3 && shouldShowBottomBadge(1)) {
                        val badge3 = b.root.findViewById<View>(R.id.subDubBadge)
                        if (badge3 != null) {
                            SubDubCache.get(media.nameRomaji, activity.lifecycleScope, media.id) { info ->
                                bindSubDubBadge(badge3, info, media)
                            }
                        }
                    } else {
                        b.root.findViewById<View>(R.id.subDubBadge)?.visibility = View.GONE
                    }
                    if (showBottom3 && shouldShowBottomBadge(2)) {
                        val pb3 = b.root.findViewById<View>(R.id.progressBadge)
                        if (pb3 != null) bindProgressBadge(pb3, media)
                    } else {
                        b.root.findViewById<View>(R.id.progressBadge)?.visibility = View.GONE
                    }
                    media.genres.apply {
                        if (isNotEmpty()) {
                            var genres = ""
                            forEach { genres += "$it • " }
                            genres = genres.removeSuffix(" • ")
                            b.itemCompactGenres.text = genres
                        }
                    }
                    b.itemCompactStatus.text = media.status ?: ""
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList!!.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }
        }
    }

    override fun getItemCount() = mediaList!!.size

    override fun getItemViewType(position: Int): Int {
        return type
    }

    fun randomOptionClick() {
        val media = if (!mediaList.isNullOrEmpty()) {
            mediaList.random()
        } else {
            null
        }
        media?.let {
            val index = mediaList?.indexOf(it) ?: -1
            clicked(index, null)
        }
    }

    private fun applyFocusWithFade(itemView: View, borderTarget: View) {
        FocusEffectUtil.applyFocusListener(itemView, borderTarget)
        val focusListener = itemView.onFocusChangeListener
        itemView.alpha = 0.85f
        itemView.setOnFocusChangeListener { v, hasFocus ->
            focusListener?.onFocusChange(v, hasFocus)
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                if (hasFocus) {
                    v.animate().alpha(1f).setDuration(200).start()
                } else {
                    v.animate().alpha(0.85f).setDuration(200).start()
                }
            } else {
                v.alpha = if (hasFocus) 1f else 0.85f
            }
        }
    }

    private fun setupDpadLongPress(view: View, callback: () -> Boolean) {
        val runnable = Runnable { callback() }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    KeyEvent.ACTION_UP -> {
                        view.removeCallbacks(runnable)
                    }
                }
            }
            false
        }
    }

    inner class MediaViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            if (matchParent) itemView.updateLayoutParams { width = -1 }
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            itemView.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
            setupDpadLongPress(itemView) { longClicked(bindingAdapterPosition) }
            applyFocusWithFade(itemView, binding.itemCompactCard)
        }
    }

    inner class MediaLargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            itemView.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
            setupDpadLongPress(itemView) { longClicked(bindingAdapterPosition) }
            applyFocusWithFade(itemView, binding.root)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageViewHolder(val binding: ItemMediaPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            applyFocusWithFade(itemView, binding.root)
            itemView.setOnTouchListener { _, _ -> true }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
            setupDpadLongPress(itemView) { longClicked(bindingAdapterPosition) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageSmallViewHolder(val binding: ItemMediaPageSmallBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            binding.itemCompactTitleContainer.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            applyFocusWithFade(itemView, binding.root)
            itemView.setOnTouchListener { _, _ -> true }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
            setupDpadLongPress(itemView) { longClicked(bindingAdapterPosition) }
        }
    }

    inner class MediaLandscapeViewHolder(val binding: ItemMediaCompactLandBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            if (matchParent) itemView.updateLayoutParams { width = -1 }
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            itemView.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
            setupDpadLongPress(itemView) { longClicked(bindingAdapterPosition) }
            applyFocusWithFade(itemView, binding.itemCompactCard)
        }
    }

    private fun bindLandscape(
        holder: MediaLandscapeViewHolder,
        position: Int,
        cardRoundness: Float
    ) {
        val b = holder.binding
        val media = mediaList?.getOrNull(position)
        if (media != null) {
            val landStyleRadius = when (rawCardStyle) {
                4 -> 24f
                6 -> 4f
                else -> cardRoundness
            }
            b.itemCompactCard.radius = landStyleRadius

            b.itemCompactScoreBG.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val m = (landStyleRadius * 0.3f).toInt().coerceAtLeast(4)
                topMargin = m
                marginEnd = m
            }
            val showScoreL = shouldShowTopBadge(1) && (media.userScore > 0 || (media.meanScore ?: 0) > 0)
            val showAiringL = shouldShowTopBadge(2) && media.status == currActivity()!!.getString(R.string.status_releasing)
            b.itemCompactScoreBG.isVisible = showScoreL || showAiringL
            b.itemCompactScore.text =
                ((if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore) / 10.0).toString()
            b.itemCompactScore.isVisible = showScoreL
            b.itemCompactScoreBG.findViewById<View>(R.id.itemCompactBroadcast)?.isVisible = showAiringL
            b.itemCompactScoreBG.findViewById<View>(R.id.imageView2)?.isVisible = showScoreL

            b.itemCompactImage.scaleType = ImageView.ScaleType.CENTER_CROP
            bindMediaCover(b.itemCompactImage, media, position)
            backdropJobs[position]?.cancel()
            backdropJobs[position] = activity.lifecycleScope.launch(Dispatchers.IO) {
                val backdropUrl = if (!media.tmdbType.isNullOrBlank()) {
                    val detail = runCatching { Tmdb.detail(media.tmdbType!!, media.id) }.getOrNull()
                    detail?.backdropPath?.let { Tmdb.imageUrl(it, 780) }
                } else {
                    AniZip.getBackdropUrl(media.id)
                }
                if (backdropUrl != null) {
                    withContext(Dispatchers.Main) {
                        b.itemCompactImage.loadImage(backdropUrl)
                    }
                }
            }
            val titlePos = cachedCardTitlePosition
            when (titlePos) {
                0 -> {
                    b.itemCompactOverlay.visibility = View.VISIBLE
                    setGradient(b.itemCompactOverlay)
                    b.itemCompactTitleBelow.visibility = View.GONE
                    logoJobs[position]?.cancel()
                    logoJobs[position] = activity.lifecycleScope.launch(Dispatchers.Main) {
                        val logoUrl = if (!PrefManager.getVal<Boolean>(PrefName.UseClearlogoInPosters)) null
                        else if (!media.tmdbType.isNullOrBlank()) {
                            runCatching { Tmdb.logoUrl(media.tmdbType!!, media.id) }.getOrNull()
                        } else {
                            LogoApi.getLogoUrl(media.id)
                        }
                        if (!logoUrl.isNullOrBlank()) {
                            b.itemCompactClearlogo.visibility = View.VISIBLE
                            b.itemCompactClearlogo.loadImage(logoUrl)
                            b.itemCompactOverlayTitle.visibility = View.GONE
                        } else {
                            b.itemCompactClearlogo.visibility = View.GONE
                            b.itemCompactOverlayTitle.visibility = View.VISIBLE
                            b.itemCompactOverlayTitle.text = media.userPreferredName
                        }
                    }
                }
                1 -> {
                    b.itemCompactOverlay.visibility = View.GONE
                    b.itemCompactClearlogo.visibility = View.GONE
                    b.itemCompactOverlayTitle.visibility = View.GONE
                    b.itemCompactTitleBelow.visibility = View.VISIBLE
                    b.itemCompactTitleBelow.text = media.userPreferredName
                }
                else -> {
                    b.itemCompactOverlay.visibility = View.GONE
                    b.itemCompactClearlogo.visibility = View.GONE
                    b.itemCompactOverlayTitle.visibility = View.GONE
                    b.itemCompactTitleBelow.visibility = View.GONE
                }
            }
            b.itemCompactScoreBG.visibility = View.VISIBLE
            val showBottomL = titlePos != 0
            if (showBottomL && shouldShowBottomBadge(1)) {
                val badgeL = b.root.findViewById<View>(R.id.subDubBadge)
                if (badgeL != null) {
                    SubDubCache.get(media.nameRomaji, activity.lifecycleScope, media.id) { info ->
                        bindSubDubBadge(badgeL, info, media)
                    }
                }
            } else {
                b.root.findViewById<View>(R.id.subDubBadge)?.visibility = View.GONE
            }
            if (showBottomL && shouldShowBottomBadge(2)) {
                val pbL = b.root.findViewById<View>(R.id.progressBadge)
                if (pbL != null) bindProgressBadge(pbL, media)
            } else {
                b.root.findViewById<View>(R.id.progressBadge)?.visibility = View.GONE
            }

        }
    }

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

    fun clicked(position: Int, itemCompactImage: ImageView?, bitmap: Bitmap? = null) {
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position)
            if (media != null && onItemClickOverride?.invoke(media) == true) return
            if (bitmap != null) MediaSingleton.bitmap = bitmap
            if (media?.tmdbType != null) {
                ContextCompat.startActivity(
                    activity,
                    Intent(activity, ani.sanin.cloudstream.TmdbDetailsActivity::class.java)
                        .putExtra(ani.sanin.cloudstream.TmdbDetailsActivity.ARG_MEDIA_TYPE, media.tmdbType)
                        .putExtra(ani.sanin.cloudstream.TmdbDetailsActivity.ARG_MEDIA_ID, media.id),
                    null
                )
                return
            }
            ContextCompat.startActivity(
                activity,
                Intent(activity, MediaDetailsActivity::class.java).putExtra(
                    "media",
                    media as Serializable
                ),
                if (itemCompactImage != null) {
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemCompactImage,
                        ViewCompat.getTransitionName(itemCompactImage)!!
                    ).toBundle()
                } else {
                    null
                }
            )
        }
    }

    fun longClicked(position: Int): Boolean {
        if (isOtherUser) return false
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return false
            if (activity.supportFragmentManager.findFragmentByTag("list") == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, "list")
                return true
            }
        }
        return false
    }

    fun getBitmapFromImageView(imageView: ImageView): Bitmap? {
        val drawable = imageView.drawable ?: return null

        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    fun resizeBitmap(source: Bitmap?, maxDimension: Int): Bitmap? {
        if (source == null) return null
        val width = source.width
        val height = source.height
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxDimension
            newHeight = (height * (maxDimension.toFloat() / width)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (width * (maxDimension.toFloat() / height)).toInt()
        }

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private fun bindSubDubBadge(badge: android.view.View, info: SubDubInfo?, media: Media) {
        scaleBadgeElements(badge)

        if (media.anime == null) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE

        val subIcon = badge.findViewById<android.view.View>(R.id.subDubSubIcon)
        val subCount = badge.findViewById<TextView>(R.id.subDubSubCount)
        val dubIcon = badge.findViewById<android.view.View>(R.id.subDubDubIcon)
        val dubCount = badge.findViewById<TextView>(R.id.subDubDubCount)
        val dividerMid = badge.findViewById<android.view.View>(R.id.subDubDividerMid)

        subIcon.visibility = View.VISIBLE
        subCount.visibility = View.VISIBLE
        subCount.text = (info?.sub ?: 0).takeIf { it > 0 }?.toString() ?: "~"

        dubIcon.visibility = View.VISIBLE
        dubCount.visibility = View.VISIBLE
        dubCount.text = (info?.dub ?: 0).takeIf { it > 0 }?.toString() ?: "~"

        dividerMid.visibility = View.VISIBLE
    }

    private fun shouldShowTopBadge(flag: Int): Boolean =
        PrefManager.getVal<Int>(PrefName.CardMetadataTop) and flag != 0

    private fun shouldShowBottomBadge(flag: Int): Boolean =
        PrefManager.getVal<Int>(PrefName.CardMetadataBottom) == flag

    private fun scaleBadgeElements(badge: View) {
        val scale = cachedCardSize / 2.25f
        badge.scaleX = scale
        badge.scaleY = scale
    }

    private fun bindProgressBadge(badge: View, media: Media) {
        scaleBadgeElements(badge)
        val watched = media.userProgress
        val isAnime = media.anime != null
        val totalEp = if (isAnime) media.anime?.totalEpisodes else null
        val nextAiring = if (isAnime) media.anime?.nextAiringEpisode else null
        val isReleasing = media.status == currActivity()?.getString(R.string.status_releasing)

        val isTmdbTv = !isAnime && media.tmdbType == "tv"
        val initialReleased = when {
            isAnime && isReleasing && (nextAiring ?: 0) > 1 -> (nextAiring ?: 1) - 1
            isAnime -> totalEp
            !isTmdbTv -> 1
            else -> null
        }

        fun render(released: Int?) {
            val allReleased = isAnime && totalEp != null && released != null && released >= totalEp
            val timeUntil = if (isAnime && isReleasing) media.timeUntilAiring else null

            val hasReleased = released != null && released > 0
            val hasTT = timeUntil != null && timeUntil > 0

            if ((watched == null || watched <= 0) && !hasReleased && !hasTT) { badge.visibility = View.GONE; return }

            val hasWatched = watched != null && watched > 0
            if (hasWatched && !hasReleased && !hasTT) { badge.visibility = View.GONE; return }

            badge.visibility = View.VISIBLE
            val watchedIcon   = badge.findViewById<android.view.View>(R.id.progressWatchedIcon)
            val watchedCount  = badge.findViewById<TextView>(R.id.progressWatchedCount)
            val releasedIcon  = badge.findViewById<android.view.View>(R.id.progressReleasedIcon)
            val releasedCount = badge.findViewById<TextView>(R.id.progressReleasedCount)
            val midDivider    = badge.findViewById<android.view.View>(R.id.progressDividerMid)
            val dividerTT     = badge.findViewById<android.view.View>(R.id.progressDividerTT)
            val ttText        = badge.findViewById<TextView>(R.id.progressTT)

            watchedIcon.visibility = if (hasWatched) View.VISIBLE else View.GONE
            watchedCount.visibility = View.VISIBLE
            watchedCount.text = if (hasWatched) watched.toString() else "~"

            releasedIcon.visibility = if (hasReleased && !allReleased) View.VISIBLE else View.GONE
            releasedCount.visibility = if (hasReleased) View.VISIBLE else View.GONE
            releasedCount.text = if (hasReleased) released.toString() else "~"

            if (hasTT && !allReleased) {
                val DAY_MILLIS = 86_400_000L
                val HOUR_MILLIS = 3_600_000L
                val days  = timeUntil!! / DAY_MILLIS
                val hours = (timeUntil % DAY_MILLIS) / HOUR_MILLIS
                ttText.text = if (days > 0) "${days}d ${hours}h" else "${hours}h"
            }
            dividerTT.visibility = if (hasTT && hasReleased && !allReleased) View.VISIBLE else View.GONE
            ttText.visibility = if (hasTT) View.VISIBLE else View.GONE

            val visibleSections = 1 + listOf(hasReleased, hasTT).count { it }
            midDivider.visibility = if (visibleSections >= 2) View.VISIBLE else View.GONE
        }

        render(initialReleased)

        if (isTmdbTv) {

            val tag = "tv:${media.id}"
            badge.tag = tag
            activity.lifecycleScope.launch {
                val detail = runCatching { Tmdb.detail("tv", media.id) }.getOrNull()

                val aired = detail?.nextEpisodeToAir?.episodeNumber
                    ?.takeIf { it > 1 }?.minus(1)
                    ?: detail?.numberOfEpisodes?.takeIf { it > 0 }
                badge.post {
                    if (badge.tag == tag) render(aired)
                }
            }
        }
    }

}
