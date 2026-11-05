package ani.sanin.media

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.AutoCompleteTextView
import android.widget.PopupMenu
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.databinding.BottomSheetSearchFilterBinding
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.chip.Chip
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class SearchFilterBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchFilterBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(
            binding.resetSearchFilter,
            binding.countryFilter
        )
        return binding.root
    }

    private var selectedGenres = mutableListOf<String>()
    private var exGenres = mutableListOf<String>()
    private var selectedTags = mutableListOf<String>()
    private var exTags = mutableListOf<String>()
    private var adultOnly = false
    private var selectedSort: String? = null
    private var suppressSwitchCallback = false

    private fun startBounceZoomAnimation(view: View? = null) {
        if (!(PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FilterResetAnimations))) return
        val targetView = view ?: binding.resetSearchFilter
        val bounceZoomAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)
        targetView.startAnimation(bounceZoomAnimation)
    }

    private fun setupDnsDropdown(
        field: AutoCompleteTextView,
        titleRes: Int,
        items: Array<String>,
        current: String?
    ) {
        field.setAdapter(null)
        field.setOnClickListener {
            requireContext().customAlertDialog().apply {
                setTitle(titleRes)
                singleChoiceItems(items, items.indexOf(current)) { index ->
                    field.setText(items[index])
                    field.setSelection(items[index].length)
                }
                show()
            }
        }
    }

    private fun setupMultiSelectDropdown(
        field: AutoCompleteTextView,
        titleRes: Int,
        items: List<String>,
        selected: MutableList<String>,
        onCommitted: (MutableList<String>) -> Unit
    ) {
        field.setAdapter(null)
        field.setOnClickListener {
            val visible = items.filter { it.isNotBlank() }
            if (visible.isEmpty()) return@setOnClickListener
            val display = visible.map { label(it) }.toTypedArray()
            val initial = BooleanArray(display.size) { idx ->
                selected.any { it.equals(visible[idx], true) }
            }
            val working = initial.copyOf()
            requireContext().customAlertDialog().apply {
                setTitle(titleRes)
                multiChoiceItems(display, working) { state ->
                    working.indices.forEach { working[it] = state[it] }
                }
                setPosButton(requireContext().getString(R.string.apply)) {
                    val committed = mutableListOf<String>()
                    working.indices.forEach { idx ->
                        if (working[idx]) committed.add(visible[idx])
                    }
                    onCommitted(committed)
                    field.setText(committed.joinToString(", "))
                    field.setSelection(field.text.length)
                }
                setNegButton(requireContext().getString(R.string.cancel))
                show()
            }
        }
    }

    private fun label(value: String): String = value.replace("_", " ")

    private fun buildSourceChips() {
        val apiGroup = binding.searchSourcesApiGroup
        val providerGroup = binding.searchSourcesProviderGroup
        apiGroup.removeAllViews()
        providerGroup.removeAllViews()
        val catalog = activity.searchSourceCatalog()
        val inflater = LayoutInflater.from(requireContext())
        apiGroup.addView(
            makeSourceChip(inflater, getString(R.string.all), SearchSource.ALL)
        )
        catalog.filter { it.category == SearchSourceCategory.API }.forEach { source ->
            apiGroup.addView(makeSourceChip(inflater, source.label, source.key))
        }
        val providers = catalog.filter { it.category != SearchSourceCategory.API }
        binding.searchSourcesDivider.visibility = if (providers.isEmpty()) GONE else View.VISIBLE
        providerGroup.visibility = if (providers.isEmpty()) GONE else View.VISIBLE
        providers.forEach { source ->
            providerGroup.addView(makeSourceChip(inflater, source.label, source.key))
        }
        refreshSourceChipState()
    }

    private fun makeSourceChip(inflater: LayoutInflater, label: String, key: String): Chip {
        val chip = inflater.inflate(
            R.layout.item_search_filter_chip, binding.searchSourcesApiGroup, false
        ) as Chip
        chip.text = label
        chip.tag = key
        chip.isCheckable = true
        chip.isChecked = false
        chip.setOnClickListener {
            if (key == SearchSource.ALL) {
                val allSelected =
                    activity.selectedSourceKeys().size == activity.searchSourceCatalog().size
                if (allSelected) activity.resetSources() else activity.selectAllSources()
            } else {
                activity.toggleSource(key)
            }
            refreshSourceChipState()
        }
        return chip
    }

    private fun refreshSourceChipState() {
        val selected = activity.selectedSourceKeys()
        val catalog = activity.searchSourceCatalog()
        val allSelected = catalog.isNotEmpty() && selected.size == catalog.size
        val groups = listOf(binding.searchSourcesApiGroup, binding.searchSourcesProviderGroup)
        for (group in groups) {
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as? Chip ?: continue
                val key = chip.tag as? String ?: continue
                chip.isChecked = if (key == SearchSource.ALL) allSelected else key in selected
            }
        }
    }

    private fun resetSearchFilter() {
        activity.aniMangaResult.sort = null
        selectedSort = null
        activity.aniMangaResult.countryOfOrigin = null
        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_search_googlefonts)
        startBounceZoomAnimation(binding.countryFilter)

        selectedGenres.clear()
        exGenres.clear()
        selectedTags.clear()
        exTags.clear()
        adultOnly = false
        suppressSwitchCallback = true
        binding.searchAdultOnlyFlag.isChecked = false
        suppressSwitchCallback = false
        binding.searchGenresField.setText("")
        binding.searchTagsField.setText("")
        binding.searchSort.setText("")
        binding.searchStatus.setText("")
        binding.searchSource.setText("")
        binding.searchFormat.setText("")
        binding.searchSeason.setText("")
        binding.searchYear.setText("")
        binding.searchStatus.clearFocus()
        binding.searchSort.clearFocus()
        binding.searchFormat.clearFocus()
        binding.searchSeason.clearFocus()
        binding.searchYear.clearFocus()
        binding.searchGenresField.clearFocus()
        binding.searchTagsField.clearFocus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        activity = requireActivity() as SearchActivity

        val rescueMode: Boolean = ani.sanin.settings.saving.PrefManager.getVal(
            ani.sanin.settings.saving.PrefName.RescueMode
        )
        if (rescueMode) {
            binding.countryFilter.visibility = View.GONE
            (binding.searchSource.parent as? View)?.visibility = View.GONE
            binding.searchTagsCont.visibility = GONE
            binding.searchSeasonCont.visibility = View.GONE
        }

        selectedGenres = activity.aniMangaResult.genres ?: mutableListOf()
        exGenres = activity.aniMangaResult.excludedGenres ?: mutableListOf()
        selectedTags = activity.aniMangaResult.tags ?: mutableListOf()
        exTags = activity.aniMangaResult.excludedTags ?: mutableListOf()
        adultOnly = activity.aniMangaResult.isAdult
        selectedSort = activity.aniMangaResult.sort
        buildSourceChips()

        binding.searchAdultOnlyFlag.apply {
            isChecked = adultOnly
            setOnCheckedChangeListener { _, checked ->
                adultOnly = checked
                if (suppressSwitchCallback) return@setOnCheckedChangeListener
                activity.aniMangaResult.isAdult = checked
                activity.invalidateSuggestionPool()
                activity.updateChips.invoke()
                activity.search()
                dismiss()
            }
        }

        binding.searchGenresField.setText(selectedGenres.joinToString(", "))
        binding.searchTagsField.setText(selectedTags.joinToString(", "))

        binding.resetSearchFilter.setOnClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FilterResetAnimations)) {
                val rotateAnimation =
                    ObjectAnimator.ofFloat(binding.resetSearchFilter, "rotation", 180f, 540f)
                rotateAnimation.duration = 500
                rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
                rotateAnimation.start()
            }
            resetSearchFilter()
        }

        binding.resetSearchFilter.setOnLongClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FilterResetAnimations)) {
                val rotateAnimation =
                    ObjectAnimator.ofFloat(binding.resetSearchFilter, "rotation", 180f, 540f)
                rotateAnimation.duration = 500
                rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
                rotateAnimation.start()
                val bounceAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)

                binding.resetSearchFilter.startAnimation(bounceAnimation)
            }
            binding.resetSearchFilter.postDelayed({
                resetSearchFilter()

                CoroutineScope(Dispatchers.Main).launch {
                    activity.aniMangaResult.apply {
                        status =
                            binding.searchStatus.text.toString().replace(" ", "_").ifBlank { null }
                        source =
                            binding.searchSource.text.toString().replace(" ", "_").ifBlank { null }
                        format = binding.searchFormat.text.toString().ifBlank { null }
                        season = binding.searchSeason.text.toString().ifBlank { null }
                        startYear = binding.searchYear.text.toString().toIntOrNull()
                        seasonYear = binding.searchYear.text.toString().toIntOrNull()
                        sort = selectedSort
                        genres = selectedGenres
                        tags = selectedTags
                        excludedGenres = exGenres
                        excludedTags = exTags
                    }
                    activity.updateChips.invoke()
                    activity.search()
                    dismiss()
                }
            }, 500)
            true
        }

        binding.countryFilter.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.country_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.country_global -> {
                        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_search_googlefonts)
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_china -> {
                        activity.aniMangaResult.countryOfOrigin = "CN"
                        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_china_googlefonts)
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_south_korea -> {
                        activity.aniMangaResult.countryOfOrigin = "KR"
                        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_south_korea_googlefonts)
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_japan -> {
                        activity.aniMangaResult.countryOfOrigin = "JP"
                        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_japan_googlefonts)
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_taiwan -> {
                        activity.aniMangaResult.countryOfOrigin = "TW"
                        binding.countryFilter.setImageResource(R.drawable.ic_round_globe_taiwan_googlefonts)
                        startBounceZoomAnimation(binding.countryFilter)
                    }
                }
                true
            }
            popupMenu.show()
        }

        binding.searchFilterApply.setOnClickListener {
            val isRescueModeApply: Boolean = ani.sanin.settings.saving.PrefManager.getVal(
                ani.sanin.settings.saving.PrefName.RescueMode
            )
            activity.aniMangaResult.apply {
                status = binding.searchStatus.text.toString().replace(" ", "_").ifBlank { null }
                format = binding.searchFormat.text.toString().ifBlank { null }
                if (activity.aniMangaResult.type == "ANIME") {
                    seasonYear = binding.searchYear.text.toString().toIntOrNull()
                } else {
                    startYear = binding.searchYear.text.toString().toIntOrNull()
                }
                sort = selectedSort
                isAdult = adultOnly
                genres = selectedGenres
                excludedGenres = exGenres
                if (!isRescueModeApply) {
                    source = binding.searchSource.text.toString().replace(" ", "_").ifBlank { null }
                    season = binding.searchSeason.text.toString().ifBlank { null }
                    countryOfOrigin = activity.aniMangaResult.countryOfOrigin
                    tags = selectedTags
                    excludedTags = exTags
                } else {
                    source = null
                    season = null
                    countryOfOrigin = null
                    tags = mutableListOf()
                    excludedTags = mutableListOf()
                }
            }
            activity.invalidateSuggestionPool()
            activity.updateChips.invoke()
            activity.search()
            dismiss()
        }
        binding.searchFilterCancel.setOnClickListener {
            dismiss()
        }
        val format =
            if (activity.aniMangaResult.type == "ANIME") Anilist.animeStatus else Anilist.mangaStatus
        binding.searchStatus.setText(activity.aniMangaResult.status?.replace("_", " "))
        setupDnsDropdown(
            binding.searchStatus,
            R.string.status_title,
            format.toTypedArray(),
            activity.aniMangaResult.status?.replace("_", " ")
        )

        binding.searchSource.setText(activity.aniMangaResult.source?.replace("_", " "))
        setupDnsDropdown(
            binding.searchSource,
            R.string.source,
            Anilist.source.toTypedArray(),
            activity.aniMangaResult.source?.replace("_", " ")
        )

        binding.searchFormat.setText(activity.aniMangaResult.format)
        setupDnsDropdown(
            binding.searchFormat,
            R.string.format,
            (if (activity.aniMangaResult.type == "ANIME") Anilist.animeFormats else Anilist.mangaFormats).toTypedArray(),
            activity.aniMangaResult.format
        )

        val sortLabels = arrayOf("Default") + resources.getStringArray(R.array.sort_by)
        binding.searchSort.setText(
            selectedSort?.let { sort -> sortLabels.getOrNull(Anilist.sortBy.indexOf(sort) + 1) }
                .orEmpty()
        )
        binding.searchSort.setAdapter(null)
        binding.searchSort.setOnClickListener {
            val current = selectedSort?.let { Anilist.sortBy.indexOf(it) + 1 } ?: 0
            requireContext().customAlertDialog().apply {
                setTitle(R.string.sort)
                singleChoiceItems(sortLabels, current) { index ->
                    selectedSort = if (index <= 0) null else Anilist.sortBy.getOrNull(index - 1)
                    binding.searchSort.setText(if (index <= 0) "" else sortLabels[index])
                }
                show()
            }
        }

        val years = (1970 until Calendar.getInstance().get(Calendar.YEAR) + 2).map { it.toString() }
            .reversed().toTypedArray()
        binding.searchYear.setText(
            if (activity.aniMangaResult.type == "ANIME") activity.aniMangaResult.seasonYear?.toString()
            else activity.aniMangaResult.startYear?.toString()
        )
        setupDnsDropdown(
            binding.searchYear,
            R.string.year,
            years,
            if (activity.aniMangaResult.type == "ANIME") activity.aniMangaResult.seasonYear?.toString()
            else activity.aniMangaResult.startYear?.toString()
        )

        if (activity.aniMangaResult.type == "MANGA") binding.searchSeasonCont.visibility = GONE
        else {
            binding.searchSeason.setText(activity.aniMangaResult.season)
            setupDnsDropdown(
                binding.searchSeason,
                R.string.season,
                Anilist.seasons.toTypedArray(),
                activity.aniMangaResult.season
            )
        }

        setupMultiSelectDropdown(
            binding.searchGenresField,
            R.string.genres,
            Anilist.genres ?: listOf(),
            selectedGenres
        ) { committed ->
            selectedGenres.clear()
            selectedGenres.addAll(committed)
            exGenres.removeAll { g -> committed.any { it.equals(g, true) } }
        }

        setupMultiSelectDropdown(
            binding.searchTagsField,
            R.string.tags,
            Anilist.tags?.get(activity.aniMangaResult.isAdult)
                ?: Anilist.tags?.get(false)
                ?: listOf(),
            selectedTags
        ) { committed ->
            selectedTags.clear()
            selectedTags.addAll(committed)
            exTags.removeAll { t -> committed.any { it.equals(t, true) } }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = SearchFilterBottomDialog()
    }

}
