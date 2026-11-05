package ani.sanin.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.cloudstream.CloudStreamAvailableFragment
import ani.sanin.cloudstream.CloudStreamInstalledFragment
import ani.sanin.cloudstream.CsRepos
import ani.sanin.databinding.ActivityExtensionsBinding
import ani.sanin.getThemeColor
import ani.sanin.initActivity
import ani.sanin.media.MediaType
import ani.sanin.navBarHeight
import ani.sanin.notifications.ExtensionBatchInstaller
import ani.sanin.others.AndroidBug5497Workaround
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.LensButtonBackground
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    private var cloudStreamMode = false

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

        cloudStreamMode = intent.getBooleanExtra(EXTRA_CLOUDSTREAM_MODE, false)

        setupModeToggle()

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

        FocusEffectUtil.applyFocusListener(binding.searchIconButton)
        LensButtonBackground.apply(binding.searchIconButton)
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
        const val EXTRA_INSTALL_UPDATES = "extra_install_updates"
    }

    private fun updateSearchUiForTab(tabPosition: Int) {
        val installed = tabPosition == 0
        binding.searchIconButton.visibility = if (installed) View.VISIBLE else View.GONE
        if (!installed) collapseSearchBar()
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
        binding.searchIconButton.setImageResource(R.drawable.ic_round_close_24)
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
        binding.searchIconButton.setImageResource(R.drawable.ic_round_search_24)
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

    private var modeToggleAnimating = false

    private fun setupModeToggle() {
        binding.modeToggle.setOnClickListener { toggleMode() }
        binding.modeToggle.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        toggleMode()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        FocusEffectUtil.applyFocusListener(binding.modeToggle)
        updateToggleUI(cloudStreamMode, animate = false)
    }

    private fun toggleMode() {
        if (modeToggleAnimating) return
        switchMode(!cloudStreamMode)
    }

    private fun updateToggleUI(cloudStream: Boolean, animate: Boolean) {
        val density = resources.displayMetrics.density
        val thumbStart = 3f * density
        val thumbEnd = (160 - 30 - 3) * density
        binding.modeToggleLabel.text = if (cloudStream) "CloudStream" else "Aniyomi"
        styleToggleDrawables(cloudStream)
        if (!animate || !PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) || !PrefManager.getVal<Boolean>(PrefName.MiscUiAnimations)) {
            binding.modeToggleThumb.translationX = if (cloudStream) thumbEnd else thumbStart
            return
        }
        modeToggleAnimating = true
        val currentX = binding.modeToggleThumb.translationX
        val targetX = if (cloudStream) thumbEnd else thumbStart
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator(1.5f)
            var labelSwapped = false
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                binding.modeToggleThumb.translationX = currentX + (targetX - currentX) * t
                if (!labelSwapped && t > 0.4f) {
                    labelSwapped = true
                    binding.modeToggleLabel.text = if (cloudStream) "CloudStream" else "Aniyomi"
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modeToggleAnimating = false
                    binding.modeToggleThumb.translationX = targetX
                    binding.modeToggleLabel.text = if (cloudStream) "CloudStream" else "Aniyomi"
                }
            })
            start()
        }
    }

    private fun styleToggleDrawables(cloudStream: Boolean) {
        val primary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = 18 * resources.displayMetrics.density
        val thumbRadius = 15 * resources.displayMetrics.density
        val rounded = floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        val thumbRound = floatArrayOf(thumbRadius, thumbRadius, thumbRadius, thumbRadius, thumbRadius, thumbRadius, thumbRadius, thumbRadius)
        binding.modeToggleFill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(primary)
            cornerRadii = rounded
        }
        binding.modeToggleThumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.WHITE)
            cornerRadii = thumbRound
        }
        binding.modeToggleThumbIcon.setImageResource(
            if (cloudStream) R.drawable.ic_toggle_cloudstream else R.drawable.ic_toggle_aniyomi
        )
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

    private fun switchMode(cloudStream: Boolean) {
        if (cloudStreamMode == cloudStream) return
        cloudStreamMode = cloudStream
        updateToggleUI(cloudStream, animate = true)
        collapseSearchBar()
        binding.searchViewText.setText("")
        binding.searchViewText.clearFocus()
        setupTabs()
        setupModeButtons()
    }

    private fun setupTabs() {
        val viewPager = binding.viewPager
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                return if (cloudStreamMode) {
                    when (position) {
                        0 -> CloudStreamInstalledFragment()
                        else -> CloudStreamAvailableFragment()
                    }
                } else {
                    when (position) {
                        0 -> InstalledAnimeExtensionsFragment()
                        else -> AnimeExtensionsFragment()
                    }
                }
            }
        }
        pillTabs = if (cloudStreamMode) {
            listOf(
                LibraryStatusTab(label = "Installed Plugins", count = 0),
                LibraryStatusTab(label = "Available Plugins", count = 0),
            )
        } else {
            listOf(
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
        binding.openSettingsButton.setOnClickListener {
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
        FocusEffectUtil.applyFocusListener(binding.openSettingsButton)
        LensButtonBackground.apply(binding.openSettingsButton)
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
