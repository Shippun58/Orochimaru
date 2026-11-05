package ani.sanin.home

import android.graphics.Color
import android.os.Bundle
import ani.sanin.isDarkTheme
import ani.sanin.statusBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.databinding.FragmentTmdbLibraryBinding
import ani.sanin.loadImage
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import ani.sanin.getThemeColor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbLibraryFragment : Fragment() {

    private var _binding: FragmentTmdbLibraryBinding? = null
    private val binding get() = _binding!!
    private var selectedTabIdx = 0
    private var viewPagerAttached = false
    private var pendingInitialFocus = true
    private var tabsState by mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var selectedPillState by mutableIntStateOf(0)
    private var allItems: List<Simkl.SimklWatchedItem> = emptyList()
    private var sectionFragments = mutableListOf<SimklSectionFragment>()
    private val libraryGenres = sortedSetOf<String>()
    private val itemGenres = HashMap<Pair<String, Int>, Set<String>>()
    private var genreEnrichmentRunning = false

    private fun renderStatusPill() {
        if (_binding == null) return
        binding.tmdbLibPill.bind(
            tabs = tabsState,
            selectedIndex = selectedPillState,
            onTabSelected = { idx ->
                selectedPillState = idx
                selectedTabIdx = idx
                if (binding.tmdbLibViewPager.currentItem != idx) {
                    binding.tmdbLibViewPager.setCurrentItem(idx, false)
                }
            },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingInitialFocus = true

        val primaryColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)

        if (PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            binding.tmdbLibSettingsContainer.setPadding(0, 0, 0, 0)
            binding.tmdbLibSettingsContainer.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            binding.root.fitsSystemWindows = false
        } else {
            binding.root.fitsSystemWindows = true
        }

        binding.tmdbLibAppBar.setBackgroundColor(primaryColor)

        binding.tmdbLibSettings.nextFocusDownId = R.id.tmdbLibPill
        binding.tmdbLibProfileButton.nextFocusDownId = R.id.tmdbLibPill
        binding.tmdbLibAvatar.nextFocusDownId = R.id.tmdbLibPill
        binding.tmdbLibPill.nextFocusUpId = R.id.tmdbLibProfileButton
        binding.tmdbLibPill.nextFocusDownId = R.id.tmdbLibViewPager
        binding.tmdbLibViewPager.nextFocusUpId = R.id.tmdbLibPill

        renderStatusPill()

        binding.tmdbLibViewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedPillState = position
                selectedTabIdx = position
                binding.tmdbLibPill.setSelected(position)
            }
        })

        if (Simkl.token == null) {
            showNotLoggedIn()
            return
        }

        binding.tmdbLibProgressBar.visibility = View.VISIBLE
        loadLibrary()

        val live = Refresh.activity.getOrPut(requireActivity().hashCode()) { androidx.lifecycle.MutableLiveData(true) }
        live.observe(viewLifecycleOwner) { if (it == true) { loadLibrary(); live.postValue(false) } }

        FocusEffectUtil.applyFocusListener(binding.tmdbLibSettings)
        binding.tmdbLibSettings.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.tmdbLibSettings)
            LibrarySettingsBottomSheet.newInstance(
                currentSort = "updated",
                filterItems = synchronized(itemGenres) { libraryGenres.toList() },
                showNsfw = false,
                onSortChanged = { sort ->
                    val mapped = when (sort) {
                        "updatedAt" -> "updated"; "release" -> "year"; else -> sort
                    }
                    sectionFragments.forEach { it.sort(mapped) }
                },
                onGenreFilterChanged = { selected ->
                    if (selected.isBlank() || selected == "All") {
                        showSections(allItems)
                    } else {
                        val filtered = synchronized(itemGenres) {
                            allItems.filter { item ->
                                val tmdbId = item.ids?.tmdb
                                tmdbId != null &&
                                    (itemGenres[(item.mediaType ?: "tv") to tmdbId] ?: emptySet()).contains(selected)
                            }
                        }
                        showFilteredSections(filtered, selected)
                    }
                },
                onNsfwChanged = null
            ).show(childFragmentManager, LibrarySettingsBottomSheet.TAG)
        }

        styleProfilePill()

        FocusEffectUtil.applyFocusListener(binding.tmdbLibProfileButton)
        binding.tmdbLibProfileButton.setOnClickListener { openSimklProfile() }

        FocusEffectUtil.applyFocusListener(binding.tmdbLibAvatar)
        val libAvatarUrl = Simkl.avatar
        if (!libAvatarUrl.isNullOrBlank()) {
            binding.tmdbLibAvatar.loadImage(libAvatarUrl)
        }
        binding.tmdbLibAvatar.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.tmdbLibAvatar)
            (activity as? ani.sanin.MainActivity)?.toggleRightRail()
        }
    }

    private fun styleProfilePill() {
        val dark = isDarkTheme()
        val fill = if (dark) Color.WHITE else Color.BLACK
        binding.tmdbLibProfileButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(fill)
        }
        binding.tmdbLibProfileButton.setTextColor(if (dark) Color.BLACK else Color.WHITE)
        binding.tmdbLibProfileButton.text =
            Simkl.username?.takeIf { it.isNotBlank() } ?: "My Profile"
    }

    private fun openSimklProfile() {
        startActivity(
            android.content.Intent(
                requireContext(),
                ani.sanin.profile.SimklProfileActivity::class.java
            )
        )
    }

    private fun loadLibrary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val movies = withContext(Dispatchers.IO) { Simkl.getMovieLibrary() }
            val shows = withContext(Dispatchers.IO) { Simkl.getShowLibrary() }
            binding.tmdbLibProgressBar.visibility = View.GONE
            allItems = movies + shows
            if (allItems.isEmpty()) {
                showEmpty()
                return@launch
            }
            showSections(allItems)
            enrichGenres()
        }
    }

    private fun enrichGenres() {
        if (genreEnrichmentRunning) return
        genreEnrichmentRunning = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            coroutineScope {
                val targets = allItems.filter { it.ids?.tmdb != null }
                    .distinctBy { (it.mediaType ?: "tv") to it.ids!!.tmdb!! }
                targets.chunked(8).forEach { batch ->
                    batch.map { item -> async { enrichOne(item) } }.forEach { it.await() }
                }
            }
            genreEnrichmentRunning = false
        }
    }

    private suspend fun enrichOne(item: Simkl.SimklWatchedItem) {
        val tmdbId = item.ids?.tmdb ?: return
        val type = item.mediaType ?: "tv"
        val key = type to tmdbId
        synchronized(itemGenres) { if (itemGenres.containsKey(key)) return }
        val genres = runCatching { Tmdb.detailGenres(type, tmdbId).map { it.name }.toSet() }
            .getOrDefault(emptySet())
        if (genres.isEmpty()) return
        synchronized(itemGenres) {
            itemGenres[key] = genres
            libraryGenres.addAll(genres)
        }
    }

    private fun showSections(items: List<Simkl.SimklWatchedItem>) {
        viewPagerAttached = false
        sectionFragments.clear()

        val sections = linkedMapOf<String, List<Simkl.SimklWatchedItem>>()

        val completedMovies = items.filter {
            it.status?.lowercase() == "completed" && it.mediaType == "movie"
        }
        val completedShows = items.filter {
            it.status?.lowercase() == "completed" && it.mediaType == "tv"
        }
        val watching = items.filter {
            it.status?.lowercase() == "watching" || it.status?.lowercase() == "current"
        }
        val planning = items.filter {
            it.status?.lowercase() == "plantowatch" || it.status?.lowercase() == "planning"
        }
        val paused = items.filter {
            it.status?.lowercase() == "hold" || it.status?.lowercase() == "onhold" || it.status?.lowercase() == "paused"
        }
        val dropped = items.filter { it.status?.lowercase() == "dropped" }

        val favourites = items.filter { (it.userRating ?: 0) >= 9 }

        if (completedMovies.isNotEmpty()) sections["Completed Movies"] = completedMovies
        if (completedShows.isNotEmpty()) sections["Completed TV"] = completedShows
        if (watching.isNotEmpty()) sections["Watching"] = watching
        if (planning.isNotEmpty()) sections["Planning"] = planning
        if (paused.isNotEmpty()) sections["Paused"] = paused
        if (dropped.isNotEmpty()) sections["Dropped"] = dropped
        if (favourites.isNotEmpty()) sections["Favourites"] = favourites
        sections["All"] = items

        if (sections.isEmpty()) {
            showEmpty()
            return
        }

        val fragments = sections.map { (_, sectionItems) ->
            SimklSectionFragment.newInstance(sectionItems).apply {
                onExitUp = { _binding?.tmdbLibPill?.requestFocus() == true }
            }
        }
        sectionFragments.addAll(fragments)

        val titles = sections.keys.toList()
        val values = sections.values.toList()
        tabsState = titles.mapIndexed { index, label ->
            LibraryStatusTab(label = label, count = values[index].size)
        }

        binding.tmdbLibViewPager.adapter = SimklPagerAdapter(sectionFragments, requireActivity())
        binding.tmdbLibPill.isVisible = true
        binding.tmdbLibViewPager.isVisible = true

        viewPagerAttached = true
        binding.tmdbLibViewPager.setCurrentItem(
            selectedTabIdx.coerceIn(0, titles.size - 1), false
        )
        selectedPillState = selectedTabIdx.coerceIn(0, titles.size - 1)
        renderStatusPill()
        requestInitialFocus()
    }

    private fun requestInitialFocus() {
        if (!pendingInitialFocus || tabsState.isEmpty()) return
        pendingInitialFocus = false
        binding.tmdbLibPill.post { _binding?.tmdbLibPill?.requestFocus() }
    }

    fun focusFirstCard(): Boolean = focusFirstCardInPager()

    private fun focusFirstCardInPager(): Boolean {
        val page = parentFragmentManager
            .findFragmentByTag("f${binding.tmdbLibViewPager.currentItem}") as? SimklSectionFragment
        return page?.focusFirstCard() ?: false
    }

    private fun showFilteredSections(items: List<Simkl.SimklWatchedItem>, title: String) {
        viewPagerAttached = false
        sectionFragments.clear()

        val fragment = SimklSectionFragment.newInstance(items).apply {
            onExitUp = { _binding?.tmdbLibPill?.requestFocus() == true }
        }
        sectionFragments.add(fragment)

        tabsState = listOf(LibraryStatusTab(label = title, count = items.size))

        binding.tmdbLibViewPager.adapter = SimklPagerAdapter(sectionFragments, requireActivity())
        binding.tmdbLibPill.isVisible = true
        binding.tmdbLibViewPager.isVisible = true

        viewPagerAttached = true
        binding.tmdbLibViewPager.setCurrentItem(0, false)
        selectedPillState = 0
        renderStatusPill()
        requestInitialFocus()
    }

    private fun showNotLoggedIn() {
        binding.tmdbLibProgressBar.visibility = View.GONE
        binding.tmdbLibPill.isVisible = false
        binding.tmdbLibViewPager.isVisible = false
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Log in to Simkl to see your library"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        (binding.root as? ViewGroup)?.addView(msg, lp)
    }

    private fun showEmpty() {
        binding.tmdbLibProgressBar.visibility = View.GONE
        binding.tmdbLibPill.isVisible = false
        binding.tmdbLibViewPager.isVisible = false
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Your Simkl library is empty"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        (binding.root as? ViewGroup)?.addView(msg, lp)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        viewPagerAttached = false
        sectionFragments.clear()
    }
}
