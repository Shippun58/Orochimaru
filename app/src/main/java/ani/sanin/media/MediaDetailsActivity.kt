package ani.sanin.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.sanin.NavPillPolicy
import ani.sanin.GesturesListener
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.bannerFallbackColor
import ani.sanin.isDarkTheme
import ani.sanin.connections.LogoApi
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.mal.MAL
import com.google.android.material.appbar.AppBarLayout
import ani.sanin.databinding.ActivityMediaBinding
import ani.sanin.getThemeColor
import ani.sanin.initActivity
import ani.sanin.loadImage
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ani.sanin.openLinkInBrowser
import ani.sanin.media.anime.AnimeWatchFragment
import ani.sanin.media.comments.CommentsCarouselAdapter
import ani.sanin.media.comments.CommentsCarouselLayoutManager
import ani.sanin.media.comments.CommentsFragment
import ani.sanin.others.getSerialized
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.LauncherWrapper
import ani.sanin.util.NavPillCustomizer
import ani.sanin.ui.components.EchoNavPillController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class MediaDetailsActivity : AppCompatActivity() {
    lateinit var launcher: LauncherWrapper
    lateinit var binding: ActivityMediaBinding
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    var selected = 0
    var anime = true
    private var hasComments = false
    private lateinit var watchFragment: AnimeWatchFragment
    private lateinit var commentsFragment: CommentsFragment
    private var commentsAdded = false
    var commentTabOpener: (() -> Unit)? = null
    var watchTabOpener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        val providedMedia: Media = intent.getSerialized("media") ?: mediaSingleton ?: emptyMedia()
        intent.removeExtra("media")
        val id = intent.getIntExtra("mediaId", -1)
        val fallbackTitle = intent.getStringExtra("mediaFallbackTitle")
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)

        ThemeManager(this).applyTheme()
        initActivity(this)
        MediaSingleton.bitmap = null
        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        launcher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        if (id != -1) {
            lifecycleScope.launch {
                val media = withContext(Dispatchers.IO) {
                    if (id > 0) {
                        fetchMediaById(id, rescueMode)
                            ?: fallbackTitle?.takeIf { it.isNotBlank() && !rescueMode }?.let { fetchMediaByTitle(it) }
                    } else {
                        fallbackTitle?.takeIf { it.isNotBlank() && !rescueMode }?.let { fetchMediaByTitle(it) }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                bindMedia(media ?: emptyMedia(), savedInstanceState, rescueMode)
            }
        } else {
            bindMedia(providedMedia, savedInstanceState, rescueMode)
        }
    }

    private suspend fun fetchMediaById(id: Int, rescueMode: Boolean): Media? = withContext(Dispatchers.IO) {
        if (rescueMode) {
            MAL.query.getAnimeDetails(id)?.let { Media(it, true) }
        } else {
            Anilist.query.getMedia(id, false)
        }
    }

    private suspend fun fetchMediaByTitle(title: String): Media? = withContext(Dispatchers.IO) {
        runCatching {
            Anilist.query.searchAniManga("ANIME", perPage = 5, search = title)?.results?.firstOrNull()?.id
        }.getOrNull()?.let { Anilist.query.getMedia(it, false) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindMedia(initialMedia: Media, savedInstanceState: Bundle?, rescueMode: Boolean) {
        var media = initialMedia
        if (media.name == "No media found") {
            snackString(media.name)
            onBackPressedDispatcher.onBackPressed()
            return
        }

        mediaSingleton = media

        
        
        watchFragment = supportFragmentManager.findFragmentByTag("watch") as? AnimeWatchFragment
            ?: AnimeWatchFragment()
        commentsFragment = supportFragmentManager.findFragmentByTag("comments") as? CommentsFragment
            ?: CommentsFragment()
        commentsAdded = commentsFragment.isAdded
        Logger.log(
            "MediaDetails: fragments restored watchAdded=${watchFragment.isAdded} " +
                "commentsAdded=${commentsFragment.isAdded} " +
                "watchCount=${supportFragmentManager.fragments.count { it is AnimeWatchFragment }} " +
                "commentsCount=${supportFragmentManager.fragments.count { it is CommentsFragment }}"
        )

        supportFragmentManager.addOnBackStackChangedListener { syncExtensionPrefsUi() }

        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)
        hasComments = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 && !rescueMode

        val bannerTransparency = PrefManager.getVal<Float>(PrefName.BannerTransparency)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (bannerTransparency > 0f) {
            val fallbackUrl = if (isPortrait) media.cover ?: media.banner else media.banner ?: media.cover
            binding.mediaBg?.loadImage(fallbackUrl)
            binding.mediaBg?.alpha = bannerTransparency
            binding.mediaBgGradient?.alpha = bannerTransparency
            binding.mediaDarkenOverlay?.setBackgroundColor(bannerFallbackColor())
            binding.mediaDarkenOverlay?.alpha = 1f - bannerTransparency
            binding.mediaDarkenOverlay?.visibility = View.VISIBLE
            binding.mediaBanner?.loadImage(fallbackUrl)
            binding.mediaBanner?.alpha = bannerTransparency
            binding.mediaBannerNoKen?.loadImage(fallbackUrl)
            binding.mediaBannerNoKen?.alpha = bannerTransparency

            lifecycleScope.launch {
                val fanartUrl = withContext(Dispatchers.IO) {
                    if (isPortrait) LogoApi.getPosterUrl(media.id)
                    else LogoApi.getBackgroundUrl(media.id)
                }

                val url = fanartUrl ?: if (isPortrait) {
                    null
                } else {
                    AniZip.getBackdropUrlWithTmdbFallback(media.id, media.nameRomaji)
                }
                if (url != null) {
                    binding.mediaBg?.loadImage(url)
                    binding.mediaBanner?.loadImage(url)
                    binding.mediaBannerNoKen?.loadImage(url)
                }
            }
        } else {
            binding.mediaBg?.visibility = View.GONE
            binding.mediaBgGradient?.visibility = View.GONE
            binding.mediaBanner?.visibility = View.GONE
            binding.mediaBannerNoKen?.visibility = View.GONE
        }

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        FocusEffectUtil.applyFocusListener(binding.mediaClose)

        if (PrefManager.getVal(PrefName.Incognito)) {
            binding.incognito.visibility = View.VISIBLE
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mediaInfoFragmentContainer, MediaInfoFragment())
                .commitAllowingStateLoss()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cornerPx = NavPillCustomizer.getCornerRadiusDp() * resources.displayMetrics.density
            binding.mediaNavPills?.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
            binding.mediaNavPills?.elevation = 10f
            binding.mediaNavPills?.clipToOutline = true
        }
        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onBgColor = getThemeColor(com.google.android.material.R.attr.colorOnBackground)
        val isMonochrome = PrefManager.getVal<String>(PrefName.Theme).contains("MONOCHROME", ignoreCase = true)
        val navFocusColor = if (isMonochrome && isDarkTheme()) android.graphics.Color.WHITE else if (isMonochrome) android.graphics.Color.BLACK else null
        val navInfo = binding.navPillInfo
        val navWatch = binding.navPillWatch
        val navComments = binding.navPillComments
        val allNav = listOfNotNull(navInfo, navWatch, navComments)
        allNav.forEach { FocusEffectUtil.applyFocusListener(it, borderColor = navFocusColor) }

        binding.navPillBg?.live = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.LiveSideRail)
        binding.navPillBg?.doOnLayout { updateMediaNavIconTints(selected) }
        binding.mediaNavPills?.let { frame ->
            frame.findViewWithTag<LinearLayout>("pill_list")?.let {
                NavPillCustomizer.applyToPillList(it)
            }

            ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
                val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = (16 * resources.displayMetrics.density).toInt() + bottomInset
                    v.layoutParams = lp
                }
                insets
            }
        }

        val mediaPillContainer = binding.mediaNavPills
        mediaNavPill = EchoNavPillController(
            container = mediaPillContainer!!,
            backgroundPill = binding.navPillBg,
            row = null,
            pillList = mediaPillContainer.findViewWithTag("pill_list"),
            pills = allNav,
            labels = listOf(
                getString(R.string.info),
                getString(R.string.watch),
                getString(R.string.reviews)
            ),
            onSearch = null,
            floatToStartOnCollapse = true
        ).also { controller ->
            controller.attach(selected)

            binding.mediaNavPills?.post { controller.syncGlass() }
        }
        attachNavPillScroll()

        fun showWatchTab(container: FrameLayout, animate: Boolean) {
            val ft = supportFragmentManager.beginTransaction()
            val restoredWatch = supportFragmentManager.findFragmentByTag("watch") as? AnimeWatchFragment
            if (restoredWatch != null && restoredWatch !== watchFragment) {
                watchFragment = restoredWatch
                Logger.log("MediaDetails: rebound Watch fragment from FragmentManager")
            }
            val alreadyAdded = watchFragment.isAdded
            if (alreadyAdded) {
                ft.show(watchFragment)
            } else {
                ft.add(R.id.mediaTabContent, watchFragment, "watch")
            }
            Logger.log(
                "MediaDetails: showWatchTab alreadyAdded=$alreadyAdded " +
                    "watchCount=${supportFragmentManager.fragments.count { it is AnimeWatchFragment }}"
            )
            if (::commentsFragment.isInitialized && commentsFragment.isAdded) {
                if (animate && alreadyAdded && PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                    val watchView = watchFragment.requireView()
                    val commentsView = commentsFragment.requireView()
                    watchView.alpha = 0f
                    watchView.scaleX = 0.92f
                    watchView.scaleY = 0.92f
                    ft.hide(commentsFragment).commit()
                    watchView.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                } else {
                    ft.hide(commentsFragment).commit()
                }
            } else {
                ft.commit()
            }
        }

        fun showCommentsTab(container: FrameLayout, animate: Boolean) {
            val parent = container.parent as? View
            parent?.layoutParams = (parent?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = 0 }
            val restoredComments = supportFragmentManager.findFragmentByTag("comments") as? CommentsFragment
            if (restoredComments != null && restoredComments !== commentsFragment) {
                commentsFragment = restoredComments
                Logger.log("MediaDetails: rebound Comments fragment from FragmentManager")
            }
            commentsAdded = commentsFragment.isAdded
            if (!commentsAdded) {
                val ft = supportFragmentManager.beginTransaction()
                commentsFragment = CommentsFragment().apply {
                    arguments = Bundle().apply {
                        putInt("mediaId", media.id)
                        putString("mediaName", media.mainName())
                        putString("mediaFormat", media.format)
                        val commentId = intent.getIntExtra("commentId", -1)
                        if (commentId != -1) putInt("commentId", commentId)
                    }
                }
                if (::watchFragment.isInitialized && watchFragment.isAdded) {
                    ft.hide(watchFragment)
                }
                ft.add(R.id.mediaTabContent, commentsFragment, "comments")
                ft.commit()
                commentsAdded = true
            } else {
                val ft = supportFragmentManager.beginTransaction()
                val watchAlreadyAdded = ::watchFragment.isInitialized && watchFragment.isAdded
                ft.show(commentsFragment)
                if (watchAlreadyAdded) {
                    if (animate && PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                        val watchView = watchFragment.requireView()
                        val commentsView = commentsFragment.requireView()
                        commentsView.alpha = 0f
                        commentsView.scaleX = 0.92f
                        commentsView.scaleY = 0.92f
                        ft.hide(watchFragment).commit()
                        commentsView.animate()
                            .alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(300)
                            .setInterpolator(android.view.animation.OvershootInterpolator())
                            .start()
                    } else {
                        ft.hide(watchFragment).commit()
                    }
                } else {
                    ft.commit()
                }
            }
        }

        fun selectTab(idx: Int, animate: Boolean = true) {
            selected = idx
            updateMediaNavIconTints(selected, animate)

            if (idx == 1) {
                if (bannerTransparency > 0f) {
                    binding.mediaBg?.alpha = 0.14f
                }
                binding.mediaDarkenOverlay?.visibility = View.GONE
            } else {
                binding.mediaBg?.alpha = bannerTransparency
                binding.mediaBgGradient?.alpha = bannerTransparency
                binding.mediaDarkenOverlay?.setBackgroundColor(bannerFallbackColor())
                binding.mediaDarkenOverlay?.alpha = 1f - bannerTransparency
                binding.mediaDarkenOverlay?.visibility =
                    if (bannerTransparency > 0f) View.VISIBLE else View.GONE
            }

            val container = binding.mediaTabContent
            val parent = container?.parent as? View
            parent?.layoutParams = (parent?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = if (idx == 0) (300 * resources.displayMetrics.density).toInt() else 0
            }
            when (idx) {
                0 -> {
                    binding.mediaBgGradient?.visibility = View.VISIBLE
                    binding.mediaRightBg?.visibility = View.VISIBLE
                    binding.mediaInfoFragmentContainer!!.visibility = View.VISIBLE
                    binding.mediaRightPanel!!.visibility = View.GONE
                }
                1 -> {
                    binding.mediaBgGradient?.visibility = View.GONE
                    binding.mediaRightBg?.visibility = View.VISIBLE
                    binding.mediaInfoFragmentContainer!!.visibility = View.GONE
                    binding.mediaRightPanel!!.visibility = View.VISIBLE
                    binding.mediaTabContent?.let {
                        showWatchTab(it, animate)
                        it.requestFocus()

                        it.post {
                            if (::watchFragment.isInitialized && watchFragment.isAdded)
                                watchFragment.focusSelectedSourceChip()
                        }
                    }
                }
                2 -> {
                    binding.mediaBgGradient?.visibility = View.GONE
                    binding.mediaRightBg?.visibility = View.GONE
                    binding.mediaInfoFragmentContainer!!.visibility = View.GONE
                    binding.mediaRightPanel!!.visibility = View.VISIBLE
                    binding.mediaTabContent?.let {
                        showCommentsTab(it, animate)
                        it.requestFocus()
                    }
                }
            }
            val sel = model.loadSelected(media, isDownload)
            sel.window = idx
            model.saveSelected(media.id, sel)
        }

        fun onNavPillClick(idx: Int) {
            if (mediaNavPill?.isCollapsed == true) {
                mediaNavPill?.setCollapsed(false)
            }
            selectTab(idx)
            if (!NavPillPolicy.pillAlwaysVisible) hideNavPills()
        }

        navInfo?.setOnClickListener { onNavPillClick(0) }
        navWatch?.setOnClickListener { onNavPillClick(1) }
        navComments?.visibility = if (hasComments) View.VISIBLE else View.GONE
        if (hasComments) {
            navComments?.setOnClickListener { onNavPillClick(2) }
        }
        commentTabOpener = { selectTab(2) }
        watchTabOpener = { selectTab(1) }

        val savedWindow = if (PrefManager.getVal<Boolean>(PrefName.RememberMediaTab)) {
            media.selected!!.window
        } else {
            INFO_TAB
        }
        var defaultTab = if (savedWindow == 2 && (!hasComments || rescueMode)) 1 else savedWindow
        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
            defaultTab = 1
        }
        if (intent.getStringExtra("FRAGMENT_TO_LOAD") != null && hasComments) defaultTab = 2

        val forcedTab = intent.getIntExtra(TAB_TO_OPEN, NO_TAB)
        if (forcedTab in INFO_TAB..COMMENTS_TAB) defaultTab = forcedTab

        val restoredTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, NO_TAB) ?: NO_TAB
        if (restoredTab in INFO_TAB..COMMENTS_TAB) defaultTab = restoredTab
        selectTab(defaultTab, animate = false)

        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onDoubleClick(event: MotionEvent) {
                snackString(getString(R.string.enable_banner_animations))
            }
            override fun onLongClick(event: MotionEvent) {
                val bannerTitle = getString(R.string.banner, media.userPreferredName)
                ani.sanin.others.ImageViewDialog.newInstance(
                    this@MediaDetailsActivity,
                    bannerTitle,
                    media.banner ?: media.cover
                )
            }
        })
        binding.mediaBg?.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent); true
        }
        binding.mediaBanner?.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent); true
        }
        binding.mediaBannerNoKen?.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent); true
        }

        model.getMedia().observe(this) { updatedMedia ->
            if (updatedMedia != null) {
                media = updatedMedia
                if (media.format?.startsWith("LOCAL") == true) {
                    openLinkInBrowser(media.shareLink)
                }
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }

    private fun syncExtensionPrefsUi() {
        if (!::binding.isInitialized) return
        val hasExtFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentExtensionsContainer) != null

        findViewById<FrameLayout>(R.id.fragmentExtensionsContainer)?.isVisible = hasExtFragment
        findViewById<AppBarLayout>(R.id.mediaAppBar)?.isGone = hasExtFragment
        binding.mediaTabContent?.isVisible = !hasExtFragment
        findViewById<CardView>(R.id.mediaClose)?.isVisible = !hasExtFragment
        findViewById<View>(R.id.mediaNavPills)?.isVisible = !hasExtFragment
        if (!hasExtFragment) binding.root.requestLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, selected)
    }

    override fun onDestroy() {

        if (!isChangingConfigurations) mediaSingleton = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

        syncExtensionPrefsUi()
        binding.navPillBg?.live = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.LiveSideRail)

        
        
        if (NavPillPolicy.pillAlwaysVisible) {
            showNavPills()
        } else {
            hideNavPills()
        }
        binding.mediaTabContent?.post {
            val chipFocused = ::watchFragment.isInitialized && watchFragment.isAdded &&
                    watchFragment.focusSelectedSourceChip()
            if (!chipFocused) binding.mediaTabContent?.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (!NavPillPolicy.pillAlwaysVisible && binding.mediaNavPills?.visibility == View.VISIBLE) {
                        hideNavPills()
                        if (binding.mediaNavPills?.visibility == View.VISIBLE) return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val focusedId = currentFocus?.id
                    if (focusedId == R.id.navPillInfo || focusedId == R.id.navPillWatch || focusedId == R.id.navPillComments) {
                        if (NavPillPolicy.pillAlwaysVisible) return false
                        hideNavPills()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val focusedId = currentFocus?.id
                    if (focusedId == R.id.navPillInfo || focusedId == R.id.navPillWatch || focusedId == R.id.navPillComments) {
                        return true
                    }
                    if (binding.mediaNavPills?.visibility != View.VISIBLE &&
                        currentFocus?.focusSearch(View.FOCUS_LEFT) == null) {
                        showNavPills()
                        focusNavPillForSelectedTab()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (selected == 2) {
                        val rv = binding.mediaTabContent?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.commentsList)
                        if (rv?.isVisible == true && binding.mediaNavPills?.visibility != View.VISIBLE) {
                            val lm = rv.layoutManager as? CommentsCarouselLayoutManager
                            val adapter = rv.adapter as? CommentsCarouselAdapter
                            if (lm != null && adapter != null) {
                                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && lm.focusedPosition < adapter.itemCount - 1) {
                                    lm.scrollToNext()
                                    adapter.setFocusedPosition(lm.focusedPosition)
                                    return true
                                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && lm.focusedPosition > 0) {
                                    lm.scrollToPrevious()
                                    adapter.setFocusedPosition(lm.focusedPosition)
                                    return true
                                }
                            }
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (selected == 2) {
                        val rv = binding.mediaTabContent?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.commentsList)
                        if (rv?.isVisible == true && binding.mediaNavPills?.visibility != View.VISIBLE) {
                            val lm = rv.layoutManager as? CommentsCarouselLayoutManager
                            val adapter = rv.adapter as? CommentsCarouselAdapter
                            if (lm != null && adapter != null) {
                                val pos = lm.focusedPosition
                                if (pos in 0 until adapter.itemCount) {
                                    val frag = supportFragmentManager.findFragmentByTag("comments") as? CommentsFragment
                                    frag?.let {
                                        adapter.currentList.getOrNull(pos)?.let { comment -> it.openCommentDetail(comment) }
                                    }
                                    return true
                                }
                            }
                        }
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (binding.mediaNavPills?.visibility != View.VISIBLE) {
                        showNavPills()
                        focusNavPillForSelectedTab()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun showNavPills() {
        binding.mediaNavPills?.visibility = View.VISIBLE
        binding.navPillBg?.doOnLayout { updateMediaNavIconTints(selected) }
    }

    fun hideNavPills() {
        binding.mediaNavPills?.visibility = View.GONE
        val focusTarget = when (selected) {
            0 -> binding.mediaInfoFragmentContainer
            else -> binding.mediaTabContent ?: binding.mediaRightPanel
        }
        focusTarget?.requestFocus()
    }

    private fun updateMediaNavIconTints(selectedIdx: Int, animate: Boolean = false) {
        val customColor = NavPillCustomizer.getIconColor()
        val pills = listOfNotNull(binding.navPillInfo, binding.navPillWatch, binding.navPillComments)
        mediaNavPill?.select(selectedIdx, animate)
        pills.forEach { pill ->
            pill.imageTintList = ColorStateList.valueOf(customColor)
        }
        mediaNavPill?.refreshLabelTint()
    }

    private var mediaNavPill: EchoNavPillController? = null

    private fun attachNavPillScroll() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return
        mediaNavPill?.startScrollTracking(binding.root)
    }

    fun focusNavPillForSelectedTab() {
        val targetId = when (selected) {
            0 -> R.id.navPillInfo
            1 -> R.id.navPillWatch
            2 -> R.id.navPillComments
            else -> R.id.navPillInfo
        }
        val target = binding.root.findViewById<View>(targetId)
        if (target?.visibility == View.VISIBLE) {
            target.requestFocus()
        } else {
            binding.navPillInfo?.requestFocus()
        }
    }

    companion object {
        var mediaSingleton: Media? = null

        const val TAB_TO_OPEN = "TAB_TO_OPEN"

        const val NO_TAB = -1

        const val INFO_TAB = 0
        const val WATCH_TAB = 1
        const val COMMENTS_TAB = 2

        private const val STATE_SELECTED_TAB = "selectedTab"
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        needsInitialClick: Boolean = false,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch {
                    clicked()
                }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.LikeButtonAnimations)) {
                ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
                ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
                delay(100.milliseconds)
            }

            if (clicked) {
                if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.LikeButtonAnimations)) {
                    ObjectAnimator.ofArgb(
                        image,
                        "ColorFilter",
                        ContextCompat.getColor(context, c1),
                        ContextCompat.getColor(context, c2)
                    ).setDuration(120).start()
                } else {
                    image.colorFilter = android.graphics.PorterDuffColorFilter(
                        ContextCompat.getColor(context, c2),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.LikeButtonAnimations)) {
                ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
                ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
                delay(120.milliseconds)
                ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
                ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
                delay(200.milliseconds)
                if (clicked) {
                    ObjectAnimator.ofArgb(
                        image,
                        "ColorFilter",
                        ContextCompat.getColor(context, c2),
                        ContextCompat.getColor(context, c1)
                    ).setDuration(200).start()
                }
            } else {
                if (clicked) {
                    image.colorFilter = android.graphics.PorterDuffColorFilter(
                        ContextCompat.getColor(context, c1),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }
}
