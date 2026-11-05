package ani.sanin.media.anime

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.lifecycle.coroutineScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.updateProgress
import ani.sanin.databinding.ItemEpisodeCompactBinding
import ani.sanin.databinding.ItemEpisodeGridBinding
import ani.sanin.databinding.ItemEpisodeStripBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaNameAdapter
import ani.sanin.setAnimation
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.customAlertDialog
import ani.sanin.getThemeColor
import ani.sanin.stripDividerGradient
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.SizeFormatter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import ani.sanin.util.EPISODE_BLUR_RADIUS
import ani.sanin.util.EPISODE_BLUR_SAMPLING
import ani.sanin.util.StackBlurTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.NumberPicker
import ani.sanin.currContext

fun handleProgress(cont: LinearLayout, bar: View, empty: View, mediaId: Int, ep: String) {
    val cleanEp = MediaNameAdapter.findEpisodeNumber(ep)?.let {
        if (it % 1 == 0f) it.toInt().toString() else it.toString()
    }
    val curr = PrefManager.getNullableCustomVal("${mediaId}_${ep}", null, Long::class.java)
        ?: cleanEp?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}", null, Long::class.java) }
    val max = PrefManager.getNullableCustomVal("${mediaId}_${ep}_max", null, Long::class.java)
        ?: cleanEp?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}_max", null, Long::class.java) }
    if (curr != null && max != null && max > 0L) {
        cont.visibility = View.VISIBLE
        val div = (curr.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val barParams = bar.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        bar.layoutParams = barParams
        val params = empty.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f - div
        empty.layoutParams = params
    } else {
        cont.visibility = View.GONE
    }
}

private fun watchedEpisodeNumber(ep: Episode): Float =
    MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f

