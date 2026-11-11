package ani.sanin.cloudstream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.databinding.BottomSheetTmdbFilterBinding
import ani.sanin.media.SheetSourceSelector
import ani.sanin.settings.StremioAddonClient
import ani.sanin.settings.StremioAddonManager
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

data class TmdbFilterResult(
    val mediaType: String,
    val genres: List<Int>,
    val keywords: List<Int>,
    val year: Int?,
    val sort: String?,
    val status: Int? = null,
    val tvType: Int? = null,
    val releaseType: Int? = null,
    val country: String? = null,
    val language: String? = null,
    val minRating: Double? = null,
    val includeAdult: Boolean = false,
    val genreNames: List<String> = emptyList(),
    val keywordNames: List<String> = emptyList()
)

private const val TMDB_SOURCE = "tmdb"

internal data class TmdbFilterOption(val value: Int, val label: String)

private data class LabeledCode(val label: String, val code: String)

internal val TMDB_TV_TYPE_OPTIONS = listOf(
    TmdbFilterOption(0, "Documentary"),
    TmdbFilterOption(1, "News"),
    TmdbFilterOption(2, "Miniseries"),
    TmdbFilterOption(3, "Reality"),
    TmdbFilterOption(4, "Scripted"),
    TmdbFilterOption(5, "Talk Show"),
    TmdbFilterOption(6, "Video")
)

internal val TMDB_MOVIE_RELEASE_OPTIONS = listOf(
    TmdbFilterOption(1, "Premiere"),
    TmdbFilterOption(2, "Limited"),
    TmdbFilterOption(3, "Theatrical"),
    TmdbFilterOption(4, "Digital"),
    TmdbFilterOption(5, "Physical"),
    TmdbFilterOption(6, "TV")
)

internal val TMDB_TV_STATUS_OPTIONS = listOf(
    TmdbFilterOption(0, "Returning"),
    TmdbFilterOption(1, "Planned"),
    TmdbFilterOption(2, "In Production"),
    TmdbFilterOption(3, "Ended"),
    TmdbFilterOption(4, "Canceled"),
    TmdbFilterOption(5, "Pilot")
)

private val TMDB_SORT_OPTIONS = listOf("Popularity", "Release Date", "Rating", "A-Z")

private val TMDB_RATING_OPTIONS = listOf(
    "Any rating" to null,
    "5.0+" to 5.0,
    "6.0+" to 6.0,
    "6.5+" to 6.5,
    "7.0+" to 7.0,
    "7.5+" to 7.5,
    "8.0+" to 8.0,
    "8.5+" to 8.5,
    "9.0+" to 9.0
)

class TmdbSearchFilterDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTmdbFilterBinding? = null
    private val binding get() = _binding!!
    var onApply: ((TmdbFilterResult) -> Unit)? = null
    var onSourcesChanged: ((Set<String>) -> Unit)? = null

    private val selectedGenres = mutableListOf<Int>()
    private val selectedKeywords = mutableListOf<Int>()
    private val selectedKeywordLabels = mutableMapOf<Int, String>()
    private val selectedSources = linkedSetOf<String>()
    private var selectedMediaType = "movie"
    private var selectedStatus: Int? = null
    private var selectedFormat: Int? = null
    private var selectedYear: Int? = null
    private var selectedCountry: LabeledCode? = null
    private var selectedLanguage: LabeledCode? = null
    private var selectedMinRating: Double? = null
    private var genreOptions: List<TmdbGenre> = emptyList()
    private var initialSources: Set<String> = setOf(TMDB_SOURCE)
    private var sortIndex = 0
    private val countryOptions: List<LabeledCode> by lazy { buildCountryOptions() }
    private val languageOptions: List<LabeledCode> by lazy { buildLanguageOptions() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTmdbFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedSources.clear()
        selectedSources.addAll(initialSources.ifEmpty { setOf(TMDB_SOURCE) })

        setupSortField()
        setupYearField()
        setupCountryIcon()
        setupLanguageField()
        setupRatingField()

        binding.tmdbFilterReset.setOnClickListener { resetFilter() }

        binding.tmdbFilterKeywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeyword()
                true
            } else false
        }
        binding.tmdbFilterKeywordAdd.setOnClickListener { addKeyword() }

        binding.tmdbFilterCancel.setOnClickListener { dismiss() }
        binding.tmdbFilterApply.setOnClickListener { applyFilter() }

        buildSourceChips()
        setupMediaTypeField()
        setupStatusField()
        rebuildFormatField()
        updateStatusVisibility()
        setupGenresField()

        viewLifecycleOwner.lifecycleScope.launch {
            genreOptions = runCatching { Tmdb.genres() }.getOrNull().orEmpty()
        }
    }

    private fun currentMediaType(): String = selectedMediaType

    private fun setupMediaTypeField() {
        val labels = arrayOf("Movies", "TV")
        binding.tmdbFilterMediaType.setText(labels[if (selectedMediaType == "tv") 1 else 0])
        setupChoiceField(
            binding.tmdbFilterMediaType,
            "Format",
            { labels },
            { if (selectedMediaType == "tv") 1 else 0 },
            { index ->
                selectedMediaType = if (index == 1) "tv" else "movie"
                binding.tmdbFilterMediaType.setText(labels[index])
                rebuildFormatField()
                updateStatusVisibility()
            }
        )
    }

    private fun setupChoiceField(
        field: AutoCompleteTextView,
        title: String,
        labels: () -> Array<String>,
        currentIndex: () -> Int,
        onPick: (Int) -> Unit
    ) {
        field.setAdapter(null)
        field.isClickable = true
        field.setOnClickListener {
            val items = labels()
            if (items.isEmpty()) return@setOnClickListener
            requireContext().customAlertDialog().apply {
                setTitle(title)
                singleChoiceItems(items, currentIndex()) { index -> onPick(index) }
                show()
            }
        }
    }

    private fun setupSortField() {
        binding.tmdbFilterSort.setText(TMDB_SORT_OPTIONS[sortIndex])
        setupChoiceField(
            binding.tmdbFilterSort,
            "Sort",
            { TMDB_SORT_OPTIONS.toTypedArray() },
            { sortIndex },
            { index ->
                sortIndex = index
                binding.tmdbFilterSort.setText(TMDB_SORT_OPTIONS[index])
            }
        )
    }

    private fun yearLabels(): Array<String> {
        val years = (1970 until Calendar.getInstance().get(Calendar.YEAR) + 2)
            .map { it.toString() }
            .reversed()
        return (listOf("Any year") + years).toTypedArray()
    }

    private fun yearIndex(): Int {
        val year = selectedYear ?: return 0
        return yearLabels().indexOf(year.toString()).coerceAtLeast(0)
    }

    private fun setupYearField() {
        binding.tmdbFilterYear.setText(selectedYear?.toString().orEmpty())
        setupChoiceField(
            binding.tmdbFilterYear,
            "Year",
            { yearLabels() },
            { yearIndex() },
            { index ->
                selectedYear = if (index <= 0) null else yearLabels().getOrNull(index)?.toIntOrNull()
                binding.tmdbFilterYear.setText(selectedYear?.toString().orEmpty())
            }
        )
    }

    private fun setupCountryIcon() {
        binding.tmdbFilterCountryIcon.setOnClickListener {
            val labels = (listOf("Any country") + countryOptions.map { it.label }).toTypedArray()
            val current = selectedCountry?.let { c -> countryOptions.indexOfFirst { it.code == c.code } + 1 } ?: 0
            requireContext().customAlertDialog().apply {
                setTitle("Country")
                singleChoiceItems(labels, current) { index ->
                    selectedCountry = if (index <= 0) null else countryOptions.getOrNull(index - 1)
                }
                show()
            }
        }
    }

    private fun setupLanguageField() {
        binding.tmdbFilterLanguage.setText(selectedLanguage?.label.orEmpty())
        setupChoiceField(
            binding.tmdbFilterLanguage,
            "Language",
            { (listOf("Any language") + languageOptions.map { it.label }).toTypedArray() },
            { selectedLanguage?.let { c -> languageOptions.indexOfFirst { it.code == c.code } + 1 } ?: 0 },
            { index ->
                selectedLanguage = if (index <= 0) null else languageOptions.getOrNull(index - 1)
                binding.tmdbFilterLanguage.setText(selectedLanguage?.label.orEmpty())
            }
        )
    }

    private fun setupRatingField() {
        binding.tmdbFilterMinRating.setText(
            TMDB_RATING_OPTIONS.firstOrNull { it.second != null && it.second == selectedMinRating }?.first.orEmpty()
        )
        setupChoiceField(
            binding.tmdbFilterMinRating,
            "Minimum rating",
            { TMDB_RATING_OPTIONS.map { it.first }.toTypedArray() },
            { TMDB_RATING_OPTIONS.indexOfFirst { it.second == selectedMinRating }.coerceAtLeast(0) },
            { index ->
                selectedMinRating = TMDB_RATING_OPTIONS.getOrNull(index)?.second
                binding.tmdbFilterMinRating.setText(
                    if (selectedMinRating == null) "" else TMDB_RATING_OPTIONS[index].first
                )
            }
        )
    }

    private fun formatOptions(): List<TmdbFilterOption> =
        if (currentMediaType() == "tv") TMDB_TV_TYPE_OPTIONS else TMDB_MOVIE_RELEASE_OPTIONS

    private fun rebuildFormatField() {
        selectedFormat = null
        val title = if (currentMediaType() == "tv") "TV Type" else "Release Type"
        binding.tmdbFilterFormat.setText("")
        binding.tmdbFilterFormatCont.setHint(title)
        setupChoiceField(
            binding.tmdbFilterFormat,
            title,
            { formatOptions().map { it.label }.toTypedArray() },
            { selectedFormat?.let { v -> formatOptions().indexOfFirst { it.value == v } } ?: -1 },
            { index ->
                val option = formatOptions().getOrNull(index)
                selectedFormat = option?.value
                binding.tmdbFilterFormat.setText(option?.label.orEmpty())
            }
        )
    }

    private fun setupStatusField() {
        binding.tmdbFilterStatus.setText("")
        setupChoiceField(
            binding.tmdbFilterStatus,
            "Status",
            { TMDB_TV_STATUS_OPTIONS.map { it.label }.toTypedArray() },
            { selectedStatus?.let { v -> TMDB_TV_STATUS_OPTIONS.indexOfFirst { it.value == v } } ?: -1 },
            { index ->
                val option = TMDB_TV_STATUS_OPTIONS.getOrNull(index)
                selectedStatus = option?.value
                binding.tmdbFilterStatus.setText(option?.label.orEmpty())
            }
        )
    }

    private fun updateStatusVisibility() {
        val isTv = currentMediaType() == "tv"
        binding.tmdbFilterStatusCont.isVisible = isTv
        if (!isTv) {
            binding.tmdbFilterStatus.setText("")
            selectedStatus = null
        }
    }

    private fun setupGenresField() {
        binding.tmdbFilterGenresField.setAdapter(null)
        binding.tmdbFilterGenresField.isClickable = true
        binding.tmdbFilterGenresField.setOnClickListener { showGenrePicker() }
    }

    private fun showGenrePicker() {
        if (genreOptions.isEmpty()) return
        val labels = genreOptions.map { it.name }.toTypedArray()
        val checked = BooleanArray(genreOptions.size) { idx -> selectedGenres.contains(genreOptions[idx].id) }
        val working = checked.copyOf()
        requireContext().customAlertDialog().apply {
            setTitle("Genres")
            multiChoiceItems(labels, working) { state ->
                working.indices.forEach { working[it] = state[it] }
            }
            setPosButton("Apply") {
                selectedGenres.clear()
                working.indices.forEach { idx -> if (working[idx]) selectedGenres.add(genreOptions[idx].id) }
                binding.tmdbFilterGenresField.setText(
                    genreOptions.filter { selectedGenres.contains(it.id) }
                        .joinToString(", ") { it.name }
                )
            }
            show()
        }
    }

    private fun applyFilter() {
        val mediaType = currentMediaType()
        val selectedGenreItems = genreOptions.filter { selectedGenres.contains(it.id) }
        val selectedKeywordItems = selectedKeywords.map {
            it to (selectedKeywordLabels[it] ?: it.toString())
        }
        val sort = when (TMDB_SORT_OPTIONS[sortIndex]) {
            "Popularity" -> "popularity.desc"
            "Release Date" -> if (mediaType == "tv") "first_air_date.desc" else "primary_release_date.desc"
            "Rating" -> "vote_average.desc"
            else -> if (mediaType == "tv") "name.asc" else "original_title.asc"
        }
        onApply?.invoke(
            TmdbFilterResult(
                mediaType = mediaType,
                genres = selectedGenreItems.map { it.id },
                keywords = selectedKeywordItems.map { it.first },
                year = selectedYear,
                sort = sort,
                status = if (mediaType == "tv") selectedStatus else null,
                tvType = if (mediaType == "tv") selectedFormat else null,
                releaseType = if (mediaType == "tv") null else selectedFormat,
                country = selectedCountry?.code,
                language = selectedLanguage?.code,
                minRating = selectedMinRating,
                includeAdult = binding.tmdbFilterAdult.isChecked,
                genreNames = selectedGenreItems.map { it.name },
                keywordNames = selectedKeywordItems.map { it.second }
            )
        )
        dismiss()
    }

    private fun buildSourceChips() {
        val group = binding.tmdbFilterSources
        group.removeAllViews()
        group.addView(makeSourceChip("TMDB", TMDB_SOURCE))
        CsRepos.installed(requireContext()).forEach { source ->
            group.addView(makeSourceChip(source.name, source.id))
        }
        runCatching { StremioAddonManager.installed() }.getOrDefault(emptyList()).forEach { addon ->
            if (StremioAddonClient.searchCatalogs(addon).isNotEmpty()) {
                group.addView(makeSourceChip(addon.manifest.name, StremioAddonManager.sourceId(addon)))
            }
        }
    }

    private fun makeSourceChip(label: String, key: String): Chip {
        val chip = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_search_filter_chip, binding.tmdbFilterSources, false) as Chip
        chip.apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isFocusable = true
            tag = key
            isChecked = key in selectedSources
        }
        FocusEffectUtil.applyFocusListener(chip)
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedSources.add(key) else selectedSources.remove(key)
            if (selectedSources.isEmpty()) {
                selectedSources.add(TMDB_SOURCE)
                if (key != TMDB_SOURCE) {
                    for (i in 0 until binding.tmdbFilterSources.childCount) {
                        val other = binding.tmdbFilterSources.getChildAt(i) as? Chip ?: continue
                        if (other.tag == TMDB_SOURCE) other.isChecked = true
                    }
                }
            }
            onSourcesChanged?.invoke(selectedSources.toSet())
        }
        return chip
    }

    private fun resetFilter() {
        selectedGenres.clear()
        selectedKeywords.clear()
        selectedKeywordLabels.clear()
        selectedStatus = null
        selectedFormat = null
        selectedYear = null
        selectedCountry = null
        selectedLanguage = null
        selectedMinRating = null
        sortIndex = 0
        binding.tmdbFilterSort.setText(TMDB_SORT_OPTIONS[0])
        binding.tmdbFilterYear.setText("")
        binding.tmdbFilterLanguage.setText("")
        binding.tmdbFilterMinRating.setText("")
        binding.tmdbFilterAdult.isChecked = false
        selectedMediaType = "movie"
        binding.tmdbFilterMediaType.setText("Movies")
        binding.tmdbFilterGenresField.setText("")
        binding.tmdbFilterKeywordChips.removeAllViews()
        selectedSources.clear()
        selectedSources.add(TMDB_SOURCE)
        buildSourceChips()
        rebuildFormatField()
        updateStatusVisibility()
        onSourcesChanged?.invoke(selectedSources.toSet())
        snackString("Filter reset")
    }

    private fun addKeyword() {
        val query = binding.tmdbFilterKeywordInput.text.toString().trim()
        if (query.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val results = Tmdb.searchKeywords(query)
            if (results.isEmpty()) {
                snackString("No keyword found for '$query'")
                return@launch
            }
            if (results.size == 1) {
                addKeywordChip(results[0].id, results[0].name)
                binding.tmdbFilterKeywordInput.setText("")
                return@launch
            }
            val sheet = SheetSourceSelector.newInstance(
                ArrayList(results.map { it.name }),
                onSelect = { idx ->
                    val kw = results[idx]
                    addKeywordChip(kw.id, kw.name)
                    binding.tmdbFilterKeywordInput.setText("")
                }
            )
            sheet.show(childFragmentManager, "keywordPicker")
        }
    }

    private fun addKeywordChip(id: Int, name: String) {
        if (selectedKeywords.contains(id)) return
        selectedKeywords.add(id)
        selectedKeywordLabels[id] = name
        val chip = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_search_filter_chip, binding.tmdbFilterKeywordChips, false) as Chip
        chip.apply {
            text = name
            isCloseIconVisible = true
            isFocusable = true
            setOnCloseIconClickListener {
                selectedKeywords.remove(id)
                selectedKeywordLabels.remove(id)
                binding.tmdbFilterKeywordChips.removeView(this)
            }
        }
        FocusEffectUtil.applyFocusListener(chip)
        binding.tmdbFilterKeywordChips.addView(chip)
    }

    private fun buildCountryOptions(): List<LabeledCode> {
        val locale = Locale.getDefault()
        return Locale.getISOCountries()
            .mapNotNull { code ->
                val name = Locale.Builder().setRegion(code).build()
                    .getDisplayCountry(locale)
                    .trim()
                if (name.isBlank() || name.equals(code, true)) null else LabeledCode(name, code)
            }
            .distinctBy { it.code }
            .sortedBy { it.label }
    }

    private fun buildLanguageOptions(): List<LabeledCode> {
        val locale = Locale.getDefault()
        return Locale.getISOLanguages()
            .filter { it.length == 2 }
            .mapNotNull { code ->
                val name = Locale(code).getDisplayLanguage(locale).trim()
                if (name.isBlank() || name.equals(code, true)) null else LabeledCode(name, code)
            }
            .distinctBy { it.code }
            .sortedBy { it.label }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            onApply: ((TmdbFilterResult) -> Unit)?,
            sources: Set<String> = setOf(TMDB_SOURCE),
            onSourcesChanged: ((Set<String>) -> Unit)? = null
        ): TmdbSearchFilterDialog {
            return TmdbSearchFilterDialog().apply {
                this.onApply = onApply
                this.initialSources = sources
                this.onSourcesChanged = onSourcesChanged
            }
        }
    }
}
