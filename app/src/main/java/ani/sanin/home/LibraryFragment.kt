package ani.sanin.home

import android.graphics.Color
import android.os.Bundle
import ani.sanin.statusBarHeight
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.FragmentLibraryBinding
import ani.sanin.getThemeColor
import ani.sanin.isDarkTheme
import ani.sanin.loadImage
import ani.sanin.media.user.ListFragment
import ani.sanin.media.user.ListViewPagerAdapter
import ani.sanin.media.user.ListViewModel
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.ui.LensButtonBackground
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var selectedTabIdx = 0
    private var viewPagerAttached = false
    private var lastTopFocusId = R.id.profileButton
    private var pendingInitialFocus = true

    private var tabsState by mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var selectedPillState by mutableIntStateOf(0)

    private val model: ListViewModel by activityViewModels()

    private fun renderStatusPill() {
        if (_binding == null) return
        binding.listTabPill.bind(
            tabs = tabsState,
            selectedIndex = selectedPillState,
            onTabSelected = { idx ->
                selectedPillState = idx
                selectedTabIdx = idx
                if (binding.listViewPager.currentItem != idx) {
                    binding.listViewPager.setCurrentItem(idx, false)
                }
            },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingInitialFocus = true

        val primaryColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)

        if (PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            binding.settingsContainer.setPadding(0, 0, 0, 0)
            binding.settingsContainer.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            binding.root.fitsSystemWindows = false
        } else {
            binding.root.fitsSystemWindows = true
        }

        binding.listAppBar.setBackgroundColor(primaryColor)

        renderStatusPill()

        binding.listViewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedPillState = position
                selectedTabIdx = position
                binding.listTabPill.setSelected(position)
            }
        })

        val defaultKeys = listOf(
            "Reading", "Watching", "Completed", "Paused", "Dropped", "Planning",
            "Favourites", "Rewatching", "Rereading", "All"
        )
        val userKeys: Array<String> = resources.getStringArray(R.array.keys)

        model.getLists().observe(viewLifecycleOwner) { it ->
            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                if (!viewPagerAttached) {
                    binding.listViewPager.adapter =
                        ListViewPagerAdapter(it.size, false, requireActivity()) {
                            _binding?.listTabPill?.requestFocus() == true
                        }
                    val keys = it.keys.toList()
                        .map { key -> userKeys.getOrNull(defaultKeys.indexOf(key)) ?: key }
                    val values = it.values.toList()
                    tabsState = keys.mapIndexed { position, key ->
                        LibraryStatusTab(label = key, count = values[position].size)
                    }
                    viewPagerAttached = true
                    binding.listViewPager.setCurrentItem(selectedTabIdx, false)
                    selectedPillState = selectedTabIdx
                    renderStatusPill()
                    if (pendingInitialFocus && tabsState.isNotEmpty()) {
                        pendingInitialFocus = false
                        binding.listTabPill.post { _binding?.listTabPill?.requestFocus() }
                    }
                }
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(viewLifecycleOwner) {
            if (it) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(true, Anilist.userid ?: 0)
                    }
                    live.postValue(false)
                }
            }
        }

        styleProfilePill()

        FocusEffectUtil.applyFocusListener(binding.profileButton)
        binding.profileButton.setOnClickListener {
            openProfile()
        }

        FocusEffectUtil.applyFocusListener(binding.listSettings)
        LensButtonBackground.apply(binding.listSettings)
        binding.listSettings.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.listSettings)
            val genres = PrefManager.getVal<Set<String>>(PrefName.GenresList).toMutableSet().sorted()
            LibrarySettingsBottomSheet.newInstance(
                currentSort = PrefManager.getVal<String>(PrefName.AnimeListSortOrder),
                filterItems = genres.ifEmpty { listOf("All") },
                onSortChanged = { sort ->
                    reloadWithSort(sort)
                },
                onGenreFilterChanged = { genre ->
                    binding.listProgressBar.visibility = View.VISIBLE
                    if (genre.isBlank()) {
                        model.unfilterLists()
                        model.filterLists("All")
                    } else {
                        model.filterLists(genre)
                    }
                    binding.listProgressBar.visibility = View.GONE
                },
                onNsfwChanged = { enabled ->
                    binding.listProgressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            model.loadLists(true, Anilist.userid ?: 0)
                        }
                        binding.listProgressBar.visibility = View.GONE
                    }
                }
            ).show(childFragmentManager, LibrarySettingsBottomSheet.TAG)
        }

        FocusEffectUtil.applyFocusListener(binding.listAvatar)
        val avatarUrl = Anilist.avatar ?: Simkl.avatar
        if (!avatarUrl.isNullOrBlank()) {
            binding.listAvatar.loadImage(avatarUrl)
        }
        binding.listAvatar.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.listAvatar)
            (activity as? ani.sanin.MainActivity)?.toggleRightRail()
        }

        setupFocusChain()
    }

    private fun setupFocusChain() {
        binding.listSettings.nextFocusRightId = R.id.profileButton
        binding.listSettings.nextFocusDownId = R.id.listTabPill
        binding.profileButton.nextFocusLeftId = R.id.listSettings
        binding.profileButton.nextFocusRightId = R.id.listAvatar
        binding.profileButton.nextFocusDownId = R.id.listTabPill
        binding.listAvatar.nextFocusLeftId = R.id.profileButton
        binding.listAvatar.nextFocusDownId = R.id.listTabPill

        binding.listTabPill.setExitFocusUp(lastTopFocusId)
        binding.listTabPill.setExitFocusDown(R.id.listViewPager)

        for (id in intArrayOf(R.id.listSettings, R.id.profileButton, R.id.listAvatar)) {
            val control = binding.root.findViewById<View>(id) ?: continue
            val borderListener = control.onFocusChangeListener
            control.setOnFocusChangeListener { view, hasFocus ->
                borderListener?.onFocusChange(view, hasFocus)
                if (hasFocus) {
                    lastTopFocusId = id
                    binding.listTabPill.setExitFocusUp(id)
                }
            }
        }
    }

    fun focusFirstCard(): Boolean = focusFirstCardInPager()

    private fun focusFirstCardInPager(): Boolean {
        val page = parentFragmentManager
            .findFragmentByTag("f${binding.listViewPager.currentItem}") as? ListFragment
        return page?.focusFirstCard() ?: false
    }

    private fun styleProfilePill() {
        val dark = isDarkTheme()
        val fill = if (dark) Color.WHITE else Color.BLACK
        binding.profileButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(fill)
        }
        binding.profileButton.setTextColor(if (dark) Color.BLACK else Color.WHITE)
    }

    private fun openProfile() {
        val uid = Anilist.userid
        if (uid != null) {
            startActivity(
                android.content.Intent(requireContext(), ani.sanin.profile.ProfileActivity::class.java)
                    .putExtra("userId", uid)
            )
        } else {

            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(android.view.Gravity.END)) {
                    val popMethod = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                    popMethod.isAccessible = true
                    popMethod.invoke(act)
                    drawer.openDrawer(android.view.Gravity.END)
                }
            }
        }
    }

    private fun reloadWithSort(sort: String) {
        binding.listProgressBar.visibility = View.VISIBLE
        binding.listViewPager.adapter = null
        viewPagerAttached = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                model.loadLists(true, Anilist.userid ?: 0, sort)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        viewPagerAttached = false
    }
}
