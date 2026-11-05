package ani.sanin.cloudstream

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputFilter.LengthFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.sanin.InputFilterMinMax
import ani.sanin.R
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.BottomSheetMediaListBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbListDialogFragment : DialogFragment() {

    private var _binding: BottomSheetMediaListBinding? = null
    private val binding get() = _binding!!

    var onSaved: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val widthPx = (resources.displayMetrics.widthPixels * 0.80f).toInt()
            w.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.5f)
            w.statusBarColor = Color.TRANSPARENT
            val surfaceColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                w.navigationBarColor = surfaceColor
            }
            WindowInsetsCompat.Type.navigationBars()
            val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
            controller.isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(surfaceColor) > 0.5
        }
        GlassEffectManager.applyGlassToSheet(binding.mediaListContainer, GlassComponent.ListEditor, 16f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMediaListBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }
        GlassEffectManager.applyGlassToSheet(binding.mediaListContainer, GlassComponent.ListEditor, 16f)

        val type = arguments?.getString("type") ?: "movie"
        val tmdbId = arguments?.getInt("tmdbId") ?: 0
        val imdbId = arguments?.getString("imdbId")
        val anilistId = arguments?.getInt("anilistId")?.takeIf { it != 0 }
        val title = arguments?.getString("title") ?: ""
        val year = arguments?.getInt("year") ?: 0
        val cover = arguments?.getString("cover")
        val totalEpisodes = arguments?.getInt("totalEpisodes")?.takeIf { it > 0 }

        val scope = viewLifecycleOwner.lifecycleScope
        val statusStrings = resources.getStringArray(R.array.status_anime)

        binding.mediaListBannerContainer.visibility = View.VISIBLE
        binding.mediaListBanner.loadImage(cover)
        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE

        binding.mediaListVolumeProgressLayout.visibility = View.GONE
        binding.mediaListExpandable.visibility = View.GONE

        binding.mediaListScore.filters = arrayOf(
            InputFilterMinMax(0.0, 10.0),
            android.text.InputFilter.LengthFilter(4)
        )

        if (totalEpisodes != null && type == "tv") {
            binding.mediaListProgressLayout.visibility = View.VISIBLE
            binding.mediaListIncrement.visibility = View.VISIBLE
            binding.mediaListProgress.setText("")
            binding.mediaListProgress.filters = arrayOf(
                InputFilterMinMax(0.0, totalEpisodes.toDouble(), binding.mediaListStatusGroup),
                LengthFilter(totalEpisodes.toString().length)
            )
            binding.mediaListProgressLayout.suffixText = " / $totalEpisodes"
            binding.mediaListProgressLayout.suffixTextView.updateLayoutParams {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.mediaListProgressLayout.suffixTextView.gravity = Gravity.CENTER
            binding.mediaListIncrement.setOnClickListener {
                val cur = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
                if (cur < totalEpisodes) binding.mediaListProgress.setText((cur + 1).toString())
            }
        } else {
            binding.mediaListProgressLayout.visibility = View.GONE
            binding.mediaListIncrement.visibility = View.GONE
        }

        scope.launch(Dispatchers.IO) {
            val currentStatus = runCatching {
                Simkl.getMediaStatus(type, tmdbId, imdbId, anilistId)
            }.getOrNull()
            val currentProgress = if (type == "tv") {
                runCatching {
                    Simkl.getProgress(type, tmdbId, imdbId, anilistId)
                }.getOrNull()
            } else null
            val currentRating = runCatching {
                Simkl.getMediaRating(type, tmdbId, imdbId, anilistId)
            }.getOrNull()

            withContext(Dispatchers.Main) {

                val currentIdx = simklStatusToIndex(currentStatus)
                binding.mediaListStatusGroup.removeAllViews()
                statusStrings.forEachIndexed { index, label ->
                    val chip = Chip(requireContext()).apply {
                        text = label
                        tag = label
                        isCheckable = true
                        isClickable = true
                        isFocusable = true
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    }
                    binding.mediaListStatusGroup.addView(chip)
                    if (index == currentIdx) chip.isChecked = true
                }
                FocusEffectUtil.applyFocusListener(
                    *(0 until binding.mediaListStatusGroup.childCount)
                        .map { binding.mediaListStatusGroup.getChildAt(it) }.toTypedArray()
                )

                if (currentProgress != null && currentProgress > 0 && totalEpisodes != null && type == "tv") {
                    binding.mediaListProgress.setText(currentProgress.toString())
                }

                if (currentRating != null && currentRating > 0) {
                    binding.mediaListScore.setText(currentRating.toString())
                }
            }
        }

        var saving = false
        binding.mediaListSave.setOnClickListener {
            if (saving) return@setOnClickListener
            saving = true
            binding.mediaListSave.isEnabled = false
            val checkedId = binding.mediaListStatusGroup.checkedChipId
            val label = (if (checkedId != -1)
                binding.mediaListStatusGroup.findViewById<Chip>(checkedId)?.text?.toString()
            else statusStrings[0]) ?: statusStrings[0]
            val idx = statusStrings.indexOf(label).coerceAtLeast(0)
            val simklStatus = SIMKL_STATUS_BY_INDEX[idx]
            val progress = if (type == "tv" && totalEpisodes != null) {
                val typed = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0

                if (typed == 0 && simklStatus == "completed") totalEpisodes else typed
            } else 0

            val score = binding.mediaListScore.text.toString().toDoubleOrNull()
                ?.let { if (it > 0) Math.round(it).toInt().coerceIn(1, 10) else 0 }
                ?: 0

            scope.launch(Dispatchers.IO) {

                if (type == "tv" && progress > 0) {
                    runCatching {
                        Simkl.setProgress(
                            type = type, title = title, year = year,
                            tmdbId = tmdbId, imdbId = imdbId,
                            anilistId = anilistId, episodeNum = progress
                        )
                    }
                }

                val applied = runCatching {
                    Simkl.setListStatus(
                        type = type, title = title, year = year,
                        tmdbId = tmdbId, imdbId = imdbId, status = simklStatus,
                        anilistId = anilistId, rating = score
                    )
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    onSaved?.invoke()
                    val message = if (applied != null && applied != simklStatus) {
                        "Simkl set to ${labelForSimklStatus(applied)} (can't set $label while airing)"
                    } else {
                        "Saved to Simkl: $label"
                    }
                    snackString(message)
                    dismissAllowingStateLoss()
                }
            }
        }

        binding.mediaListDelete.setOnClickListener {
            if (saving) return@setOnClickListener
            saving = true
            binding.mediaListDelete.isEnabled = false
            scope.launch(Dispatchers.IO) {
                runCatching { Simkl.removeFromList(type, tmdbId, imdbId, anilistId) }
                withContext(Dispatchers.Main) {
                    onSaved?.invoke()
                    snackString("Removed from Simkl")
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val SIMKL_STATUS_BY_INDEX =
            arrayOf("plantowatch", "watching", "completed", "watching", "hold", "dropped")

        fun simklStatusToIndex(s: String?): Int = when (s) {
            "watching" -> 1
            "completed" -> 2
            "plantowatch" -> 0
            "hold" -> 4
            "dropped", "notinteresting" -> 5
            else -> 0
        }

        fun labelForSimklStatus(s: String): String = when (s) {
            "plantowatch", "plantolisten" -> "PLANNING"
            "watching", "reading" -> "WATCHING"
            "completed" -> "COMPLETED"
            "hold" -> "PAUSED"
            "dropped", "notinteresting" -> "DROPPED"
            else -> s.uppercase()
        }

        fun newInstance(
            type: String,
            tmdbId: Int,
            imdbId: String?,
            anilistId: Int?,
            title: String,
            year: Int?,
            cover: String?,
            totalEpisodes: Int?
        ): TmdbListDialogFragment {
            val f = TmdbListDialogFragment()
            f.arguments = Bundle().apply {
                putString("type", type)
                putInt("tmdbId", tmdbId)
                putString("imdbId", imdbId)
                putInt("anilistId", anilistId ?: 0)
                putString("title", title)
                putInt("year", year ?: 0)
                putString("cover", cover)
                putInt("totalEpisodes", totalEpisodes ?: -1)
            }
            return f
        }
    }
}
