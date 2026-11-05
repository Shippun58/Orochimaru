package ani.sanin.media

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.App.Companion.context
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.AnilistSearch.SearchType
import ani.sanin.databinding.ItemSearchHeaderBinding
import ani.sanin.getThemeColor
import ani.sanin.openLinkInBrowser
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchAdapter(
    activity: SearchActivity,
    private val type: SearchType,
    binding: ItemSearchHeaderBinding
) : HeaderInterface(activity, binding) {

    private fun updateFilterTextViewDrawable() {
        val filterDrawable = when (activity.aniMangaResult.sort) {
            Anilist.sortBy[0] -> R.drawable.ic_round_area_chart_24
            Anilist.sortBy[1] -> R.drawable.ic_round_filter_peak_24
            Anilist.sortBy[2] -> R.drawable.ic_round_star_graph_24
            Anilist.sortBy[3] -> R.drawable.ic_round_new_releases_24
            Anilist.sortBy[4] -> R.drawable.ic_round_filter_list_24
            Anilist.sortBy[5] -> R.drawable.ic_round_filter_list_24_reverse
            Anilist.sortBy[6] -> R.drawable.ic_round_assist_walker_24
            else -> R.drawable.ic_round_filter_alt_24
        }
        binding.searchFilter.setImageResource(filterDrawable)
        binding.searchFilter.imageTintList =
            ColorStateList.valueOf(FocusEffectUtil.getPrimaryColor(activity))
    }

    private fun hasActiveFilters(): Boolean = activity.aniMangaResult.let {
        it.sort != null || it.status != null || it.source != null || it.format != null ||
            it.countryOfOrigin != null || it.season != null || it.seasonYear != null ||
            it.startYear != null || !it.genres.isNullOrEmpty() ||
            !it.excludedGenres.isNullOrEmpty() || !it.tags.isNullOrEmpty() ||
            !it.excludedTags.isNullOrEmpty() || it.isAdult
    }

    private val filterChipsAdapter = SearchFilterChipsAdapter { chip ->
        if (chip.key.startsWith("src:")) {
            val key = chip.key.removePrefix("src:")
            if (key == SearchSource.ALL) activity.resetSources()
            else activity.toggleSource(key)
            return@SearchFilterChipsAdapter
        }
        val r = activity.aniMangaResult
        when (chip.key) {
            "sort" -> r.sort = null
            "status" -> r.status = null
            "source" -> r.source = null
            "format" -> r.format = null
            "season" -> r.season = null
            "country" -> r.countryOfOrigin = null
            "seasonYear" -> r.seasonYear = null
            "startYear" -> r.startYear = null
            "adult" -> {
                r.isAdult = false
                activity.invalidateSuggestionPool()
            }
            else -> {
                if (chip.key.startsWith("genre:")) {
                    val value = chip.key.removePrefix("genre:")
                    r.genres?.remove(value)
                    if (r.genres?.isEmpty() == true) r.genres = null
                } else if (chip.key.startsWith("tag:")) {
                    val value = chip.key.removePrefix("tag:")
                    r.tags?.remove(value)
                    if (r.tags?.isEmpty() == true) r.tags = null
                } else if (chip.key.startsWith("exgenre:")) {
                    val value = chip.key.removePrefix("exgenre:")
                    r.excludedGenres?.remove(value)
                    if (r.excludedGenres?.isEmpty() == true) r.excludedGenres = null
                } else if (chip.key.startsWith("extag:")) {
                    val value = chip.key.removePrefix("extag:")
                    r.excludedTags?.remove(value)
                    if (r.excludedTags?.isEmpty() == true) r.excludedTags = null
                }
            }
        }
        activity.updateChips.invoke()
        activity.search()
    }

    private fun updateFilterState() {
        val tint = if (hasActiveFilters()) {
            FocusEffectUtil.getPrimaryColor(activity)
        } else {
            activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        }
        binding.searchFilter.imageTintList = ColorStateList.valueOf(tint)

        val result = activity.aniMangaResult
        val chips = ArrayList<SearchFilterChipsAdapter.ChipSpec>()
        chips.addAll(activity.sourceChipsForDisplay())
        chips.addAll(buildFilterChips(result))
        filterChipsAdapter.submit(chips)
        binding.searchFilterChips.visibility =
            if (chips.isEmpty()) View.GONE else View.VISIBLE
        binding.searchBarText.nextFocusDownId =
            if (chips.isEmpty()) R.id.searchRecyclerView else R.id.searchFilterChips
    }

    override fun seeAllResults(q: String) {
        activity.closeSuggestions()
        activity.aniMangaResult.search = q.ifBlank { null }
        if (binding.searchBarText.text.toString() != q) {
            activity.suppressSuggestionsForNextQuery()
            binding.searchBarText.setText(q)
            binding.searchBarText.setSelection(q.length)
        } else {
            activity.search()
        }
        binding.searchBarText.clearFocus()
    }

    private fun refreshEndIcon() {
        val hasText = !binding.searchBarText.text.isNullOrBlank()
        binding.searchBar.endIconMode =
            com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
        binding.searchBar.endIconDrawable = if (hasText) {
            androidx.core.content.ContextCompat.getDrawable(
                binding.root.context, R.drawable.ic_round_close_24
            )
        } else {
            androidx.core.content.ContextCompat.getDrawable(
                binding.root.context, R.drawable.ic_round_search_24
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun bind() {
        searchHistoryAdapter = SearchHistoryAdapter(
            type,
            upFocusId = R.id.searchBarText
        ) {
            binding.searchBarText.setText(it)
            binding.searchBarText.setSelection(it.length)
        }
        searchHistoryAdapter.onItemCountChanged = { updateClearHistoryVisibility() }
        binding.searchHistoryList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchHistoryList.adapter = searchHistoryAdapter

        binding.searchFilterChips.layoutManager =
            LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
        binding.searchFilterChips.adapter = filterChipsAdapter

        if (activity.searchType != SearchType.MANGA && activity.searchType != SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        when (activity.style) {
            0 -> {
                binding.searchResultGrid.alpha = 1f
                binding.searchResultList.alpha = 0.33f
            }

            1 -> {
                binding.searchResultList.alpha = 1f
                binding.searchResultGrid.alpha = 0.33f
            }
        }

        binding.searchBar.hint = activity.aniMangaResult.type
        if (PrefManager.getVal(PrefName.Incognito)) {
            val startIconDrawableRes = R.drawable.ic_incognito_24
            val startIconDrawable: Drawable? =
                context?.let { AppCompatResources.getDrawable(it, startIconDrawableRes) }
            binding.searchBar.startIconDrawable = startIconDrawable
        }

        binding.searchBarText.removeTextChangedListener(textWatcher)
        binding.searchBarText.setText(activity.aniMangaResult.search)
        binding.searchAdultCont.visibility = View.GONE
        binding.clearHistoryLabel.visibility = View.GONE

        LensButtonBackground.apply(binding.searchFilter)
        LensButtonBackground.apply(binding.clearHistory)
        LensButtonBackground.apply(binding.searchResultGrid)
        LensButtonBackground.apply(binding.searchResultList)

        FocusEffectUtil.applyFocusListener(
            binding.clearHistory,
            binding.searchFilter,
            binding.searchResultGrid,
            binding.searchResultList
        )

        binding.searchBarText.nextFocusDownId = R.id.searchFilterChips
        binding.searchFilter.nextFocusLeftId = R.id.searchBarText
        binding.searchFilter.nextFocusRightId = R.id.searchBarText
        binding.searchBarText.nextFocusLeftId = R.id.searchFilter
        binding.searchFilterChips.nextFocusDownId = R.id.searchHistoryTextView
        binding.searchHistoryList.nextFocusRightId = R.id.searchHistoryClear
        binding.searchHistoryClear.nextFocusLeftId = R.id.searchHistoryTextView

        binding.searchFilter.setOnClickListener {
            SearchFilterBottomDialog.newInstance().show(activity.supportFragmentManager, "dialog")
        }
        binding.searchFilter.setOnLongClickListener {
            val popupMenu = PopupMenu(activity, binding.searchFilter)
            popupMenu.menuInflater.inflate(R.menu.sortby_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.sort_by_score -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[0]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_popular -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[1]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_trending -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[2]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_recent -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[3]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_a_z -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[4]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_z_a -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[5]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_pure_pain -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[6]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }
                }
                true
            }
            popupMenu.show()
            true
        }
        updateFilterState()
        activity.updateChips = { updateFilterState() }

        setupClearHistoryControls()
        updateClearHistoryVisibility()

        fun searchTitle() {
            activity.aniMangaResult.apply {
                search =
                    if (binding.searchBarText.text.toString() != "") binding.searchBarText.text.toString() else null
            }
            if (binding.searchBarText.text.toString().equals("hentai", true)) {
                openLinkInBrowser("https://www.youtube.com/watch?v=GgJrEOo0QoA")
            }
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().isBlank()) {
                    activity.emptyMediaAdapter()
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(200)
                        activity.runOnUiThread {
                            setHistoryVisibility(true)
                        }
                    }
                } else {
                    setHistoryVisibility(false)
                    searchTitle()
                }
                activity.onSearchQueryChanged(s.toString())
                refreshEndIcon()
            }
        }
        binding.searchBarText.addTextChangedListener(textWatcher)

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    activity.closeSuggestions()
                    searchTitle()
                    binding.searchBarText.clearFocus()
                    true
                }

                else -> false
            }
        }
        binding.searchBar.setEndIconOnClickListener {
            activity.closeSuggestions()
            if (binding.searchBarText.text.isNullOrBlank()) {
                searchTitle()
            } else {
                binding.searchBarText.setText("")
            }
        }

        binding.searchResultGrid.setOnClickListener {
            it.alpha = 1f
            binding.searchResultList.alpha = 0.33f
            activity.style = 0
            PrefManager.setVal(PrefName.SearchStyle, 0)
            activity.recycler()
        }
        binding.searchResultList.setOnClickListener {
            it.alpha = 1f
            binding.searchResultGrid.alpha = 0.33f
            activity.style = 1
            PrefManager.setVal(PrefName.SearchStyle, 1)
            activity.recycler()
        }

        refreshEndIcon()

        binding.searchBarText.post {
            val mode = TvKeyboardUtil.keyboardMode()
            Logger.log(Log.INFO, "SearchAdapter: applying keyboard setup mode=$mode to searchBarText", "TvKeyboard")
            when (mode) {
                0 -> TvKeyboardUtil.setupSystemKeyboard(binding.searchBarText)
                1 -> TvKeyboardUtil.setupEditTextWithToggle(binding.searchBarText, binding.searchKeyboardToggle)
                2 -> TvKeyboardUtil.setupEditTextForAlwaysVisible(binding.searchBarText)
            }
        }
        search = Runnable { searchTitle() }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }

    override fun imageSearchVisible(): Boolean = activity.aniMangaResult.type == "ANIME"
}
