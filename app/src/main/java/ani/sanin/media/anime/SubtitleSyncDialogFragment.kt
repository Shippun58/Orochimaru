package ani.sanin.media.anime

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.BottomSheetSubtitleSyncBinding
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.databinding.ItemSubtitleSyncBinding
import ani.sanin.media.MediaDetailsViewModel
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SyncCue(
    val text: String,
    val startTimeMs: Long,
    val durationMs: Long = 3000L
)

private const val PROGRESS_PAYLOAD = "sync_progress"

interface SubtitleSyncHost {
    fun getSyncPlayerPosition(): Long
    fun getSubtitleOffset(): Long
    fun setSubtitleOffset(offsetMs: Long)
    fun getSyncCues(): List<SyncCue>
}

class SubtitleSyncDialogFragment : DialogFragment() {
    private var _binding: BottomSheetSubtitleSyncBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var currentOffset: Long = 0L

    private var openingOffset: Long = 0L
    private var updateJob: Job? = null
    private var adapter: SyncAdapter? = null
    private var prevPlayingIndex: Int = -1

    private var userScrolling = false
    private var resumeAutoScrollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val widthPx = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            w.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.5f)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
        GlassEffectManager.applyGlassToSheet(binding.syncRoot, GlassComponent.SubtitleSync, 16f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubtitleSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentOffset = (requireActivity() as? SubtitleSyncHost)?.getSubtitleOffset() ?: 0L
        openingOffset = currentOffset
        setupViews()
        startUpdateLoop()
    }

    private fun onCueClicked(cue: SyncCue) {
        val playerPos = getPlayerPosition()
        currentOffset = playerPos - cue.startTimeMs
        binding.syncOffsetInput.setText(currentOffset.toString())
        applyOffset()
    }

    private fun styleSyncControls() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val radius = 16f * density
        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
            setColor(Color.TRANSPARENT)
            setCornerRadius(radius)
            setStroke(
                (2f * density).toInt(),
                FocusEffectUtil.getPrimaryColor(ctx)
            )
        }
        listOf(
            binding.syncSubtractMore, binding.syncSubtract, binding.syncAdd,
            binding.syncAddMore, binding.syncCancel, binding.syncReset, binding.syncApply
        ).forEach { b ->
            b.background = android.graphics.drawable.GradientDrawable().apply {
                setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                setColor(Color.WHITE)
                setCornerRadius(radius)
            }
            b.backgroundTintList = null
            b.setTextColor(Color.BLACK)
        }
        binding.syncOffsetInput.background = inputBg
        binding.syncOffsetInput.setTextColor(Color.WHITE)
        binding.syncOffsetInput.setHintTextColor(0xFF666666.toInt())
    }

    private fun setupViews() {
        styleSyncControls()
        binding.syncOffsetInput.setText(currentOffset.toString())
        binding.syncOffsetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toLongOrNull()?.let { offset ->
                    currentOffset = offset
                    updateStatusText()
                }
            }
        })

        applySyncFocus(binding.syncSubtractMore)
        applySyncFocus(binding.syncSubtract)
        applySyncFocus(binding.syncOffsetInput)
        applySyncFocus(binding.syncAdd)
        applySyncFocus(binding.syncAddMore)
        applySyncFocus(binding.syncCancel)
        applySyncFocus(binding.syncReset)
        applySyncFocus(binding.syncApply)

        TvKeyboardUtil.setupTvInput(binding.syncOffsetInput)
        dialog?.window?.let { TvKeyboardUtil.retainWindowFocus(it) }

        binding.syncSubtractMore.setOnClickListener { changeBy(-1000L) }
        binding.syncSubtract.setOnClickListener { changeBy(-100L) }
        binding.syncAdd.setOnClickListener { changeBy(100L) }
        binding.syncAddMore.setOnClickListener { changeBy(1000L) }
        binding.syncApply.setOnClickListener { applyOffset() }
        binding.syncReset.setOnClickListener { resetOffset() }
        binding.syncCancel.setOnClickListener { dismiss() }

        binding.subtitleSyncRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.subtitleSyncRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {

                        userScrolling = true
                        resumeAutoScrollJob?.cancel()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {

                        resumeAutoScrollJob?.cancel()
                        resumeAutoScrollJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            delay(3000)
                            userScrolling = false
                        }
                    }
                    else -> {}
                }
            }
        })

        val cues = (requireActivity() as? SubtitleSyncHost)?.getSyncCues() ?: emptyList()

        if (cues.isEmpty()) {
            binding.noSubtitlesNotice.isVisible = true
            binding.subtitleSyncRecycler.isVisible = false
        } else {
            binding.noSubtitlesNotice.isVisible = false
            binding.subtitleSyncRecycler.isVisible = true
            adapter = SyncAdapter(
                cues,
                getPlayerPosition(),
                onCueClick = ::onCueClicked,
                onPlayingIndexChanged = ::onPlayingIndexChanged
            )
            binding.subtitleSyncRecycler.adapter = adapter
            scrollToCurrentCue()
        }

        updateStatusText()
    }

    private fun applySyncFocus(view: View) {
        val borderWidthPx = 3f
        val savedFg = mutableMapOf<View, android.graphics.drawable.Drawable?>()
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val primaryColor = FocusEffectUtil.getPrimaryColor(v.context)
                val border = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_DIP, borderWidthPx,
                            v.resources.displayMetrics
                        ).toInt(),
                        primaryColor
                    )
                    setCornerRadius(16f * v.resources.displayMetrics.density)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    savedFg[v] = v.foreground
                    v.foreground = border
                }
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                } else {
                    v.scaleX = 1.08f
                    v.scaleY = 1.08f
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    v.foreground = savedFg.remove(v)
                }
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                } else {
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
        }
    }

    private fun changeBy(delta: Long) {
        val current = (binding.syncOffsetInput.text?.toString()?.toLongOrNull() ?: 0) + delta
        binding.syncOffsetInput.setText(current.toString())
    }

    private fun applyOffset() {
        (requireActivity() as? SubtitleSyncHost)?.setSubtitleOffset(currentOffset)
        dismiss()
    }

    private fun resetOffset() {

        currentOffset = openingOffset
        binding.syncOffsetInput.setText(currentOffset.toString())
        (requireActivity() as? SubtitleSyncHost)?.setSubtitleOffset(currentOffset)
        dismiss()
    }

    private fun getPlayerPosition(): Long {
        return (requireActivity() as? SubtitleSyncHost)?.getSyncPlayerPosition() ?: 0L
    }

    private fun updateStatusText() {
        val status = when {
            currentOffset > 0L -> "Offset: +${currentOffset}ms - Subtitles appear later"
            currentOffset < 0L -> "Offset: ${currentOffset}ms - Subtitles appear earlier"
            else -> "Offset: 0ms - In sync"
        }
        binding.syncStatusText.text = status
    }

    private fun scrollToCurrentCue() {
        val position = getPlayerPosition()
        val cues = (requireActivity() as? SubtitleSyncHost)?.getSyncCues() ?: return
        val index = cues.indexOfLast { position >= it.startTimeMs }
        if (index >= 0) {
            binding.subtitleSyncRecycler.post {
                centerOnIndex(index)
            }
        }
    }

    private fun onPlayingIndexChanged(index: Int) {
        if (index < 0) return
        if (userScrolling) return

        if (binding.subtitleSyncRecycler.focusedChild != null) return
        binding.subtitleSyncRecycler.post {
            centerOnIndex(index)
        }
    }

    private fun centerOnIndex(index: Int) {
        val recycler = binding.subtitleSyncRecycler
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val itemHeight = recycler.getChildAt(0)?.height?.takeIf { it > 0 }
            ?: (recycler.height / 3).takeIf { it > 0 }
            ?: 120
        lm.scrollToPositionWithOffset(index, recycler.height / 2 - itemHeight / 2)
    }

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                adapter?.updatePlayerPosition(getPlayerPosition())
                delay(500)
            }
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        resumeAutoScrollJob?.cancel()
        _binding = null
        super.onDestroy()
    }

    inner class SyncAdapter(
        private val cues: List<SyncCue>,
        initialPosition: Long,
        private val onCueClick: (SyncCue) -> Unit = {},
        private val onPlayingIndexChanged: (Int) -> Unit = {}
    ) : RecyclerView.Adapter<CueViewHolder>() {
        private var playerPositionMs: Long = initialPosition

        fun updatePlayerPosition(pos: Long) {
            playerPositionMs = pos
            val adjustedPos = pos - currentOffset

            var newPlayingIndex = -1
            cues.forEachIndexed { index, cue ->
                val isPlaying = adjustedPos in cue.startTimeMs..<(cue.startTimeMs + cue.durationMs)
                if (isPlaying) newPlayingIndex = index
            }

            if (prevPlayingIndex != newPlayingIndex) {
                if (prevPlayingIndex >= 0) notifyItemChanged(prevPlayingIndex)
                if (newPlayingIndex >= 0) notifyItemChanged(newPlayingIndex)
                prevPlayingIndex = newPlayingIndex
                if (newPlayingIndex >= 0) onPlayingIndexChanged(newPlayingIndex)
            } else if (newPlayingIndex >= 0) {

                notifyItemChanged(newPlayingIndex, PROGRESS_PAYLOAD)
            }
        }

        override fun onBindViewHolder(
            holder: CueViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {

            if (payloads.any { it == PROGRESS_PAYLOAD }) {
                val cue = cues[position]
                val adjustedPos = playerPositionMs - currentOffset
                val showProgress = adjustedPos in cue.startTimeMs..<(cue.startTimeMs + cue.durationMs)
                if (showProgress) {
                    val progressValue = ((adjustedPos - cue.startTimeMs) * 1000f / cue.durationMs).roundToInt()
                        .coerceIn(0, 1000)
                    if (holder.progressBar.progress != progressValue) {
                        holder.progressBar.progress = progressValue
                    }
                } else {

                    onBindViewHolder(holder, position)
                }
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CueViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemBinding = ItemSubtitleSyncBinding.inflate(inflater, parent, false)
            applyCardFocus(itemBinding.root, itemBinding.subtitleSyncCard)

            itemBinding.subtitleSyncProgress.progressTintList =
                android.content.res.ColorStateList.valueOf(
                    FocusEffectUtil.getPrimaryColor(itemBinding.root.context)
                )
            return CueViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: CueViewHolder, position: Int) {
            val cue = cues[position]
            val adjustedPos = playerPositionMs - currentOffset
            val isPlaying = adjustedPos in cue.startTimeMs..<(cue.startTimeMs + cue.durationMs)

            holder.textView.text = cue.text

            if (isPlaying) {
                holder.cardView.setCardBackgroundColor(Color.WHITE)
                holder.textView.setTextColor(Color.BLACK)
            } else {
                holder.cardView.setCardBackgroundColor(0)
                holder.textView.setTextColor(-0x555556)
            }

            val showProgress = isPlaying && isPlaying
            holder.progressBar.isVisible = showProgress
            if (showProgress) {
                val progressValue = ((adjustedPos - cue.startTimeMs) * 1000f / cue.durationMs).roundToInt()
                    .coerceIn(0, 1000)
                if (holder.progressBar.progress != progressValue) {
                    holder.progressBar.progress = progressValue
                }
            } else {
                holder.progressBar.progress = 0
            }

            val root = holder.itemBinding.root
            root.setOnClickListener { onCueClick(cue) }

            if (isPlaying) {
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    root.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                } else {
                    root.scaleX = 1.05f
                    root.scaleY = 1.05f
                }
            } else {
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    root.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                } else {
                    root.scaleX = 1f
                    root.scaleY = 1f
                }
            }
        }

        override fun getItemCount(): Int = cues.size
    }

    inner class CueViewHolder(val itemBinding: ItemSubtitleSyncBinding) :
        RecyclerView.ViewHolder(itemBinding.root) {
        val textView: TextView = itemBinding.subtitleSyncText
        val progressBar: ProgressBar = itemBinding.subtitleSyncProgress
        val cardView: androidx.cardview.widget.CardView = itemBinding.subtitleSyncCard
    }

    private fun applyCardFocus(focusView: View, borderView: View) {
        val defaultRadius = 18f
        val borderWidthPx = 3f
        val savedFg = mutableMapOf<View, android.graphics.drawable.Drawable?>()
        focusView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val primaryColor = FocusEffectUtil.getPrimaryColor(borderView.context)
                val border = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_DIP, borderWidthPx,
                            borderView.resources.displayMetrics
                        ).toInt(),
                        primaryColor
                    )
                    setCornerRadius(defaultRadius * borderView.resources.displayMetrics.density)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    savedFg[borderView] = borderView.foreground
                    borderView.foreground = border
                }
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    focusView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    focusView.scaleX = 1.05f
                    focusView.scaleY = 1.05f
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    borderView.foreground = savedFg.remove(borderView)
                }
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                    focusView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                } else {
                    focusView.scaleX = 1f
                    focusView.scaleY = 1f
                }
            }
        }
    }
}
