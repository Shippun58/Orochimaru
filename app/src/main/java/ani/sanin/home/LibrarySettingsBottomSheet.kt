package ani.sanin.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.R
import ani.sanin.databinding.BottomSheetLibrarySettingsBinding
import ani.sanin.getThemeColor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.chip.Chip

class LibrarySettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLibrarySettingsBinding? = null
    private val binding get() = _binding!!

    private var onSortChanged: ((String) -> Unit)? = null
    private var onGenreFilterChanged: ((String) -> Unit)? = null
    private var onNsfwChanged: ((Boolean) -> Unit)? = null
    private var allChip: Chip? = null

    companion object {
        const val TAG = "LibrarySettingsBottomSheet"

        fun newInstance(
            currentSort: String,
            filterItems: List<String>? = null,
            showNsfw: Boolean = true,
            onSortChanged: (String) -> Unit,
            onGenreFilterChanged: (String) -> Unit,
            onNsfwChanged: ((Boolean) -> Unit)? = null
        ): LibrarySettingsBottomSheet = LibrarySettingsBottomSheet().apply {
            this.onSortChanged = onSortChanged
            this.onGenreFilterChanged = onGenreFilterChanged
            this.onNsfwChanged = onNsfwChanged
            arguments = Bundle().apply {
                putString("sort", currentSort)
                putBoolean("showNsfw", showNsfw)
                putStringArrayList("filters", ArrayList(filterItems ?: emptyList()))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLibrarySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        val primary = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = ctx.getThemeColor(com.google.android.material.R.attr.colorOutline)
        val density = resources.displayMetrics.density

        binding.sheetHeaderIconBg.background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(ColorUtils.setAlphaComponent(primary, 40))
        }
        binding.sheetHeaderIcon.imageTintList = ColorStateList.valueOf(primary)

        val currentSort = arguments?.getString("sort") ?: "updatedAt"

        val sortButtons = linkedMapOf(
            binding.sortRecent to "updatedAt",
            binding.sortScore to "score",
            binding.sortTitle to "title",
            binding.sortRelease to "release"
        )
        sortButtons.forEach { (btn, sortKey) ->
            FocusEffectUtil.applyFocusListener(btn)
            btn.setOnClickListener {
                PrefManager.setVal(PrefName.AnimeListSortOrder, sortKey)
                updateSortHighlight(sortKey, primary, onSurface, outline)
                onSortChanged?.invoke(sortKey)
            }
        }
        updateSortHighlight(currentSort, primary, onSurface, outline)

        buildFilterChips()

        FocusEffectUtil.applyFocusListener(binding.clearAllBtn)
        binding.clearAllBtn.setOnClickListener {
            allChip?.isChecked = true
            PrefManager.setVal(PrefName.LibraryGenreFilter, "")
            onGenreFilterChanged?.invoke("All")
            if (binding.nsfwRow.visibility == View.VISIBLE) {
                binding.nsfwToggle.isChecked = false
                PrefManager.setVal(PrefName.LibraryNsfw, false)
                onNsfwChanged?.invoke(false)
            }
        }

        val showNsfw = arguments?.getBoolean("showNsfw") ?: true
        if (!showNsfw || onNsfwChanged == null) {
            binding.nsfwRow.visibility = View.GONE
        } else {
            binding.nsfwToggle.isSaveEnabled = false
            binding.nsfwToggle.isChecked = PrefManager.getVal<Boolean>(PrefName.LibraryNsfw)
            FocusEffectUtil.applyFocusListener(binding.nsfwToggle)
            binding.nsfwRow.setOnClickListener {
                binding.nsfwToggle.isChecked = !binding.nsfwToggle.isChecked
            }
            binding.nsfwToggle.setOnCheckedChangeListener { _, isChecked ->
                PrefManager.setVal(PrefName.LibraryNsfw, isChecked)
                onNsfwChanged?.invoke(isChecked)
            }
        }
    }

    private fun buildFilterChips() {
        val items = mutableListOf("All") + filterItems().filterNot { it.equals("All", true) }
        val current = PrefManager.getVal<String>(PrefName.LibraryGenreFilter)
        val ctx = requireContext()
        val primary = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = ctx.getThemeColor(com.google.android.material.R.attr.colorOutline)
        items.forEach { genre ->
            val chip = Chip(requireContext()).apply {
                text = genre
                isCheckable = true
                isFocusable = true
                checkedIcon = null
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(primary, Color.TRANSPARENT)
                )
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, onSurface)
                ))
                chipStrokeColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(primary, outline)
                )
                chipStrokeWidth = 1f * resources.displayMetrics.density
                isChecked = if (genre == "All") current.isBlank() else genre == current
            }
            if (genre == "All") allChip = chip
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) return@setOnCheckedChangeListener
                val selected = chip.text?.toString() ?: "All"
                PrefManager.setVal(PrefName.LibraryGenreFilter, if (selected == "All") "" else selected)
                onGenreFilterChanged?.invoke(selected)
            }
            FocusEffectUtil.applyFocusListener(chip)
            binding.genreChipGroup.addView(chip)
        }

        if (binding.genreChipGroup.checkedChipId == View.NO_ID && binding.genreChipGroup.childCount > 0) {
            (binding.genreChipGroup.getChildAt(0) as? Chip)?.isChecked = true
        }
    }

    private fun filterItems(): List<String> {
        arguments?.getStringArrayList("filters")?.takeIf { it.isNotEmpty() }?.let { return it }

        if ((arguments?.getBoolean("showNsfw") ?: true) == false) return emptyList()
        val stored = PrefManager.getVal<Set<String>>(PrefName.GenresList)
        return if (stored.isNullOrEmpty()) {
            listOf(
                "All", "Completed Movies", "Completed TV", "Watching",
                "Planning", "Paused", "Dropped", "Favourites"
            )
        } else {
            stored.toList().sorted()
        }
    }

    private fun updateSortHighlight(activeKey: String, primary: Int, onSurface: Int, outline: Int) {
        val mapping = mapOf(
            binding.sortRecent to "updatedAt",
            binding.sortScore to "score",
            binding.sortTitle to "title",
            binding.sortRelease to "release"
        )
        val density = resources.displayMetrics.density
        mapping.forEach { (btn, key) ->
            val active = key == activeKey
            btn.backgroundTintList = ColorStateList.valueOf(if (active) primary else Color.TRANSPARENT)
            btn.setTextColor(if (active) Color.WHITE else onSurface)
            btn.iconTint = ColorStateList.valueOf(if (active) Color.WHITE else onSurface)
            btn.strokeColor = ColorStateList.valueOf(if (active) primary else outline)
            btn.strokeWidth = if (active) 0 else (1 * density).toInt()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