@OptIn(UnstableApi::class)
class EpisodeAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: AnimeWatchFragment,
    var arr: List<Episode> = arrayListOf(),
    var offlineMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val context = fragment.requireContext()
    private var cachedBlurUnwatched = PrefManager.getVal<Boolean>(PrefName.BlurUnwatchedEpisodes)
    private var cachedGreyWatched = PrefManager.getVal<Boolean>(PrefName.GreyWatchedEpisodes)

    fun refreshCache() {
        cachedBlurUnwatched = PrefManager.getVal(PrefName.BlurUnwatchedEpisodes)
        cachedGreyWatched = PrefManager.getVal(PrefName.GreyWatchedEpisodes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return (when (viewType) {
            1 -> EpisodeGridViewHolder(
                ItemEpisodeGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> EpisodeCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            3 -> EpisodeStripViewHolder(
                ItemEpisodeStripBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        })
    }

    override fun getItemViewType(position: Int): Int {
        return if (type == 0) 3 else type
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindViewHolder(holder, position, false)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bindViewHolder(holder, position, false)
        } else {
            bindViewHolder(holder, position, payloads.contains("metadata"))
        }
    }

    private fun bindViewHolder(holder: RecyclerView.ViewHolder, position: Int, metadataOnly: Boolean) {
        val ep = arr[position]
        val title = if (!ep.title.isNullOrEmpty() && ep.title != "null") {
            ep.title?.let { MediaNameAdapter.removeEpisodeNumber(it) }
        } else {
            ep.number
        } ?: ""

        if (position == 0) {

            holder.itemView.nextFocusUpId = fragment.episodeUpTargetId()
        }
        if (position == arr.size - 1) {
            holder.itemView.nextFocusDownId = R.id.ScrollTop
        }

        when (holder) {
            is EpisodeGridViewHolder -> {
                val binding = holder.binding
                if (!metadataOnly) {
                    setAnimation(fragment.requireContext(), holder.binding.root)

                    val thumb = ep.thumb?.let {
                        if (it.url.isNotEmpty()) {
                            if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                                it.url
                            } else {
                                GlideUrl(it.url) { it.headers }
                            }
                        } else null
                    }
                    val isWatched = media.userProgress != null &&
                        watchedEpisodeNumber(ep) <= media.userProgress!!.toFloat()
                    val blurEnabled = !isWatched && cachedBlurUnwatched
                    val glideRequest = Glide.with(binding.itemMediaImage).load(thumb ?: media.cover)
                        .override(400, 0).diskCacheStrategy(DiskCacheStrategy.ALL)
                    if (blurEnabled) {
                        glideRequest.transform(StackBlurTransformation(EPISODE_BLUR_RADIUS, EPISODE_BLUR_SAMPLING)).into(binding.itemMediaImage)
                    } else {
                        glideRequest.into(binding.itemMediaImage)
                    }
                }

                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = title

                val ratingStr = ep.rating
                if (ratingStr != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ $ratingStr"
                    val ratingFloat = ratingStr.toFloatOrNull()
                    if (ratingFloat != null && ratingFloat > 7.9f) {
                        binding.itemEpisodeSparkle1.visibility = View.VISIBLE
                        binding.itemEpisodeSparkle2.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeSparkle1.visibility = View.GONE
                        binding.itemEpisodeSparkle2.visibility = View.GONE
                    }
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                    binding.itemEpisodeSparkle1.visibility = View.GONE
                    binding.itemEpisodeSparkle2.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }
                if (media.userProgress != null) {
                    val isWatched = watchedEpisodeNumber(ep) <= media.userProgress!!.toFloat()
                    val blurUnwatched = cachedBlurUnwatched
                    val greyWatched = cachedGreyWatched

                    if (isWatched) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                        binding.itemEpisodeDivider?.setBackgroundColor(
                            fragment.requireContext().getThemeColor(com.google.android.material.R.attr.colorOnBackground)
                        )
                        if (greyWatched) {
                            val cm = ColorMatrix().apply { setSaturation(0f) }
                            binding.itemMediaImage.colorFilter = ColorMatrixColorFilter(cm)
                            binding.itemEpisodeTitle.alpha = 0.5f
                            binding.itemEpisodeDate?.alpha = 0.5f
                            binding.itemEpisodeDivider?.alpha = 0.5f
                        } else {
                            binding.itemMediaImage.colorFilter = null
                            binding.itemEpisodeTitle.alpha = 1f
                            binding.itemEpisodeDate?.alpha = 1f
                            binding.itemEpisodeDivider?.alpha = 1f
                        }
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.itemEpisodeDivider?.setBackgroundColor(
                            fragment.requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimary)
                        )
                        if (blurUnwatched) {
                            val cm = ColorMatrix().apply { setSaturation(0.3f) }
                            binding.itemMediaImage.colorFilter = ColorMatrixColorFilter(cm)
                            binding.itemEpisodeTitle.alpha = 0.5f
                            binding.itemEpisodeDate?.alpha = 0.5f
                            binding.itemEpisodeDivider?.alpha = 1f
                        } else {
                            binding.itemMediaImage.colorFilter = null
                            binding.itemEpisodeTitle.alpha = 1f
                            binding.itemEpisodeDate?.alpha = 1f
                            binding.itemEpisodeDivider?.alpha = 1f
                        }
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
                bindDownloadButton(binding.itemEpisodeDownload, ep)
            }

            is EpisodeCompactViewHolder -> {
                val binding = holder.binding
                if (!metadataOnly) {
                    setAnimation(fragment.requireContext(), holder.binding.root)
                }
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeFillerView.isVisible = ep.filler
                if (media.userProgress != null) {
                    val isWatched = watchedEpisodeNumber(ep) <= media.userProgress!!.toFloat()
                    val blurUnwatched = cachedBlurUnwatched
                    val greyWatched = cachedGreyWatched

                    if (isWatched) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        if (greyWatched) {
                            binding.itemEpisodeNumber.alpha = 0.5f
                        } else {
                            binding.itemEpisodeNumber.alpha = 1f
                        }
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        if (blurUnwatched) {
                            binding.itemEpisodeNumber.alpha = 0.5f
                        } else {
                            binding.itemEpisodeNumber.alpha = 1f
                        }
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeStripViewHolder -> {
                val binding = holder.binding
                if (!metadataOnly) {
                    setAnimation(fragment.requireContext(), holder.binding.root)

                    val thumb = ep.thumb?.let {
                        if (it.url.isNotEmpty()) {
                            if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                                it.url
                            } else {
                                GlideUrl(it.url) { it.headers }
                            }
                        } else null
                    }
                    val isWatched = media.userProgress != null &&
                        watchedEpisodeNumber(ep) <= media.userProgress!!.toFloat()
                    val blurEnabled = !isWatched && cachedBlurUnwatched
                    val glideRequest = Glide.with(binding.itemMediaImage).load(thumb ?: media.cover)
                        .override(400, 0).diskCacheStrategy(DiskCacheStrategy.ALL)
                    if (blurEnabled) {
                        glideRequest.transform(StackBlurTransformation(EPISODE_BLUR_RADIUS, EPISODE_BLUR_SAMPLING)).into(binding.itemMediaImage)
                    } else {
                        glideRequest.into(binding.itemMediaImage)
                    }
                }

                binding.itemEpisodeTitle.text = title

                val ratingStr = ep.rating
                if (ratingStr != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ $ratingStr"
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }

                val desc = ep.desc
                binding.itemEpisodeDesc.text = desc ?: ""
                binding.itemEpisodeDesc.isVisible = !desc.isNullOrBlank()
                binding.itemEpisodeDesc.maxLines = 2
                binding.itemEpisodeDesc.alpha = 0.58f
                var expanded = false
                binding.itemEpisodeMore.setText(R.string.strips_more)
                binding.itemEpisodeMore.setOnClickListener {
                    expanded = !expanded
                    binding.itemEpisodeDesc.maxLines = if (expanded) 100 else 2
                    binding.itemEpisodeMore.setText(
                        if (expanded) R.string.strips_less else R.string.strips_more
                    )
                    binding.itemEpisodeDesc.post {
                        val layout = binding.itemEpisodeDesc.layout
                        val truncated = layout != null && layout.lineCount > 0 &&
                            (layout.getEllipsisCount(layout.lineCount - 1) > 0)
                        binding.itemEpisodeMore.isVisible = expanded || truncated
                    }
                }
                binding.itemEpisodeDesc.post {
                    val layout = binding.itemEpisodeDesc.layout
                    val truncated = layout != null && layout.lineCount > 0 &&
                        (layout.getEllipsisCount(layout.lineCount - 1) > 0)
                    binding.itemEpisodeMore.isVisible = truncated
                }

                if (media.userProgress != null) {
                    val isWatched = watchedEpisodeNumber(ep) <= media.userProgress!!.toFloat()
                    val blurUnwatched = cachedBlurUnwatched
                    val greyWatched = cachedGreyWatched

                    if (isWatched) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                        binding.itemEpisodeDivider?.background =
                            fragment.requireContext().stripDividerGradient()
                        if (greyWatched) {
                            val cm = ColorMatrix().apply { setSaturation(0f) }
                            binding.itemMediaImage.colorFilter = ColorMatrixColorFilter(cm)
                            binding.itemEpisodeTitle.alpha = 0.5f
                            binding.itemEpisodeDate.alpha = 0.5f
                            binding.itemEpisodeDesc.alpha = 0.3f
                            binding.itemEpisodeDivider?.alpha = 0.3f
                        } else {
                            binding.itemMediaImage.colorFilter = null
                            binding.itemEpisodeTitle.alpha = 1f
                            binding.itemEpisodeDate.alpha = 1f
                            binding.itemEpisodeDesc.alpha = 0.58f
                            binding.itemEpisodeDivider?.alpha = 0.4f
                        }
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.itemEpisodeDivider?.background =
                            fragment.requireContext().stripDividerGradient()
                        if (blurUnwatched) {
                            val cm = ColorMatrix().apply { setSaturation(0.3f) }
                            binding.itemMediaImage.colorFilter = ColorMatrixColorFilter(cm)
                            binding.itemEpisodeTitle.alpha = 0.5f
                            binding.itemEpisodeDate.alpha = 0.5f
                            binding.itemEpisodeDesc.alpha = 0.3f
                            binding.itemEpisodeDivider?.alpha = 0.4f
                        } else {
                            binding.itemMediaImage.colorFilter = null
                            binding.itemEpisodeTitle.alpha = 1f
                            binding.itemEpisodeDate.alpha = 1f
                            binding.itemEpisodeDesc.alpha = 0.58f
                            binding.itemEpisodeDivider?.alpha = 0.4f
                        }
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }

                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
                bindDownloadButton(binding.itemEpisodeDownload, ep)
            }
        }
    }

    private fun bindDownloadButton(button: android.widget.ImageView, ep: Episode) {
        if (fragment.downloadMode) {
            button.visibility = View.VISIBLE
            button.setOnClickListener { fragment.onEpisodeDownload(ep.number) }
        } else {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = arr.size
    private val downloadedEpisodes = mutableSetOf<String>()

    fun startDownload(episodeNumber: String) {
        if (downloadedEpisodes.contains(episodeNumber))
                return
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = ""
            notifyItemChanged(position)
        }
    }

    fun addToDownloadedEpisodes(episodeNumber: String, size: Double) {
        downloadedEpisodes.add(episodeNumber)

        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Downloaded" + ": (${"%.1f".format(size)} MB)"
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(episodeNumber: String) {
        downloadedEpisodes.remove(episodeNumber)

        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position)
        }
    }

    fun purgeDownload(episodeNumber: String) {
        downloadedEpisodes.remove(episodeNumber)

        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Failed"
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(episodeNumber: String, progress: Int) {
        updateDownloadProgress(episodeNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        episodeNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {

        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = buildDownloadProgressText(
                progress,
                downloadedBytes,
                estimatedTotalBytes
            )

            notifyItemChanged(position)
        }
    }

    private fun buildDownloadProgressText(
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ): String {
        val hasDownloaded = downloadedBytes > 0L
        val hasEstimatedTotal = estimatedTotalBytes > 0L
        return if (hasDownloaded && hasEstimatedTotal) {
            "Downloading: $progress% (${SizeFormatter.formatBytes(downloadedBytes)} / ${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else if (hasEstimatedTotal) {
            "Downloading: $progress% (~${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else {
            "Downloading: $progress%"
        }
    }

    inner class EpisodeCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.isFocusable = true
            FocusEffectUtil.applyFocusListener(itemView, borderDp = 5f)
            itemView.nextFocusRightId = R.id.itemEpisodeCont
            itemView.setOnClickListener {
                if (bindingAdapterPosition in arr.indices)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
            itemView.setOnLongClickListener {
                if (bindingAdapterPosition in arr.indices) {
                    fragment.onEpisodeDownload(arr[bindingAdapterPosition].number)
                    true
                } else false
            }
        }
    }

    inner class EpisodeStripViewHolder(val binding: ItemEpisodeStripBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.isFocusable = true
            FocusEffectUtil.applyFocusListener(itemView, borderDp = 5f)
            itemView.setOnClickListener {
                if (bindingAdapterPosition in arr.indices)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
            itemView.setOnLongClickListener {
                if (bindingAdapterPosition in arr.indices) {
                    fragment.onEpisodeDownload(arr[bindingAdapterPosition].number)
                    true
                } else false
            }
        }
    }

    inner class EpisodeGridViewHolder(val binding: ItemEpisodeGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.isFocusable = true
            FocusEffectUtil.applyFocusListener(itemView, borderDp = 5f)
            itemView.nextFocusRightId = R.id.itemEpisodeCont
            itemView.setOnClickListener {
                if (bindingAdapterPosition in arr.indices)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
            itemView.setOnLongClickListener {
                if (bindingAdapterPosition in arr.indices) {
                    fragment.onEpisodeDownload(arr[bindingAdapterPosition].number)
                    true
                } else false
            }
        }
    }

    fun updateType(t: Int) {
        type = t
    }

    fun submitList(newList: List<Episode>, newType: Int) {
        if (type != newType) {
            type = newType
            arr = newList
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = arr.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                arr[oldPos].number == newList[newPos].number
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = arr[oldPos]
                val new = newList[newPos]
                return old.title == new.title &&
                    old.desc == new.desc &&
                    old.thumb?.url == new.thumb?.url &&
                    old.filler == new.filler &&
                    old.date == new.date &&
                    old.rating == new.rating &&
                    old.downloadProgress == new.downloadProgress
            }
        }, false)
        arr = newList
        diff.dispatchUpdatesTo(this)
    }
}
