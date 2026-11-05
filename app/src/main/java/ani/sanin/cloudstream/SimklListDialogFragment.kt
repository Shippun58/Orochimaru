package ani.sanin.cloudstream

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.BottomSheetMediaListBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimklListDialogFragment : DialogFragment() {

    private var _binding: BottomSheetMediaListBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_MEDIA_TYPE = "mediaType"
        private const val ARG_MEDIA_ID = "mediaId"
        private const val ARG_TITLE = "title"
        private const val ARG_YEAR = "year"
        private const val ARG_IMDB_ID = "imdbId"
        private const val ARG_COVER_URL = "coverUrl"
        private const val ARG_BACKDROP_URL = "backdropUrl"
        private const val ARG_CURRENT_STATUS = "currentStatus"
        private const val ARG_CURRENT_RATING = "currentRating"

        fun newInstance(
            mediaType: String,
            mediaId: Int,
            title: String,
            year: Int?,
            imdbId: String?,
            coverUrl: String?,
            backdropUrl: String? = null,
            currentStatus: String?,
            currentRating: Int = 0
        ): SimklListDialogFragment {
            return SimklListDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MEDIA_TYPE, mediaType)
                    putInt(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                    putInt(ARG_YEAR, year ?: 0)
                    putString(ARG_IMDB_ID, imdbId)
                    putString(ARG_COVER_URL, coverUrl)
                    putString(ARG_BACKDROP_URL, backdropUrl)
                    putString(ARG_CURRENT_STATUS, currentStatus)
                    putInt(ARG_CURRENT_RATING, currentRating)
                }
            }
        }
    }

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
            controller.isAppearanceLightNavigationBars =
                androidx.core.graphics.ColorUtils.calculateLuminance(surfaceColor) > 0.5
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

    private var isSaving = false

    private fun simklLabel(status: String): String = when (status) {
        "watching" -> "Watching"
        "plantowatch" -> "Plan to Watch"
        "completed" -> "Completed"
        "dropped" -> "Dropped"
        "hold" -> "On Hold"
        "notinteresting", "na" -> "Not Interested"
        else -> status
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        GlassEffectManager.applyGlassToSheet(binding.mediaListContainer, GlassComponent.ListEditor, 16f)

        val mediaType = arguments?.getString(ARG_MEDIA_TYPE) ?: "tv"
        val mediaId = arguments?.getInt(ARG_MEDIA_ID, -1) ?: -1
        val title = arguments?.getString(ARG_TITLE) ?: ""
        val year = arguments?.getInt(ARG_YEAR, 0)?.takeIf { it > 0 }
        val imdbId = arguments?.getString(ARG_IMDB_ID)
        val coverUrl = arguments?.getString(ARG_COVER_URL)
        val currentStatus = arguments?.getString(ARG_CURRENT_STATUS)

        binding.mediaListProgressBar.visibility = View.VISIBLE
        binding.mediaListLayout.visibility = View.GONE
        binding.mediaListBannerContainer.visibility = View.VISIBLE

        val backdropUrl = arguments?.getString(ARG_BACKDROP_URL)
        val bannerUrl = backdropUrl ?: coverUrl
        if (!bannerUrl.isNullOrBlank()) {
            binding.mediaListBanner.loadImage(bannerUrl)
        }

        val simklStatuses = listOf("watching", "plantowatch", "completed", "dropped", "hold", "na")
        val simklStatusLabels = listOf("Watching", "Plan to Watch", "Completed", "Dropped", "On Hold", "Not Interested")

        binding.mediaListStatusGroup.removeAllViews()
        var selectedStatus = currentStatus ?: "plantowatch"

        simklStatusLabels.forEachIndexed { index, label ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                tag = simklStatuses[index]
                isCheckable = true
                isClickable = true
                isFocusable = true
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                if (simklStatuses[index] == selectedStatus) {
                    isChecked = true
                }
            }
            binding.mediaListStatusGroup.addView(chip)
        }

        val currentRating = arguments?.getInt(ARG_CURRENT_RATING, 0) ?: 0
        if (currentRating > 0) {
            binding.mediaListScore.setText(currentRating.toString())
        }
        binding.mediaListScore.filters = arrayOf(
            ani.sanin.InputFilterMinMax(0.0, 10.0),
            android.text.InputFilter.LengthFilter(4)
        )
        binding.mediaListScoreLayout.suffixTextView.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.mediaListScoreLayout.suffixTextView.gravity = Gravity.CENTER

        binding.mediaListProgressLayout.visibility = View.GONE
        binding.mediaListVolumeProgressLayout.visibility = View.GONE
        binding.mediaListStartLayout.visibility = View.GONE
        binding.mediaListEndLayout.visibility = View.GONE
        binding.mediaListRepeatLayout.visibility = View.GONE
        binding.mediaListNotes.visibility = View.GONE
        binding.mediaListPrivate.visibility = View.GONE
        binding.mediaListShow.visibility = View.GONE
        binding.mediaListAddCustomList.visibility = View.GONE
        binding.mediaListCustomListContainer.visibility = View.GONE

        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE

        binding.mediaListStatusGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val checkedChip = group.findViewById<com.google.android.material.chip.Chip>(checkedIds[0])
                if (checkedChip != null) {
                    selectedStatus = checkedChip.tag?.toString() ?: "plantowatch"
                }
            }
        }

        binding.mediaListSave.setOnClickListener {
            if (isSaving) return@setOnClickListener
            isSaving = true
            binding.mediaListSave.isEnabled = false
            var applied: String? = null
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    Logger.log("SimklListDialog: saving status=$selectedStatus for '$title' (tmdb=$mediaId)")
                    val score = _binding?.mediaListScore?.text.toString().toDoubleOrNull()?.let {
                        if (it > 0) Math.round(it).toInt().coerceIn(1, 10) else 0
                    } ?: 0
                    applied = Simkl.setListStatus(
                        type = mediaType,
                        title = title,
                        year = year,
                        tmdbId = mediaId,
                        imdbId = imdbId,
                        status = selectedStatus,
                        rating = score
                    )
                }
                withContext(Dispatchers.Main) {
                    Refresh.all()
                    val requestedLabel = simklStatusLabels.getOrElse(
                        simklStatuses.indexOf(selectedStatus)
                    ) { selectedStatus }
                    val appliedStatus = applied
                    val message = if (appliedStatus != null && appliedStatus != selectedStatus) {
                        "Simkl set to: ${simklLabel(appliedStatus)} (can't set $requestedLabel while airing)"
                    } else {
                        "List updated to: $requestedLabel"
                    }
                    snackString(message)
                    dismissAllowingStateLoss()
                }
            }
        }

        binding.mediaListDelete.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    Logger.log("SimklListDialog: removing '$title' from list")
                    Simkl.removeFromList(type = mediaType, tmdbId = mediaId, imdbId = imdbId)
                }
                withContext(Dispatchers.Main) {
                    Refresh.all()
                    snackString("Removed from list")
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
