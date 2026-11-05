package ani.sanin.home
import ani.sanin.settings.AnimUtils

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ani.sanin.MainActivity
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.blurImage
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.mal.MAL
import ani.sanin.connections.anilist.AnilistHomeViewModel
import ani.sanin.connections.anilist.getUserId
import ani.sanin.currContext
import ani.sanin.databinding.FragmentHomeBinding
import ani.sanin.getThemeColor
import ani.sanin.home.status.UserStatusAdapter
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaListViewActivity
import ani.sanin.media.user.ListActivity
import ani.sanin.navBarHeight
import ani.sanin.openLinkInBrowser
import ani.sanin.profile.ProfileActivity
import ani.sanin.px
import ani.sanin.setSafeOnClickListener
import ani.sanin.util.FocusEffectUtil
import ani.sanin.setSlideIn
import ani.sanin.setSlideUp
import ani.sanin.settings.SettingsDialogFragment
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefManager.asLiveBool
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.bannerCardSizePx
import ani.sanin.sizeBannerCard
import ani.sanin.statusBarHeight
import ani.sanin.tryWithSuspend
import ani.sanin.util.Logger
import ani.sanin.util.bindClassicChips
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val rvDataMap = mutableMapOf<RecyclerView, List<Media>>()
    private var navBannerCurrentMediaId = -1
    private var navBannerCurrentMedia: Media? = null
    private var navBannerSlotA = true
    private var homeBannerItems: List<Media> = emptyList()
    private var homeBannerLogos: Map<Int, String?> = emptyMap()

    private var catalogueMode = false

    private var catalogueJob: Job? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    val model: AnilistHomeViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scope = lifecycleScope
        catalogueMode = AnimeCatalogueHome.isCatalogueSource(AnimeCatalogueHome.savedSourceId())
        Logger.log("HomeFragment: catalogueMode=$catalogueMode source='${AnimeCatalogueHome.savedSourceId()}'")
        fun load() {
            Logger.log("Loading HomeFragment")
            if (activity != null && _binding != null) lifecycleScope.launch(Dispatchers.Main) {
                val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                val bannerMode: Int = PrefManager.getVal(PrefName.HomeBannerMode)
                val isCarouselMode = bannerMode == 0
                val showProfileHeader = bannerMode == 1

                if (rescueMode && MAL.token != null) {
                    binding.homeUserName.text = MAL.username ?: Anilist.username
                } else {
                    binding.homeUserName.text = Anilist.username
                }

                if (!rescueMode) {
                    binding.homeUserEpisodesWatched.text = Anilist.episodesWatched.toString()
                    binding.homeUserChaptersRead.text = Anilist.chapterRead.toString()
                } else {
                    binding.homeUserEpisodesWatched.text = MAL.episodesWatched?.toString() ?: "—"
                    binding.homeUserChaptersRead.text = MAL.chaptersRead?.toString() ?: "—"
                }

                if (isCarouselMode) {
                    binding.homeUserBg.visibility = View.GONE
                    binding.homeUserBgNoKen.visibility = View.GONE
                    binding.homeUserDataContainer.visibility = View.GONE
                    binding.homeBannerCardWrap.visibility = View.VISIBLE
                    setupBannerCarousel()
                } else if (showProfileHeader) {
                    binding.homeBannerCardWrap.visibility = View.GONE
                    binding.homeUserBg.visibility = View.VISIBLE
                    binding.homeUserBgNoKen.visibility = View.VISIBLE
                    val bannerAnimations: Boolean = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.BannerAnimations)
                    val bannerUrl = if (rescueMode) (Anilist.bg ?: MAL.avatar) else Anilist.bg
                    blurImage(
                        if (bannerAnimations) binding.homeUserBg else binding.homeUserBgNoKen,
                        bannerUrl
                    )
                } else if (bannerMode == 2) {
                    binding.homeBannerCardWrap.visibility = View.GONE
                    binding.homeUserBg.visibility = View.GONE
                    binding.homeUserBgNoKen.visibility = View.GONE
                    binding.homeNavigatingBannerContainer.visibility = View.VISIBLE
                } else {
                    binding.homeBannerCardWrap.visibility = View.GONE
                    binding.homeUserBg.visibility = View.GONE
                    binding.homeUserBgNoKen.visibility = View.GONE
                    binding.homeNavigatingBannerContainer.visibility = View.GONE
                }
                applyHomeBannerLandscapeMode()

                binding.homeUserDataProgressBar.visibility = View.GONE

                val listUserId = Anilist.userid ?: 0
                val listUsername = if (rescueMode) MAL.username ?: Anilist.username else Anilist.username
                binding.homeAnimeList.setOnClickListener {
                    ContextCompat.startActivity(
                        requireActivity(), Intent(requireActivity(), ListActivity::class.java)
                            .putExtra("anime", true)
                            .putExtra("userId", listUserId)
                            .putExtra("username", listUsername), null
                    )
                }

                if (showProfileHeader) {
                    binding.homeUserDataContainer.visibility = View.VISIBLE
                    binding.homeUserDataContainer.layoutAnimation =
                        LayoutAnimationController(setSlideUp(), 0.25f)
                    binding.homeAnimeList.visibility = View.VISIBLE
                    binding.homeMangaList.visibility = View.GONE
                    binding.homeListContainer.layoutAnimation =
                        LayoutAnimationController(setSlideIn(), 0.25f)
                } else {
                    binding.homeUserDataContainer.visibility = View.GONE
                    binding.homeAnimeList.visibility = View.GONE
                    binding.homeMangaList.visibility = View.GONE
                }
            }
            else {
                snackString(currContext()?.getString(R.string.please_reload))
            }
        }
        setupSectionFocusChain()
        applyHomeBannerFocusChain()
        setupHomeBannerWatchBtn()
        binding.homeUserChaptersReadRow.visibility = View.GONE
        binding.homeContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (_binding == null || newFocus == null) return@addOnGlobalFocusChangeListener
            val bannerMode: Int = PrefManager.getVal(PrefName.HomeBannerMode)
            if (bannerMode != 2) return@addOnGlobalFocusChangeListener
            var currentView: View = newFocus
            var parentRv: RecyclerView? = null
            var itemView: View = newFocus
            while (currentView.parent != null) {
                val parent = currentView.parent
                if (parent is RecyclerView) {
                    parentRv = parent
                    itemView = currentView
                    break
                }
                if (parent is View) currentView = parent else break
            }
            if (parentRv != null) {
                val pos = parentRv.getChildAdapterPosition(itemView)
                val media = rvDataMap[parentRv]?.getOrNull(pos)
                if (media != null && media.id != navBannerCurrentMediaId) {
                    updateNavigatingBanner(media)
                }
            }
        }

        val duration = if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.HomeAnimations)) (PrefManager.getVal<Float>(PrefName.AnimationSpeed) * 200).toLong() else 0L
        var height = statusBarHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = activity?.window?.decorView?.rootWindowInsets?.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    height =
                        max(
                            statusBarHeight,
                            min(
                                displayCutout.boundingRects[0].width(),
                                displayCutout.boundingRects[0].height()
                            )
                        )
                }
            }
        }
        binding.homeRefresh.setSlingshotDistance(height + 128)
        binding.homeRefresh.setProgressViewEndTarget(false, height + 128)
        binding.homeRefresh.setOnRefreshListener {
            if (catalogueMode) loadCatalogue() else Refresh.activity[1]!!.postValue(true)
        }
        binding.homeCatalogueRefresh.setOnRefreshListener { loadCatalogue() }

        binding.homeUserDataProgressBar.visibility = View.VISIBLE
        binding.homeUserDataContainer.visibility = View.GONE
        if (catalogueMode) {
            applyCatalogueMode()
            loadCatalogue()
        } else if (model.loaded) {
            load()
        }

        model.getListImages().observe(viewLifecycleOwner) {
            if (it != null && it.isNotEmpty()) {
                binding.homeAnimeListImage.loadImage(it[0] ?: "https://bit.ly/31bsIHq")
                binding.homeMangaListImage.loadImage(it[1] ?: "https://bit.ly/2ZGfcuG")
            }
        }

        fun initContinueWatchingRecyclerView(
            mode: LiveData<ArrayList<Media>>,
            container: View,
            recyclerView: RecyclerView,
            progress: View,
            empty: View,
            title: View,
            more: View,
            string: String
        ) {
            container.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            empty.visibility = View.GONE
            title.visibility = View.INVISIBLE
            more.visibility = View.INVISIBLE

            mode.observe(viewLifecycleOwner) {
                recyclerView.visibility = View.GONE
                empty.visibility = View.GONE
                if (it != null) {
                    if (it.isNotEmpty()) {
                        rvDataMap[recyclerView] = it
                        recyclerView.adapter = ConcatAdapter(
                            ContinueWatchingLandscapeAdapter(it) { media ->
                                ContextCompat.startActivity(
                                    requireContext(),
                                    Intent(requireContext(), ani.sanin.media.MediaDetailsActivity::class.java)
                                        .putExtra("media", media)
                                        .putExtra("anime", true),
                                    null
                                )
                            },
                            SectionMoreAdapter { v ->
                                MediaListViewActivity.passedMedia = it
                                ContextCompat.startActivity(
                                    v.context, Intent(v.context, MediaListViewActivity::class.java)
                                        .putExtra("title", string),
                                    null
                                )
                            }
                        )
                        recyclerView.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        more.setOnClickListener { i ->
                            MediaListViewActivity.passedMedia = it
                            ContextCompat.startActivity(
                                i.context, Intent(i.context, MediaListViewActivity::class.java)
                                    .putExtra("title", string),
                                null
                            )
                        }
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)

                    } else {
                        empty.visibility = View.VISIBLE
                    }
                    more.visibility = View.VISIBLE
                    title.visibility = View.VISIBLE
                    more.startAnimation(setSlideUp())
                    title.startAnimation(setSlideUp())
                    progress.visibility = View.GONE
                    setupSectionFocusChain()
                    applyHomeBannerFocusChain()
                }
            }
        }

        fun initRecyclerView(
            mode: LiveData<ArrayList<Media>>,
            container: View,
            recyclerView: RecyclerView,
            progress: View,
            empty: View,
            title: View,
            more: View,
            string: String
        ) {
            container.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            empty.visibility = View.GONE
            title.visibility = View.INVISIBLE
            more.visibility = View.INVISIBLE

            mode.observe(viewLifecycleOwner) {
                recyclerView.visibility = View.GONE
                empty.visibility = View.GONE
                if (it != null) {
                    if (it.isNotEmpty()) {
                        rvDataMap[recyclerView] = it
                        val openMore: (View) -> Unit = { v ->
                            MediaListViewActivity.passedMedia = it
                            ContextCompat.startActivity(
                                v.context, Intent(v.context, MediaListViewActivity::class.java)
                                    .putExtra("title", string),
                                null
                            )
                        }
                        recyclerView.adapter = ConcatAdapter(
                            MediaAdaptor(0, it, requireActivity()),
                            SectionMoreAdapter(openMore)
                        )
                        recyclerView.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        more.setOnClickListener { i -> openMore(i) }
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)

                    } else {
                        empty.visibility = View.VISIBLE
                    }
                    more.visibility = View.VISIBLE
                    title.visibility = View.VISIBLE
                    more.startAnimation(setSlideUp())
                    title.startAnimation(setSlideUp())
                    progress.visibility = View.GONE
                    setupSectionFocusChain()
                    applyHomeBannerFocusChain()
                }
            }

        }

        initContinueWatchingRecyclerView(
            model.getAnimeContinue(),
            binding.homeContinueWatchingContainer,
            binding.homeWatchingRecyclerView,
            binding.homeWatchingProgressBar,
            binding.homeWatchingEmpty,
            binding.homeContinueWatch,
            binding.homeContinueWatchMore,
            getString(R.string.continue_watching)
        )
        binding.homeWatchingBrowseButton.setOnClickListener {
            (requireActivity() as? MainActivity)?.navPillsViewModel?.setTab(1)
        }

        initRecyclerView(
            model.getAnimeFav(),
            binding.homeFavAnimeContainer,
            binding.homeFavAnimeRecyclerView,
            binding.homeFavAnimeProgressBar,
            binding.homeFavAnimeEmpty,
            binding.homeFavAnime,
            binding.homeFavAnimeMore,
            getString(R.string.fav_anime)
        )

        initRecyclerView(
            model.getAnimePlanned(),
            binding.homePlannedAnimeContainer,
            binding.homePlannedAnimeRecyclerView,
            binding.homePlannedAnimeProgressBar,
            binding.homePlannedAnimeEmpty,
            binding.homePlannedAnime,
            binding.homePlannedAnimeMore,
            getString(R.string.planned_anime)
        )
        binding.homePlannedAnimeBrowseButton.setOnClickListener {
            (requireActivity() as? MainActivity)?.navPillsViewModel?.setTab(1)
        }

        model.getAnimeContinue().observe(viewLifecycleOwner) { list ->
            if (_binding != null && list != null && list.isNotEmpty() && PrefManager.getVal<Int>(PrefName.HomeBannerMode) == 2
                && navBannerCurrentMediaId == -1) {
                updateNavigatingBanner(list[0])
            }
        }

        initRecyclerView(
            model.getRecommendation(),
            binding.homeRecommendedContainer,
            binding.homeRecommendedRecyclerView,
            binding.homeRecommendedProgressBar,
            binding.homeRecommendedEmpty,
            binding.homeRecommended,
            binding.homeRecommendedMore,
            getString(R.string.recommended)
        )

        initRecyclerView(
            model.getMissingSequels(),
            binding.homeMissingSequelsContainer,
            binding.homeMissingSequelsRecyclerView,
            binding.homeMissingSequelsProgressBar,
            binding.homeMissingSequelsEmpty,
            binding.homeMissingSequels,
            binding.homeMissingSequelsMore,
            getString(R.string.missing_sequels)
        )

        binding.homeHiddenItemsContainer.visibility = View.GONE
        model.getHidden().observe(viewLifecycleOwner) {
            if (it != null) {
                if (it.isNotEmpty()) {
                    binding.homeHiddenItemsRecyclerView.adapter =
                        ConcatAdapter(
                            MediaAdaptor(0, it, requireActivity()),
                            SectionMoreAdapter { _ ->
                                MediaListViewActivity.passedMedia = it
                                ContextCompat.startActivity(
                                    requireActivity(),
                                    Intent(requireActivity(), MediaListViewActivity::class.java)
                                        .putExtra("title", getString(R.string.hidden)),
                                    null
                                )
                            }
                        )
                    binding.homeHiddenItemsRecyclerView.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    binding.homeContinueWatch.setOnLongClickListener {
                        binding.homeHiddenItemsContainer.visibility = View.VISIBLE
                        binding.homeHiddenItemsRecyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)
                        true
                    }
                    binding.homeHiddenItemsMore.setSafeOnClickListener { _ ->
                        MediaListViewActivity.passedMedia = it
                        ContextCompat.startActivity(
                            requireActivity(),
                            Intent(requireActivity(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.hidden)),
                            null
                        )
                    }
                    binding.homeHiddenItemsTitle.setOnLongClickListener {
                        binding.homeHiddenItemsContainer.visibility = View.GONE
                        true
                    }
                } else {
                    binding.homeContinueWatch.setOnLongClickListener {
                        snackString(getString(R.string.no_hidden_items))
                        true
                    }
                }
            } else {
                binding.homeContinueWatch.setOnLongClickListener {
                    snackString(getString(R.string.no_hidden_items))
                    true
                }
            }
        }

        model.empty.observe(viewLifecycleOwner)
        {
            binding.homeSaninContainer.visibility = if (it == true) View.VISIBLE else View.GONE
            if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.AnimatedVectorDrawables)) (binding.homeSaninIcon.drawable as? Animatable)?.start()
            binding.homeSaninContainer.startAnimation(setSlideUp())
            binding.homeSaninIcon.setSafeOnClickListener {
                if (AnimUtils.enabled(ani.sanin.settings.saving.PrefName.AnimatedVectorDrawables)) (binding.homeSaninIcon.drawable as? Animatable)?.start()
            }
        }

        val array = arrayOf(
            "AnimeContinue",
            "AnimeFav",
            "AnimePlanned",
        )

        val containers = arrayOf(
            binding.homeContinueWatchingContainer,
            binding.homeFavAnimeContainer,
            binding.homePlannedAnimeContainer,
            binding.homeMissingSequelsContainer,
            binding.homeRecommendedContainer,
        )

        var running = false
        val live = Refresh.activity.getOrPut(1) { MutableLiveData(true) }

        PrefManager.getLiveVal(PrefName.RescueMode, false).asLiveBool()
            .observe(viewLifecycleOwner) { inRescueMode ->

                val alOnlySections = listOf(
                    binding.homeFavAnimeContainer,
                )
                binding.homeRescueModeBanner.visibility = View.GONE
                if (inRescueMode) {
                    alOnlySections.forEach { it.visibility = View.GONE }

                    binding.homeContinueWatchingContainer.visibility = View.VISIBLE
                    binding.homePlannedAnimeContainer.visibility = View.VISIBLE
                } else {
                    val homeLayoutShow: List<Boolean> = PrefManager.getVal(PrefName.HomeLayout)
                    val alOnlyIndices = listOf(1)
                    alOnlySections.forEachIndexed { idx, view ->
                        if (homeLayoutShow.getOrElse(alOnlyIndices[idx]) { true }) {
                            view.visibility = View.VISIBLE
                        } else {
                            view.visibility = View.GONE
                        }
                    }
                }
                setupSectionFocusChain()
                applyHomeBannerFocusChain()
            }

        live.observe(viewLifecycleOwner) { shouldRefresh ->
            if (catalogueMode) {
                if (shouldRefresh && binding.homeCatalogueSections.childCount == 0 &&
                    catalogueJob?.isActive != true
                ) {
                    loadCatalogue()
                }
                return@observe
            }
            if (!running && shouldRefresh) {
                running = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                        if (rescueMode) {
                            if (MAL.token != null && MAL.episodesWatched == null) {
                                tryWithSuspend { MAL.query.getUserData() }
                            }
                            withContext(Dispatchers.Main) { load() }
                        } else {
                            Anilist.userid =
                                PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
                                    ?.toIntOrNull()
                            if (Anilist.userid == null) {
                                withContext(Dispatchers.Main) {
                                    getUserId(requireContext()) {
                                        load()
                                    }
                                }
                            } else {
                                getUserId(requireContext()) {
                                    load()
                                }
                            }
                        }
                        model.loaded = true
                    }

                    if (Anilist.anilistDisabledSignal && !PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                        withContext(Dispatchers.Main) {
                            if (isAdded && _binding != null) {
                                requireContext().customAlertDialog().apply {
                                    setTitle(R.string.rescue_mode_prompt_title)
                                    setMessage(R.string.rescue_mode_prompt_message)
                                    setPosButton(R.string.rescue_mode_enable) {
                                        PrefManager.setVal(PrefName.RescueMode, true)
                                        PrefManager.setVal(PrefName.SelectedMediaType, 0)
                                        PrefManager.setVal(PrefName.SelectedTracker, 1)
                                        PrefManager.setVal(PrefName.ContentMode, "anime")
                                        Anilist.anilistDisabledSignal = false
                                        val intent = Intent(requireContext(), MainActivity::class.java)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                        startActivity(intent)
                                        activity?.overridePendingTransition(0, 0)
                                        activity?.finish()
                                        activity?.overridePendingTransition(0, 0)
                                    }
                                    setNegButton(R.string.no)
                                    show()
                                }
                            }
                        }
                    }

                    var empty = true
                    val homeLayoutShow: List<Boolean> = PrefManager.getVal(PrefName.HomeLayout)
                    var homeLayoutOrder: List<Int> = PrefManager.getVal(PrefName.HomeLayoutOrder)
                    if (homeLayoutOrder.isEmpty()) {
                        homeLayoutOrder = containers.indices.toList()
                    }

                    withContext(Dispatchers.Main) {
                        containers.indices.forEach { i ->
                            val show = homeLayoutShow.getOrElse(i) { true }
                            if (show) {
                                empty = false
                            } else {
                                containers[i].visibility = View.GONE
                            }
                        }

                        var insertIndex = binding.homeContainer.indexOfChild(binding.homeHiddenItemsContainer) + 1

                        homeLayoutOrder.forEach { i ->
                            val container = containers.getOrNull(i)
                            if (container != null) {
                                binding.homeContainer.removeView(container)
                                binding.homeContainer.addView(container, insertIndex)
                                insertIndex++
                            }
                        }
                    }

                    val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                    val initHomePage = async(Dispatchers.IO) { model.initHomePage() }
                    val setListImages = async(Dispatchers.IO) { model.setListImages() }
                    if (!rescueMode) {
                        awaitAll(initHomePage, setListImages)
                    } else {
                        awaitAll(initHomePage, setListImages)
                    }

                    withContext(Dispatchers.Main) {
                        model.empty.postValue(empty)
                        binding.homeHiddenItemsContainer.visibility = View.GONE
                    }

                    live.postValue(false)
                    _binding?.homeRefresh?.isRefreshing = false
                    running = false
                }
            }
        }
    }

    private fun <T : View> id(id: Int): T = requireView().findViewById(id)

    private class HomeSection(val container: ViewGroup, val rv: RecyclerView, val title: TextView)

    private val sectionItemFocusListeners = mutableSetOf<RecyclerView>()
    private var cwItemUpTarget = View.NO_ID

    private fun homeSections(): List<HomeSection> = listOf(
        HomeSection(binding.homeContinueWatchingContainer, binding.homeWatchingRecyclerView, binding.homeContinueWatch),
        HomeSection(binding.homeFavAnimeContainer, binding.homeFavAnimeRecyclerView, binding.homeFavAnime),
        HomeSection(binding.homePlannedAnimeContainer, binding.homePlannedAnimeRecyclerView, binding.homePlannedAnime),
        HomeSection(binding.homeMissingSequelsContainer, binding.homeMissingSequelsRecyclerView, binding.homeMissingSequels),
        HomeSection(binding.homeRecommendedContainer, binding.homeRecommendedRecyclerView, binding.homeRecommended),
    )

    private fun setupSectionFocusChain() {
        val sections = homeSections()

        for (sec in sections) {
            sec.rv.nextFocusUpId = View.NO_ID
            sec.rv.nextFocusDownId = View.NO_ID
            if (sec.container.childCount > 0) {
                val row = sec.container.getChildAt(0)
                row.isFocusable = false
                row.nextFocusUpId = View.NO_ID
                row.nextFocusDownId = View.NO_ID
            }
            if (sectionItemFocusListeners.add(sec.rv)) {
                sec.rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) { wireSectionItem(view) }
                    override fun onChildViewDetachedFromWindow(view: View) {}
                })
            }
        }

        val wired = homeSections()
            .filter {
                it.container.visibility == View.VISIBLE &&
                    it.rv.visibility == View.VISIBLE &&
                    it.title.visibility == View.VISIBLE
            }
            .sortedBy { binding.homeContainer.indexOfChild(it.container) }
        for (i in wired.indices) {
            val sec = wired[i]
            val row = sec.container.getChildAt(0)
            val prev = wired.getOrNull(i - 1)
            val next = wired.getOrNull(i + 1)
            sec.rv.isFocusable = true
            row.isFocusable = true
            row.nextFocusDownId = sec.rv.id
            sec.rv.nextFocusUpId = row.id
            sec.rv.nextFocusDownId = next?.rv?.id ?: View.NO_ID
            row.nextFocusUpId = prev?.rv?.id ?: View.NO_ID
        }

        for (sec in sections) {
            for (i in 0 until sec.rv.childCount) {
                wireSectionItem(sec.rv.getChildAt(i))
            }
        }
    }

    private fun wireSectionItem(view: View) {
        val parentRv = view.parent as? RecyclerView ?: return
        val wired = homeSections()
            .filter {
                it.container.visibility == View.VISIBLE &&
                    it.rv.visibility == View.VISIBLE &&
                    it.title.visibility == View.VISIBLE
            }
            .sortedBy { binding.homeContainer.indexOfChild(it.container) }
        val idx = wired.indexOfFirst { it.rv == parentRv }
        if (idx < 0) return
        val prev = wired.getOrNull(idx - 1)
        val next = wired.getOrNull(idx + 1)
        if (parentRv == binding.homeWatchingRecyclerView) {
            view.nextFocusUpId = cwItemUpTarget
        } else {
            view.nextFocusUpId = prev?.rv?.id ?: View.NO_ID
        }
        view.nextFocusDownId = next?.rv?.id ?: View.NO_ID
    }

    private var bannerCarouselAdapter: BannerCarouselAdapter? = null
    private val bannerSnapHelper = PagerSnapHelper()
    private var bannerAutoScrollHandler: Handler? = null
    private var bannerAutoScrollRunnable: Runnable? = null

    private fun setupBannerCarousel() {
        applyHomeBannerLandscapeMode()
        val rv = binding.homeBannerCarousel
        rv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rv.isFocusable = true
        rv.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.nextFocusDownId = R.id.homeWatchingRecyclerView

        rv.itemAnimator = null
        bannerSnapHelper.attachToRecyclerView(rv)

        model.getTrendingBanner().observe(viewLifecycleOwner) { items ->
            if (items != null && items.isNotEmpty()) {
                homeBannerItems = items
                lifecycleScope.launch(Dispatchers.IO) {
                    val allImages = AniZip.getImagesBatch(items.map { it.id })
                    val urls = mutableMapOf<Int, String?>()
                    for (m in items) {
                        val aUrl = allImages[m.id]?.backdropUrl
                        if (!aUrl.isNullOrBlank()) {
                            urls[m.id] = aUrl
                        } else {
                            urls[m.id] = try {
                                val results = ani.sanin.connections.tmdb.Tmdb.search(m.nameRomaji)
                                results.firstOrNull()?.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 1280) }
                            } catch (_: Exception) { null }
                        }
                    }
                    val logos = ani.sanin.connections.LogoApi.getLogosBatch(items.map { it.id })
                    withContext(Dispatchers.Main) {
                        val modern = isModernBanner()
                        bannerCarouselAdapter = BannerCarouselAdapter(
                            items, lifecycleScope, { media ->
                                val intent = Intent(requireContext(), ani.sanin.media.MediaDetailsActivity::class.java)
                                intent.putExtra("media", media)
                                intent.putExtra("anime", true)
                                startActivity(intent)
                            }, urls, logos,
                            nextFocusDownId = R.id.homeWatchingRecyclerView,
                            layoutRes = layoutForBannerType(modern),

                            cardMode = !modern && !isClassicBanner(),
                            hideDescription = !modern,
                            modernMode = modern,
                        )
                        rv.adapter = bannerCarouselAdapter
                        homeBannerLogos = logos
                        val start = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % items.size)
                        rv.scrollToPosition(start)
                        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            private var lastTarget = -1

                            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                                val overlay = binding.homeBannerOverlay
                                if (!overlay.isVisible) return
                                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                                val child = lm.getChildAt(0) ?: return
                                val pos = lm.getPosition(child)
                                if (pos == RecyclerView.NO_POSITION || homeBannerItems.isEmpty()) return
                                val cardW = child.width
                                val stripW = overlay.width - cardW / 4
                                if (cardW <= 0 || stripW <= 0) return
                                val progress = (-child.left).toFloat() / cardW
                                val real = pos % homeBannerItems.size
                                val target = if (progress < 0.5f) real else (real + 1) % homeBannerItems.size
                                if (target != lastTarget) {
                                    lastTarget = target
                                    updateHomeBannerOverlay(homeBannerItems[target])
                                }
                                val scale = stripW.toFloat() / cardW
                                overlay.translationX =
                                    (if (progress < 0.5f) child.left else child.left + cardW) * scale
                            }

                            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                    lastTarget = -1
                                    binding.homeBannerOverlay.translationX = 0f
                                    updateHomeBannerOverlayForCurrent()
                                }
                            }
                        })
                        applyHomeBannerLandscapeMode()
                        setupHomeBannerWatchBtn()
                        applyHomeBannerFocusChain()
                        setupBannerDots(rv, items.size)
                        startBannerAutoScroll(rv, items.size)
                        updateHomeBannerOverlayForCurrent()
                    }
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { model.loadTrendingBanner() }
    }

    private fun setupBannerDots(rv: RecyclerView, itemCount: Int) {
        val dots = binding.homeBannerDots
        dots.removeAllViews()
        val density = resources.displayMetrics.density

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val shown = if (isPortrait) minOf(itemCount, 7) else itemCount
        val dotsList = mutableListOf<View>()
        for (i in 0 until shown) {
            val dot = View(requireContext())
            val w = if (i == 0) (32 * density).toInt() else (12 * density).toInt()
            val lp = LinearLayout.LayoutParams(w, (4 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            dot.layoutParams = lp
            dot.background = if (i == 0)
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_active)
            else
                ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_inactive)
            dot.setOnClickListener {
                val lm = rv.layoutManager as LinearLayoutManager
                val current = lm.findFirstVisibleItemPosition()
                val currentReal = current % itemCount
                val target = (currentReal / shown) * shown + i
                if (target == currentReal || target >= itemCount) return@setOnClickListener
                rv.smoothScrollToPosition(current + (target - currentReal))
            }
            dots.addView(dot)
            dotsList.add(dot)
        }
        dots.visibility = View.VISIBLE

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    resetHomeBannerAutoScroll()
                    return
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (itemCount == 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val raw = lm.findFirstVisibleItemPosition()
                    if (raw == RecyclerView.NO_POSITION) return
                    resetHomeBannerAutoScroll()
                    val pos = raw % itemCount % shown
                    for (i in 0 until dotsList.size) {
                        val dot = dotsList[i]
                        val lp = dot.layoutParams
                        lp.width = if (i == pos) (32 * density).toInt() else (12 * density).toInt()
                        dot.layoutParams = lp
                        dot.background = if (i == pos)
                            ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_active)
                        else
                            ContextCompat.getDrawable(requireContext(), R.drawable.banner_dot_inactive)
                    }
                }
            }
        })
    }

    private fun startBannerAutoScroll(rv: RecyclerView, itemCount: Int) {
        bannerAutoScrollHandler?.removeCallbacksAndMessages(null)
        bannerAutoScrollHandler = Handler(Looper.getMainLooper())
        bannerAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (itemCount == 0) return

                val lm = rv.layoutManager as? LinearLayoutManager
                if (lm != null && lm.isSmoothScrolling()) {
                    bannerAutoScrollHandler?.postDelayed(this, BANNER_SETTLE_POLL_MS)
                    return
                }
                val focus = activity?.currentFocus
                val onBannerControl = focus != null && (
                    focus.id == R.id.homeBannerWatchBtn ||
                    focus.id == R.id.homeBannerCarousel ||
                    binding.homeBannerCarousel.findContainingViewHolder(focus) != null
                )
                if (!onBannerControl) {

                    val current = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    if (current != RecyclerView.NO_POSITION) rv.smoothScrollToPosition(current + 1)
                }
                bannerAutoScrollHandler?.postDelayed(this, 5000L)
            }
        }
        bannerAutoScrollHandler?.postDelayed(bannerAutoScrollRunnable!!, 5000L)
    }

    private fun resetHomeBannerAutoScroll() {
        val runnable = bannerAutoScrollRunnable ?: return
        bannerAutoScrollHandler?.removeCallbacksAndMessages(null)
        bannerAutoScrollHandler?.postDelayed(runnable, 5000L)
    }

    private fun applyNavBannerContentStyle(classic: Boolean) {
        val b = _binding ?: return
        val ctx = b.root.context
        val density = ctx.resources.displayMetrics.density
        val content = b.navBannerContent

        content.gravity = if (classic) Gravity.CENTER_HORIZONTAL else 0
        content.setPadding(
            ((if (classic) 16 else 12) * density).toInt(),
            0,
            ((if (classic) 16 else 12) * density).toInt(),
            ((if (classic) 14 else 12) * density).toInt()
        )

        val logoLp = b.navBannerLogo.layoutParams as LinearLayout.LayoutParams
        if (classic) {
            logoLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            logoLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            logoLp.gravity = Gravity.CENTER_HORIZONTAL
            b.navBannerLogo.scaleType = ImageView.ScaleType.FIT_CENTER
            b.navBannerLogo.maxWidth = Int.MAX_VALUE
            b.navBannerLogo.maxHeight = Int.MAX_VALUE
        } else {
            logoLp.width = (120 * density).toInt()
            logoLp.height = (32 * density).toInt()
            logoLp.gravity = Gravity.NO_GRAVITY
            b.navBannerLogo.scaleType = ImageView.ScaleType.FIT_START
            b.navBannerLogo.maxWidth = (120 * density).toInt()
            b.navBannerLogo.maxHeight = (32 * density).toInt()
        }
        b.navBannerLogo.layoutParams = logoLp

        val res = ctx.resources
        if (classic) {
            b.navBannerTitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                res.getDimension(R.dimen.banner_classic_title_text)
            )
            b.navBannerTitle.gravity = Gravity.CENTER_HORIZONTAL
            ResourcesCompat.getFont(ctx, R.font.poppins_bold)?.let { b.navBannerTitle.typeface = it }
            b.navBannerMetaRow.isVisible = false
            b.navBannerChipsRow.isVisible = true
        } else {
            b.navBannerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            b.navBannerTitle.gravity = Gravity.NO_GRAVITY
            b.navBannerTitle.typeface =
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            b.navBannerMetaRow.isVisible = true
            b.navBannerChipsRow.isVisible = false
        }

        val btn = b.navBannerWatchBtn
        val btnLp = btn.layoutParams as LinearLayout.LayoutParams
        btnLp.gravity = if (classic) Gravity.CENTER_HORIZONTAL else Gravity.NO_GRAVITY
        btn.layoutParams = btnLp
        if (classic) {
            btn.setBackgroundResource(R.drawable.banner_classic_pill_bg)
            btn.minWidth = 0
            btn.minHeight = 0
            btn.minimumHeight = res.getDimensionPixelSize(R.dimen.banner_classic_watch_height)
            btn.maxHeight = res.getDimensionPixelSize(R.dimen.banner_classic_watch_height)
            btn.setPadding(
                res.getDimensionPixelSize(R.dimen.banner_classic_watch_padding), 0,
                res.getDimensionPixelSize(R.dimen.banner_classic_watch_padding), 0
            )
            btn.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                res.getDimension(R.dimen.banner_classic_watch_text)
            )
            btn.setTextColor(
                ContextCompat.getColor(ctx, R.color.bg_white)
            )
            btn.gravity = Gravity.CENTER
            btn.setAllCaps(false)
        } else {
            btn.setBackgroundResource(R.drawable.tag_chip_bg)
            btn.gravity = Gravity.CENTER
            btn.setPadding(
                (20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), (8 * density).toInt()
            )
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            btn.setTextColor(
                ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground)
            )
        }
    }

    private fun navBannerChipsLabels(media: Media): List<String> {
        val formatText = media.format?.replace("_", " ")?.let { fmt ->
            when {
                fmt.equals("TV", true) -> "TV Series"
                fmt.equals("TV_SHORT", true) -> "TV Short"
                else -> fmt
            }
        }
        val statusText =
            media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
        val seasonText = media.anime?.season?.lowercase()
            ?.let { season ->
                media.anime?.seasonYear?.let { year -> "$season $year" } ?: season
            }
        val scoreText = media.meanScore?.let { "$it%" }
        return listOfNotNull(formatText, statusText, seasonText, scoreText) + media.genres
    }

    private fun updateNavigatingBanner(media: Media) {
        val b = _binding ?: return
        val classic = isClassicBanner()
        applyNavBannerContentStyle(classic)
        navBannerCurrentMediaId = media.id
        navBannerCurrentMedia = media
        updateHomeBannerOverlay(media)

        val front = if (navBannerSlotA) b.navBannerBgA else b.navBannerBgB
        val back = if (navBannerSlotA) b.navBannerBgB else b.navBannerBgA

        lifecycleScope.launch(Dispatchers.IO) {
            val anizipUrl = AniZip.getBackdropUrlWithTmdbFallback(media.id, media.nameRomaji)
            val bannerUrl = anizipUrl ?: media.banner ?: media.cover ?: return@launch
            withContext(Dispatchers.Main) {
                if (_binding == null || navBannerCurrentMediaId != media.id) return@withContext
                Glide.with(back.context)
                    .load(bannerUrl)
                    .override(1280, 720)
                    .error(R.drawable.ic_round_person_24)
                    .listener(object : RequestListener<Drawable> {
                        override fun onResourceReady(
                            resource: Drawable, model: Any, target: Target<Drawable>,
                            dataSource: DataSource, isFirstResource: Boolean
                        ): Boolean {
                            val portrait = resource.intrinsicHeight > resource.intrinsicWidth
                            val isLandscape = back.context.resources.configuration.orientation ==
                                Configuration.ORIENTATION_LANDSCAPE
                            val navActive = PrefManager.getVal<Int>(PrefName.HomeBannerMode) == 2
                            back.scaleType = when {
                                portrait -> ImageView.ScaleType.CENTER_CROP
                                isLandscape && navActive && !isClassicBanner() ->
                                    ImageView.ScaleType.FIT_CENTER
                                else -> ImageView.ScaleType.CENTER_CROP
                            }
                            return false
                        }
                        override fun onLoadFailed(
                            e: GlideException?, model: Any?, target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean = false
                    })
                    .into(back)
                back.alpha = 1f
                front.alpha = 0f
                navBannerSlotA = !navBannerSlotA
            }
        }

        b.navBannerTitle.text = media.userPreferredName
        b.navBannerLogo.visibility = View.GONE
        b.navBannerTitle.visibility = View.VISIBLE

        b.navBannerStatus.text = media.status?.replace("_", " ") ?: ""
        b.navBannerStatus.isVisible = media.status != null
        b.navBannerRating.text = media.meanScore?.let { "★ ${it / 10.0}" } ?: ""
        b.navBannerRating.isVisible = media.meanScore != null
        b.navBannerGenres.text = media.genres.take(2).joinToString(" • ")
        b.navBannerGenres.isVisible = media.genres.isNotEmpty()
        b.navBannerSynopsis.isVisible = false

        if (classic) {
            bindClassicChips(
                b.navBannerChipsRow,
                b.navBannerChipsTop,
                b.navBannerChipsBottom,
                navBannerChipsLabels(media)
            )
        }

        val isWatching = media.userStatus == "CURRENT"
        b.navBannerWatchBtn.text = if (isWatching)
            getString(R.string.continue_watching_short)
        else
            getString(R.string.watch_now)
        b.navBannerWatchBtn.setOnClickListener { openNavBannerMedia(media) }
        b.navBannerCard.setOnClickListener { openNavBannerMedia(media) }

        lifecycleScope.launch(Dispatchers.IO) {
            val logoUrl = if (PrefManager.getVal(PrefName.UseClearlogoInPosters)) ani.sanin.connections.LogoApi.getLogoUrl(media.id) else null
            withContext(Dispatchers.Main) {
                if (_binding == null || navBannerCurrentMediaId != media.id) return@withContext
                if (logoUrl != null) {
                    b.navBannerLogo.loadImage(logoUrl)
                    b.navBannerLogo.visibility = View.VISIBLE
                    b.navBannerTitle.visibility = View.GONE
                } else {
                    b.navBannerLogo.visibility = View.GONE
                    b.navBannerTitle.visibility = View.VISIBLE
                }
                homeBannerLogos = homeBannerLogos + (media.id to logoUrl)
                updateHomeBannerOverlay(media)
            }
        }
    }

    private fun openNavBannerMedia(media: Media) {
        val intent = Intent(requireContext(), ani.sanin.media.MediaDetailsActivity::class.java)
        intent.putExtra("media", media)
        intent.putExtra("anime", true)
        startActivity(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            applyHomeBannerLandscapeMode()
            applyHomeBannerFocusChain()

            bannerCarouselAdapter?.notifyDataSetChanged()
        }
    }

    private fun setupHomeBannerWatchBtn() {
        val btn = binding.homeBannerWatchBtn
        btn.setOnClickListener { currentHomeBannerMedia()?.let { openHomeBannerMedia(it) } }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    currentHomeBannerMedia()?.let { openHomeBannerMedia(it) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveHomeBannerCarousel(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    true
                }
                else -> false
            }
        }
        FocusEffectUtil.applyFocusListener(btn)
        btn.nextFocusUpId = R.id.mainCalendarContainer
        btn.nextFocusDownId = R.id.homeWatchingRecyclerView
    }

    private fun currentHomeBannerMedia(): Media? {
        if (homeBannerItems.isEmpty()) return null
        val lm = binding.homeBannerCarousel.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos < 0) return null
        return homeBannerItems[pos % homeBannerItems.size]
    }

    private fun openHomeBannerMedia(media: Media) {
        val intent = Intent(requireContext(), ani.sanin.media.MediaDetailsActivity::class.java)
        intent.putExtra("media", media)
        intent.putExtra("anime", true)
        startActivity(intent)
    }

    private fun moveHomeBannerCarousel(forward: Boolean) {
        val rv = binding.homeBannerCarousel
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        rv.smoothScrollToPosition(pos + (if (forward) 1 else -1))
    }

    private fun applyHomeBannerFocusChain() {
        val b = _binding ?: return
        val isLandscape =
            b.root.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bannerMode: Int = PrefManager.getVal(PrefName.HomeBannerMode)
        val carouselActive = bannerMode == 0
        val navActive = bannerMode == 2
        val hasBanner = carouselActive || navActive
        val cal = activity?.findViewById<View>(R.id.mainCalendarContainer)
        val avatar = activity?.findViewById<View>(R.id.mainUserAvatarContainer)
        val cwRow = if (b.homeContinueWatchingContainer.childCount > 0)
            b.homeContinueWatchingContainer.getChildAt(0) else null
        val watchBtn = b.homeBannerWatchBtn
        when {
            !isLandscape || !hasBanner -> {
                cwItemUpTarget = R.id.homeBannerCarousel
                cwRow?.nextFocusUpId = R.id.homeBannerCarousel
                cal?.nextFocusDownId = R.id.homeBannerCarousel
                avatar?.nextFocusDownId = R.id.homeBannerCarousel
                watchBtn.isVisible = false
            }
            navActive -> {
                cwItemUpTarget = R.id.mainCalendarContainer
                cwRow?.nextFocusUpId = R.id.mainCalendarContainer
                cal?.nextFocusDownId = R.id.homeBannerWatchBtn
                avatar?.nextFocusDownId = R.id.homeBannerWatchBtn
                watchBtn.isVisible = true
            }
            else -> {

                val target =
                    if (isModernBanner() || isClassicBanner()) R.id.homeBannerCarousel
                    else R.id.homeBannerWatchBtn
                cwItemUpTarget = target
                cwRow?.nextFocusUpId = target
                cal?.nextFocusDownId = target
                avatar?.nextFocusDownId = target
                watchBtn.isVisible = target == R.id.homeBannerWatchBtn
            }
        }

        for (i in 0 until b.homeWatchingRecyclerView.childCount) {
            b.homeWatchingRecyclerView.getChildAt(i).nextFocusUpId = cwItemUpTarget
        }
    }

    private fun applyHomeBannerLandscapeMode() {
        val b = _binding ?: return
        val ctx = b.root.context
        val isLandscape =
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bannerMode: Int = PrefManager.getVal(PrefName.HomeBannerMode)
        val carouselActive = bannerMode == 0
        val navActive = bannerMode == 2
        val hasBanner = carouselActive || navActive
        val card = if (carouselActive) b.homeBannerCard else b.navBannerCard
        val fade = b.homeLeftFade
        val overlay = b.homeBannerOverlay

        val setCardCentered = { c: androidx.cardview.widget.CardView ->
            val lp = c.layoutParams as ConstraintLayout.LayoutParams
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            c.layoutParams = lp
        }

        if (isModernBanner() && hasBanner) {
            card.sizeBannerCard()
            setCardCentered(card)
            fade.isVisible = false
            fade.translationX = 0f
            overlay.isVisible = false
            overlay.translationX = 0f
            if (navActive) {
                b.navBannerContent.isVisible = false
                b.navBannerBottomGradient.isVisible = false
                b.navBannerScrim.isVisible = false
                b.navBannerCard.isFocusable = true
                b.navBannerCard.nextFocusDownId = R.id.homeContinueWatchRow
                navBannerCurrentMedia?.let { updateHomeBannerOverlay(it) }
            } else {
                b.navBannerContent.isVisible = true
                b.navBannerBottomGradient.isVisible = true
                b.navBannerScrim.isVisible = false
                b.navBannerCard.isFocusable = false
                bannerCarouselAdapter?.setLandscapeMode(false, 0)
            }
            return
        }

        if (!isLandscape || !hasBanner) {
            b.homeBannerCard.sizeBannerCard()
            b.navBannerCard.sizeBannerCard()
            setCardCentered(b.homeBannerCard)
            setCardCentered(b.navBannerCard)
            b.navBannerContent.isVisible = true
            applyNavBannerContentStyle(isClassicBanner())
            b.navBannerBottomGradient.isVisible = true
            b.navBannerScrim.isVisible = false
            b.navBannerBgA.scaleType = ImageView.ScaleType.CENTER_CROP
            b.navBannerBgB.scaleType = ImageView.ScaleType.CENTER_CROP
            b.navBannerCard.isFocusable = false
            fade.isVisible = false
            overlay.isVisible = false
            overlay.translationX = 0f
            bannerCarouselAdapter?.setLandscapeMode(false, 0)
            return
        }

        card.sizeBannerCard(0.65f)
        val (cardW, cardH) = card.bannerCardSizePx(0.65f)
        val lp = card.layoutParams as ConstraintLayout.LayoutParams
        lp.startToStart = ConstraintSet.UNSET
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        card.layoutParams = lp

        val density = ctx.resources.displayMetrics.density
        val sidePad = (24 * density).toInt()
        val stripW = ctx.resources.displayMetrics.widthPixels - cardW

        val fullBleed = isModernBanner() || isClassicBanner()

        fade.isVisible = !fullBleed
        fade.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = stripW
            height = cardH
        }
        fade.bringToFront()
        overlay.bringToFront()

        overlay.isVisible = !fullBleed
        if (!fullBleed) {
            overlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = stripW + cardW / 4
                height = cardH
            }
            overlay.setPadding(sidePad, 0, sidePad, 0)
            b.homeBannerOverlayLogo.maxWidth = (stripW - sidePad * 2).coerceAtLeast(1)
            b.homeBannerOverlayLogo.maxHeight = (cardH * 0.30f).toInt()
            b.homeBannerOverlaySynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = (stripW - sidePad * 2 + cardW / 4).coerceAtLeast(1)
            }
        }

        if (navActive) {
            val largeBanner = isClassicBanner()
            applyNavBannerContentStyle(largeBanner)
            b.navBannerContent.isVisible = largeBanner

            b.navBannerBottomGradient.isVisible = largeBanner
            b.navBannerScrim.isVisible = !largeBanner
            b.navBannerScrim.layoutParams = b.navBannerScrim.layoutParams.apply {
                width = cardW / 2
            }
            val scale = if (largeBanner) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
            b.navBannerBgA.scaleType = scale
            b.navBannerBgB.scaleType = scale
            b.navBannerCard.isFocusable = true
            b.navBannerCard.nextFocusDownId = R.id.homeContinueWatchRow
            navBannerCurrentMedia?.let { updateHomeBannerOverlay(it) }
        } else {
            b.navBannerContent.isVisible = true
            b.navBannerBottomGradient.isVisible = true
            b.navBannerBgA.scaleType = ImageView.ScaleType.CENTER_CROP
            b.navBannerBgB.scaleType = ImageView.ScaleType.CENTER_CROP
            b.navBannerCard.isFocusable = false
            bannerCarouselAdapter?.setLandscapeMode(true, cardW)
            updateHomeBannerOverlayForCurrent()
        }
    }

    private fun updateHomeBannerOverlayForCurrent() {
        val b = _binding ?: return
        if (homeBannerItems.isEmpty()) return
        val lm = b.homeBannerCarousel.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos < 0) return
        updateHomeBannerOverlay(homeBannerItems[pos % homeBannerItems.size])
    }

    private fun updateHomeBannerOverlay(media: Media) {
        val b = _binding ?: return

        if (!b.homeBannerOverlay.isVisible) return
        val logo = b.homeBannerOverlayLogo
        val title = b.homeBannerOverlayTitle
        val chips = b.homeBannerOverlayChips
        val genres = b.homeBannerOverlayGenres
        val synopsis = b.homeBannerOverlaySynopsis

        val logoUrl = homeBannerLogos[media.id]
        if (!logoUrl.isNullOrBlank()) {
            logo.isVisible = true
            title.isVisible = false
            logo.loadImage(logoUrl)
        } else {
            logo.isVisible = false
            logo.setImageDrawable(null)
            title.isVisible = true
            title.text = media.userPreferredName ?: media.name
        }

        chips.removeAllViews()
        addHomeOverlayChip(chips, homeOverlayFormatText(media))
        addHomeOverlayChip(chips, homeOverlayStatusText(media))
        addHomeOverlayChip(chips, homeOverlaySeasonText(media))
        addHomeOverlayChip(chips, homeOverlayScoreText(media))

        genres.removeAllViews()
        for (g in media.genres.take(4)) addHomeOverlayChip(genres, g)

        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        if (isClassicBanner()) {
            synopsis.isVisible = false
        } else if (!desc.isNullOrBlank()) {
            synopsis.text = desc
            synopsis.isVisible = true
        } else {
            synopsis.isVisible = false
        }
    }

    private fun addHomeOverlayChip(container: LinearLayout, text: String?) {
        if (text.isNullOrBlank()) return
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val chip = TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
            textSize = 12f
            setBackgroundResource(R.drawable.tag_chip_bg)
            setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt()
            )
            maxLines = 1
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (6 * density).toInt()
        container.addView(chip, lp)
    }

    private fun homeOverlayFormatText(media: Media): String? =
        media.format?.replace("_", " ")?.let { fmt ->
            when {
                fmt.equals("TV", true) -> "TV Series"
                fmt.equals("TV_SHORT", true) -> "TV Short"
                else -> fmt
            }
        }

    private fun homeOverlayStatusText(media: Media): String? =
        media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }

    private fun homeOverlaySeasonText(media: Media): String? {
        val season = media.anime?.season?.lowercase()
        val year = media.anime?.seasonYear
        return if (season != null && year != null) "$season $year" else null
    }

    private fun homeOverlayScoreText(media: Media): String? =
        media.meanScore?.let { "$it%" }

    private fun applyCatalogueMode() {
        binding.homeRefresh.visibility = View.GONE
        binding.homeCatalogueRefresh.visibility = View.VISIBLE
    }

    private fun loadCatalogue() {
        val sourceId = AnimeCatalogueHome.savedSourceId()
        Logger.log("HomeCatalogue: loading source '$sourceId'")
        catalogueJob?.cancel()
        binding.homeCatalogueRefresh.isRefreshing = true
        catalogueJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    try {
                        AnimeCatalogueHome.loadRows(sourceId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("HomeCatalogue: load threw for '$sourceId'")
                        Logger.log(e)
                        emptyList()
                    }
                }
                if (_binding == null) return@launch
                Logger.log(
                    "HomeCatalogue: '$sourceId' produced ${rows.size} rows " +
                        "(${rows.sumOf { it.items.size }} items)"
                )
                renderCatalogueRows(sourceId, rows)
            } finally {
                if (_binding != null) binding.homeCatalogueRefresh.isRefreshing = false
            }
        }
    }

    private fun renderCatalogueRows(sourceId: String, rows: List<CatalogueRow>) {
        if (_binding == null) return
        val ctx = requireContext()
        binding.homeCatalogueSections.removeAllViews()
        if (rows.isEmpty()) {
            val label = when {
                sourceId.startsWith("native:", true) -> sourceId.substringAfter(':')
                sourceId.startsWith("ext:", true) -> sourceId.substringAfter(':')
                else -> sourceId
            }
            Logger.log("HomeCatalogue: no rows for '$sourceId' (label '$label')")
            binding.homeCatalogueSections.addView(TextView(ctx).apply {
                text = "Couldn't load the catalogue from $label.\n" +
                    "Check that the source works and your connection, then pull to refresh."
                setPadding(48, 48, 48, 48)
                textSize = 15f
                alpha = 0.7f
                setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            })
            return
        }
        rows.forEach { row ->
            val mediaToItem = LinkedHashMap<Int, CatalogueItem>()
            val medias = ArrayList<Media>()
            row.items.forEach { item ->
                val display = AnimeCatalogueHome.displayMedia(sourceId, item)
                mediaToItem[display.id] = item
                medias.add(display)
            }
            val gridFallback = row.title.isBlank()
            val header = if (gridFallback) null else TextView(ctx).apply {
                text = row.title
                setPadding(24, 14, 24, 8)
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
            val recycler = RecyclerView(ctx).apply {
                layoutManager = if (gridFallback) GridLayoutManager(ctx, 3) else
                    LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
                adapter = MediaAdaptor(
                    0,
                    medias,
                    requireActivity(),
                    onItemClickOverride = { media ->
                        val item = mediaToItem[media.id]
                        val act = activity as? AppCompatActivity
                        if (item != null && act != null) {
                            AnimeCatalogueHome.openItem(
                                act,
                                sourceId,
                                item,
                                onFailed = { snackString(getString(R.string.no_results_found)) },
                            ) { resolved ->
                                ContextCompat.startActivity(
                                    ctx,
                                    Intent(ctx, ani.sanin.media.MediaDetailsActivity::class.java)
                                        .putExtra("media", resolved)
                                        .putExtra("anime", true),
                                    null
                                )
                            }
                            true
                        } else false
                    }
                )
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(24, 0, 24, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 11f.px }
            }
            header?.let(binding.homeCatalogueSections::addView)
            binding.homeCatalogueSections.addView(recycler)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        val wasCatalogue = catalogueMode
        catalogueMode = AnimeCatalogueHome.isCatalogueSource(AnimeCatalogueHome.savedSourceId())
        if (catalogueMode != wasCatalogue) {
            Logger.log("HomeFragment: catalogue mode changed to $catalogueMode")
            if (catalogueMode) {
                applyCatalogueMode()
                loadCatalogue()
            } else {
                binding.homeCatalogueRefresh.visibility = View.GONE
                binding.homeRefresh.visibility = View.VISIBLE
                if (!model.loaded) Refresh.activity[1]!!.postValue(true)
            }
            return
        }
        if (catalogueMode) {
            applyCatalogueMode()
            if (binding.homeCatalogueSections.childCount == 0 && catalogueJob?.isActive != true) {
                loadCatalogue()
            }
            return
        }
        if (!model.loaded) Refresh.activity[1]!!.postValue(true)
        reapplyHomeLayout()
    }

    private fun reapplyHomeLayout() {
        if (_binding == null || catalogueMode || PrefManager.getVal(PrefName.RescueMode)) return
        val homeLayoutShow = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
        val order = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
        if (order.isEmpty()) return
        val containers = homeSections().map { it.container }
        containers.forEachIndexed { i, c ->
            c.visibility = if (homeLayoutShow.getOrElse(i) { true }) View.VISIBLE else View.GONE
        }
        var insertIndex = binding.homeContainer.indexOfChild(binding.homeHiddenItemsContainer) + 1
        order.forEach { i ->
            val container = containers.getOrNull(i)
            if (container != null) {
                binding.homeContainer.removeView(container)
                binding.homeContainer.addView(container, insertIndex)
                insertIndex++
            }
        }
        setupSectionFocusChain()
    }

    override fun onPause() {
        super.onPause()
        bannerAutoScrollHandler?.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        catalogueJob?.cancel()
        bannerAutoScrollHandler?.removeCallbacksAndMessages(null)
        bannerSnapHelper.attachToRecyclerView(null)
        _binding = null
        super.onDestroyView()
    }
}
