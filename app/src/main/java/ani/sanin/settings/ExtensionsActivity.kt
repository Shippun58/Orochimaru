package ani.sanin.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.getThemeColor
import ani.sanin.cloudstream.CloudStreamAvailableFragment
import ani.sanin.cloudstream.CloudStreamInstalledFragment
import ani.sanin.cloudstream.CsRepos
import ani.sanin.databinding.ActivityExtensionsBinding
import ani.sanin.initActivity
import ani.sanin.media.MediaType
import ani.sanin.navBarHeight
import ani.sanin.notifications.ExtensionBatchInstaller
import ani.sanin.others.AndroidBug5497Workaround
import ani.sanin.plugins.nuvio.NuvioAvailableFragment
import ani.sanin.plugins.nuvio.NuvioInstalledFragment
import ani.sanin.plugins.nuvio.NuvioRepository
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.LensButtonBackground
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import ani.sanin.util.TvKeyboardUtil

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    private enum class ExtensionMode(val label: String, val logoRes: Int) {
        ANIYOMI("Aniyomi", R.drawable.ic_toggle_aniyomi_mono),
        CLOUDSTREAM("CloudStream", R.drawable.ic_toggle_cloudstream_mono),
        STREMIO("Stremio", R.drawable.ic_stremio_watermark),
        NUVIO("Nuvio", R.drawable.ic_nuvio_logo_mono),
    }

    private var extensionMode = ExtensionMode.ANIYOMI
    private val cloudStreamMode get() = extensionMode == ExtensionMode.CLOUDSTREAM
    private val stremioMode get() = extensionMode == ExtensionMode.STREMIO
    private val nuvioMode get() = extensionMode == ExtensionMode.NUVIO

    private var pillTabs by mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var selectedPillIndex by mutableIntStateOf(0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK &&
            searchExpanded
        ) {
            collapseSearchBar()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val vp = binding.viewPager

            val focused = currentFocus
            var tabFocused = false
            var p: android.view.ViewParent? = focused?.parent
            while (p != null) {
                if (p === binding.extensionsPill) { tabFocused = true; break }
                p = p.parent as? android.view.ViewParent
            }
            if (vp.isFocused || tabFocused) {
                focusFirstBrowseButton(vp)
                return true
            }

            if (focused != null) {
                var v: android.view.View? = focused
                var rv: androidx.recyclerview.widget.RecyclerView? = null
                while (v != null) {
                    val parent = v.parent
                    if (parent is androidx.recyclerview.widget.RecyclerView && parent.id == R.id.allExtensionsRecyclerView) {
                        rv = parent; break
                    }
                    v = parent as? android.view.View
                }
                if (rv != null) {

                    var item: android.view.View? = focused
                    while (item != null && item.parent !== rv) {
                        item = item.parent as? android.view.View
                    }
                    val pos = item?.let { rv.getChildAdapterPosition(it) }
                        ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val last = (rv.adapter?.itemCount ?: 0) - 1
                        if (pos == last) {
                            if (rv.canScrollVertically(1)) rv.smoothScrollBy(0, 80)
                            return true
                        }
                        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val lastVisible = lm?.findLastVisibleItemPosition() ?: -1
                        if (pos >= lastVisible && rv.canScrollVertically(1)) {
                            rv.smoothScrollToPosition((pos + 1).coerceAtMost(last))
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        AndroidBug5497Workaround.assistActivity(this) { }

        extensionMode = when {
            intent.getBooleanExtra(EXTRA_NUVIO_MODE, false) -> ExtensionMode.NUVIO
            intent.getBooleanExtra(EXTRA_STREMIO_MODE, false) -> ExtensionMode.STREMIO
            intent.getBooleanExtra(EXTRA_CLOUDSTREAM_MODE, false) -> ExtensionMode.CLOUDSTREAM
            else -> ExtensionMode.ANIYOMI
        }

        binding.systemNameLabel.text = extensionMode.label
        binding.systemNameLogo.setImageResource(extensionMode.logoRes)
        binding.systemNameLogo.imageTintList = ColorStateList.valueOf(
            getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        )

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.offscreenPageLimit = 1

        viewPager.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusFirstBrowseButton(viewPager)
        }

        binding.extensionsPill.bind(
            tabs = pillTabs,
            selectedIndex = selectedPillIndex,
            onTabSelected = { position -> selectTab(position) },
            fillWidth = false,
            compact = false,
        )
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedPillIndex = position
                binding.extensionsPill.setSelected(position)
                updateSearchUiForTab(position)
                viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        })

        setupTabs()

        viewPager.post {
            (viewPager.getChildAt(0) as? ViewGroup)?.let { internal ->
                internal.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)

        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentFragment =
                    supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.updateContentBasedOnQuery(s?.toString()?.trim())
                }
            }
        })

        TvKeyboardUtil.setupTvInput(binding.searchViewText)

        if (TvKeyboardUtil.isTv(this)) FocusEffectUtil.applyFocusListener(binding.searchIconButton)
        LensButtonBackground.apply(
            binding.searchLens,
            cornerRadiusPx = 20f * resources.displayMetrics.density,
            ripple = false,
        )
        binding.searchIconButton.setOnClickListener { toggleSearchBar() }
        updateSearchUiForTab(0)

        binding.settingsContainer.updatePadding(top = statusBarHeight, bottom = navBarHeight)

        setupModeButtons()

        if (intent.getBooleanExtra(EXTRA_INSTALL_UPDATES, false)) {
            ExtensionBatchInstaller.installPending(this)
        }
    }

    companion object {
        const val EXTRA_CLOUDSTREAM_MODE = "extra_cloudstream_mode"
        const val EXTRA_STREMIO_MODE = "extra_stremio_mode"
        const val EXTRA_NUVIO_MODE = "extra_nuvio_mode"
        const val EXTRA_INSTALL_UPDATES = "extra_install_updates"
    }

    private fun updateSearchUiForTab(tabPosition: Int) {
        val showSearch = stremioMode || nuvioMode || tabPosition == 0
        binding.searchIconButton.visibility = if (showSearch) View.VISIBLE else View.GONE
        if (!showSearch) collapseSearchBar()
    }

    private fun selectTab(position: Int) {
        if (position == selectedPillIndex) return
        binding.viewPager.setCurrentItem(position, true)

        binding.searchViewText.setText("")
        binding.searchViewText.clearFocus()
        focusFirstBrowseButton(binding.viewPager)
    }

    private var searchExpanded = false

    private fun toggleSearchBar() {
        if (searchExpanded) collapseSearchBar() else expandSearchBar()
    }

    private fun expandSearchBar() {
        if (searchExpanded) return
        searchExpanded = true
        binding.searchButtonIcon.setImageResource(R.drawable.ic_round_close_24)
        binding.searchButtonLabel.text = "Close"
        val heightPx = (56 * resources.displayMetrics.density).toInt()
        binding.searchView.visibility = View.VISIBLE
        binding.searchView.alpha = 0f
        val anim = ValueAnimator.ofInt(0, heightPx).apply {
            duration = if (AnimUtils.enabled(PrefName.SearchHeaderAnimations)) 220 else 0
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                binding.searchView.layoutParams.height = it.animatedValue as Int
                binding.searchView.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.searchView.alpha = 1f
                    binding.searchViewText.requestFocus()
                }
            })
        }
        anim.start()
    }

    private fun collapseSearchBar() {
        if (!searchExpanded) return
        searchExpanded = false
        binding.searchButtonIcon.setImageResource(R.drawable.ic_round_search_24)
        binding.searchButtonLabel.text = "Search"
        binding.searchViewText.clearFocus()
        val startHeight = binding.searchView.layoutParams.height
        val anim = ValueAnimator.ofInt(startHeight, 0).apply {
            duration = if (AnimUtils.enabled(PrefName.SearchHeaderAnimations)) 200 else 0
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                binding.searchView.layoutParams.height = it.animatedValue as Int
                binding.searchView.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.searchView.visibility = View.GONE
                    binding.searchView.layoutParams.height = 0
                }
            })
        }
        anim.start()
    }

    fun focusPill(): Boolean = binding.extensionsPill.requestFocus()

    private fun focusFirstBrowseButton(viewPager: ViewPager2, attempt: Int = 0) {
        viewPager.postDelayed({
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            val rv = currentFragment?.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.allExtensionsRecyclerView
            )
            val browseBtn = rv?.findViewHolderForAdapterPosition(0)
                ?.itemView?.findViewById<android.view.View>(R.id.repoBrowseButton)
            if (browseBtn?.requestFocus() == true) return@postDelayed

            if (attempt == 0) {
                focusFirstBrowseButton(viewPager, 1)
            } else {
                rv?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }, if (attempt == 0) 150 else 100)
    }

    private fun setupTabs() {
        val viewPager = binding.viewPager
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                return when (extensionMode) {
                    ExtensionMode.CLOUDSTREAM -> when (position) {
                        0 -> CloudStreamInstalledFragment()
                        else -> CloudStreamAvailableFragment()
                    }
                    ExtensionMode.STREMIO -> when (position) {
                        0 -> StremioInstalledFragment()
                        else -> StremioAvailableFragment()
                    }
                    ExtensionMode.NUVIO -> when (position) {
                        0 -> NuvioInstalledFragment()
                        else -> NuvioAvailableFragment()
                    }
                    ExtensionMode.ANIYOMI -> when (position) {
                        0 -> InstalledAnimeExtensionsFragment()
                        else -> AnimeExtensionsFragment()
                    }
                }
            }
        }
        pillTabs = when (extensionMode) {
            ExtensionMode.CLOUDSTREAM -> listOf(
                LibraryStatusTab(label = "Installed Plugins", count = 0),
                LibraryStatusTab(label = "Available Plugins", count = 0),
            )
            ExtensionMode.STREMIO -> listOf(
                LibraryStatusTab(label = "Installed Add-ons", count = 0),
                LibraryStatusTab(label = "Available Add-ons", count = 0),
            )
            ExtensionMode.NUVIO -> listOf(
                LibraryStatusTab(label = "Installed Plugins", count = 0),
                LibraryStatusTab(label = "Available Plugins", count = 0),
            )
            ExtensionMode.ANIYOMI -> listOf(
                LibraryStatusTab(label = "Installed Extensions", count = 0),
                LibraryStatusTab(label = "Available Extensions", count = 0),
            )
        }
        selectedPillIndex = 0
        binding.extensionsPill.bind(
            tabs = pillTabs,
            selectedIndex = selectedPillIndex,
            onTabSelected = { position -> selectTab(position) },
            fillWidth = false,
            compact = false,
        )
        viewPager.setCurrentItem(0, false)

        viewPager.post {
            (viewPager.getChildAt(0) as? ViewGroup)?.let { internal ->
                internal.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }

    private fun setupModeButtons() {
        val addLabel = if (stremioMode) "Add add-on" else "Add repository"
        binding.openSettingsButton.visibility = View.VISIBLE
        binding.openSettingsIcon.setImageResource(R.drawable.ic_round_menu_book_24)
        binding.openSettingsLabel.text = addLabel
        binding.openSettingsButton.contentDescription = addLabel
        binding.openSettingsButton.setOnClickListener {
            if (stremioMode) {
                showAddStremioAddonDialog()
                return@setOnClickListener
            }
            if (nuvioMode) {
                showAddNuvioRepoDialog()
                return@setOnClickListener
            }
            val repos = if (cloudStreamMode) {
                CsRepos.repos().toList()
            } else {
                PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toList()
            }
            AddRepositoryBottomSheet.newInstance(
                MediaType.ANIME,
                repos,
                { input, _ -> AddRepositoryBottomSheet.addRepo(input, MediaType.ANIME, cloudStreamMode) },
                { input, _ -> AddRepositoryBottomSheet.removeRepo(input, MediaType.ANIME, cloudStreamMode) },
                cloudStreamMode
            ).show(supportFragmentManager, "add_repo")
        }
        if (TvKeyboardUtil.isTv(this)) FocusEffectUtil.applyFocusListener(binding.openSettingsButton)
        LensButtonBackground.apply(
            binding.openSettingsLens,
            cornerRadiusPx = 20f * resources.displayMetrics.density,
            ripple = false,
        )
    }

    private fun showAddStremioAddonDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/manifest.json"
            setSingleLine(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(32, 24, 32, 24)
            setMinWidth((280 * resources.displayMetrics.density).toInt())
        }
        customAlertDialog().apply {
            setTitle("Add Stremio add-on")
            setMessage("Enter the add-on manifest URL")
            setCustomView(input)
            setOnShowListener {
                TvKeyboardUtil.setupTvInput(input)
                input.requestFocus()
            }
            setPosButton("Add") {
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    ani.sanin.toast("Enter an add-on URL")
                    return@setPosButton
                }
                lifecycleScope.launch {
                    Logger.log("STREMIO: add-on URL submitted url=$url")
                    val installed = StremioAddonManager.installFromUrl(url)
                    if (installed == null) {
                        Logger.log("STREMIO: add-on URL failed url=$url")
                        ani.sanin.toast("Could not add add-on (invalid manifest or already installed)")
                    } else {
                        val detail = listOf(
                            StremioAddonClient.capabilities(installed).joinToString(),
                            StremioAddonClient.supportedTypes(installed).joinToString(),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        ani.sanin.toast(
                            if (detail.isBlank()) "${installed.manifest.name} installed"
                            else "${installed.manifest.name} installed ($detail)"
                        )
                        refreshCurrentList()
                    }
                }
            }
            setNegButton("Cancel")
            show()
        }
    }

    private fun showAddNuvioRepoDialog() {
        val input = EditText(this).apply {
            hint = "https://raw.githubusercontent.com/user/repo/main"
            setSingleLine(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(32, 24, 32, 24)
            setMinWidth((280 * resources.displayMetrics.density).toInt())
        }
        customAlertDialog().apply {
            setTitle("Add Nuvio repository")
            setMessage("Enter a repository URL or manifest.json URL")
            setCustomView(input)
            setOnShowListener {
                TvKeyboardUtil.setupTvInput(input)
                input.requestFocus()
            }
            setPosButton("Add") {
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    ani.sanin.toast("Enter a repository URL")
                    return@setPosButton
                }
                lifecycleScope.launch {
                    Logger.log("NUVIO: repository URL submitted url=$url")
                    runCatching { NuvioRepository.addRepository(url) }
                        .onSuccess { ani.sanin.toast("${it.name} added (${it.scraperCount} plugins)") }
                        .onFailure {
                            Logger.log("NUVIO: repository URL failed url=$url error=${it.message}")
                            ani.sanin.toast("Could not add repository: ${it.message}")
                        }
                    refreshCurrentList()
                }
            }
            setNegButton("Cancel")
            show()
        }
    }

    private fun refreshCurrentList() {
        val fragment = supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        (fragment as? SearchQueryHandler)?.notifyDataChanged()
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
