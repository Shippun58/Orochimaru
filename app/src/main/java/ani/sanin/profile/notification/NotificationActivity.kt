package ani.sanin.profile.notification

import android.content.res.TypedArray
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ani.sanin.FadingEdgeRecyclerView
import ani.sanin.R
import ani.sanin.getThemeColor
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.api.Notification
import ani.sanin.databinding.ActivityNotificationBinding
import ani.sanin.initActivity
import ani.sanin.media.MediaDetailsActivity
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.notifications.comment.CommentStore
import ani.sanin.notifications.subscription.SubscriptionStore
import ani.sanin.profile.ProfileActivity
import ani.sanin.profile.activity.FeedActivity
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.ui.components.TomoeView
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class NotificationClickType { USER, MEDIA, ACTIVITY, COMMENT, TMDB_MEDIA, UNDEFINED }
enum class TabType { USER, MEDIA, SUBSCRIPTION, COMMENT, ONE }

class NotificationActivity : AppCompatActivity() {
    lateinit var binding: ActivityNotificationBinding
    private var selected = 0
    private val CommentsEnabled = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1
    private var userCount = 0
    private var mediaCount = 0
    private var subsCount = 0
    private var commentCount = 0
    private var getOne = -1
    private var isMovieMode = false

    private lateinit var visibleTabTypes: List<TabType>

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabAdapter = GroupieAdapter()
    private lateinit var tabRecycler: FadingEdgeRecyclerView
    private lateinit var tabSwipeRefresh: SwipeRefreshLayout
    private lateinit var tabProgress: FrameLayout
    private lateinit var tabEmpty: TextView

    private class TabState {
        val items = mutableListOf<Notification>()
        var currentPage = 1
        var hasNextPage = false
        var loaded = false
        var loading = false
    }
    private val tabStates = Array(4) { TabState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.notificationTitle.text = getString(R.string.notifications)
        binding.notificationToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        FocusEffectUtil.applyFocusListener(binding.notificationBack)

        setupContent()

        isMovieMode = PrefManager.getVal<String>(PrefName.ContentMode) == "movie_tv"

        setupTabs()

        getOne = intent.getIntExtra("activityId", -1)
        if (getOne != -1) binding.notificationTabLayout.visibility = View.GONE

        updateCounts()
        binding.notificationBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupTabs() {
        val tabs: List<Pair<String, TabType>> = if (isMovieMode) {

            listOf(getString(R.string.subscriptions) to TabType.SUBSCRIPTION)
        } else {
            buildList {
                add(getString(R.string.anilist) to TabType.USER)
                add(getString(R.string.media) to TabType.MEDIA)
                add(getString(R.string.subscriptions) to TabType.SUBSCRIPTION)
                if (CommentsEnabled) add(getString(R.string.comments_activity) to TabType.COMMENT)
            }
        }
        visibleTabTypes = tabs.map { it.second }
        tabs.forEach { (label, _) ->
            binding.notificationTabLayout.addTab(binding.notificationTabLayout.newTab().setText(label))
        }
        binding.notificationTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selected = tab.position
                selectTab(selected)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                selectTab(tab.position)
            }
        })
        if (isMovieMode) {
            binding.notificationTabLayout.tabGravity = TabLayout.GRAVITY_CENTER
        }

        val startTab = if (isMovieMode) 0
        else visibleTabTypes.indexOf(TabType.MEDIA).coerceAtLeast(0)
        binding.notificationTabLayout.getTabAt(startTab)?.select()
    }

    private fun tomoe(sizeDp: Float, speed: Float, rot: Float, grav: Int): TomoeView {
        val s = (sizeDp * resources.displayMetrics.density).toInt()
        return TomoeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(s, s).apply { this.gravity = grav }
            setAnimation(R.raw.tomoe)
            this.speed = speed
            repeatCount = Int.MAX_VALUE
            playAnimation()
            rotation = rot
        }
    }

    private fun setupContent() {
        val dp = resources.displayMetrics.density

        tabSwipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isEnabled = false
            setColorSchemeColors(getThemeColor(android.R.attr.colorPrimary))
            setOnRefreshListener { refreshCurrentTab() }
        }

        tabRecycler = FadingEdgeRecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            adapter = tabAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = true
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (shouldLoadMore()) loadMore()
                }
            })
        }
        tabSwipeRefresh.addView(tabRecycler)

        tabEmpty = TextView(this).apply {
            text = getString(R.string.nothing_here)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            val ta = theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnBackground))
            setTextColor(ta.getColor(0, Color.WHITE))
            ta.recycle()
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            visibility = View.GONE
        }

        val refreshBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (48 * dp).toInt(), (48 * dp).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (8 * dp).toInt()
                rightMargin = (8 * dp).toInt()
            }
            setImageResource(R.drawable.ic_round_refresh_24)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            isFocusable = true
            setOnClickListener { refreshCurrentTab() }
            FocusEffectUtil.applyFocusListener(this)
        }

        tabProgress = FrameLayout(this).apply {
            val sz = (76.5f * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply { gravity = Gravity.CENTER }
            addView(tomoe(72f, 1.75f, 0f, Gravity.CENTER))
            visibility = View.GONE
        }

        FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(tabProgress)
            addView(tabEmpty)
            addView(tabSwipeRefresh)
            addView(refreshBtn)
            binding.notificationContent.addView(this)
        }
    }

    private fun selectTab(idx: Int) {
        selected = idx
        val state = tabStates[idx]
        if (getOne != -1 || !state.loaded) {
            loadTab(idx, force = true)
        } else {
            showTabContent(idx)
        }
    }

    private fun showTabContent(idx: Int) {
        val state = tabStates[idx]
        tabAdapter.clear()
        tabAdapter.addAll(state.items.map { n ->
            newNotificationItem(n, visibleTabTypes[idx])
        })
        tabEmpty.visibility = if (tabAdapter.itemCount == 0) View.VISIBLE else View.GONE
        tabProgress.visibility = View.GONE
        tabSwipeRefresh.isEnabled = true

        if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
            binding.notificationContent.alpha = 0f
            binding.notificationContent.scaleX = 0.92f
            binding.notificationContent.scaleY = 0.92f
            binding.notificationContent.animate().cancel()
            binding.notificationContent.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            binding.notificationContent.alpha = 1f
            binding.notificationContent.scaleX = 1f
            binding.notificationContent.scaleY = 1f
        }

        tabRecycler.post { tabRecycler.requestFocus() }
    }

    private fun newNotificationItem(n: Notification, tab: TabType): com.xwray.groupie.Item<*> {
        val isEpisode = tab == TabType.SUBSCRIPTION ||
            (tab == TabType.MEDIA &&
                (n.notificationType == "AIRING" || n.notificationType == "SUBSCRIPTION"))
        Logger.log("NotifyItem tab=$tab type=${n.notificationType} isEpisode=$isEpisode mediaId=${n.mediaId} ep=${n.episode} epTitle=${n.episodeTitle}")
        return if (isEpisode) {
            EpisodeNotificationItem(n, tab, tabAdapter, ::onNotificationClick)
        } else {
            NotificationItem(n, tab, tabAdapter, ::onNotificationClick)
        }
    }

    private fun loadTab(idx: Int, force: Boolean = false) {
        val state = tabStates[idx]
        if (state.loading && !force) return
        state.loading = true
        if (force) { state.items.clear(); state.currentPage = 1 }
        tabProgress.visibility = View.VISIBLE
        tabEmpty.visibility = View.GONE
        tabSwipeRefresh.isEnabled = false

        ioScope.launch {
            try {
                val list = fetchNotificationsForTab(idx, state.currentPage)
                launch(Dispatchers.Main) {
                    state.items.addAll(list)
                    if (list.isNotEmpty()) state.currentPage++
                    state.hasNextPage = list.size >= 25
                    state.loaded = true; state.loading = false
                    if (idx == selected) showTabContent(idx)
                    resetCountIfNeeded(idx)
                }
            } catch (_: Exception) {
                launch(Dispatchers.Main) {
                    state.loading = false
                    if (idx == selected) {
                        tabProgress.visibility = View.GONE
                        tabEmpty.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private suspend fun fetchNotificationsForTab(idx: Int, page: Int): List<Notification> {
        val uid = Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull() ?: 0
        return when (visibleTabTypes[idx]) {
            TabType.USER -> Anilist.query.getNotifications(uid, page, true, null)
                ?.data?.page?.notifications?.filter {
                    it.media == null && it.notificationType != "RELATED_MEDIA_ADDITION"
                } ?: listOf()
            TabType.MEDIA -> Anilist.query.getNotifications(uid, page, true, true)
                ?.data?.page?.notifications?.filter {
                    it.media != null || it.notificationType == "MEDIA_DELETION"
                } ?: listOf()
            TabType.SUBSCRIPTION -> {
                val isMovieMode = PrefManager.getVal<String>(PrefName.ContentMode) == "movie_tv"
                val list = PrefManager.getNullableVal<List<SubscriptionStore>>(
                    PrefName.SubscriptionNotificationStore, null
                ) ?: listOf()
                list.sortedByDescending { (it.time / 1000L).toInt() }
                    .filter { it.image != null }

                    .filter { if (isMovieMode) it.tmdbType != null else it.tmdbType == null }
                    .map { Notification(it.type, System.currentTimeMillis().toInt(),
                        commentId = it.mediaId, mediaId = it.mediaId,
                        notificationType = it.type,
                        context = it.title + ": " + it.content,
                        createdAt = (it.time / 1000L).toInt(),
                        image = it.image, banner = it.banner ?: it.image,
                        tmdbType = it.tmdbType,
                        episode = it.episodeNumber,
                        episodeTitle = it.episodeTitle,
                        thumbnail = it.thumbnail,
                        durationMinutes = it.durationMinutes,
                        airDate = it.airDate,
                        airTimeMillis = it.time) }
            }
            TabType.COMMENT -> {
                val list = PrefManager.getNullableVal<List<CommentStore>>(
                    PrefName.CommentNotificationStore, null
                ) ?: listOf()
                list.sortedByDescending { (it.time / 1000L).toInt() }
                    .map { Notification(it.type.toString(), System.currentTimeMillis().toInt(),
                        commentId = it.commentId, notificationType = it.type.toString(),
                        mediaId = it.mediaId,
                        context = it.title + "\n" + it.content,
                        createdAt = (it.time / 1000L).toInt()) }
            }
            TabType.ONE -> Anilist.query.getNotifications(uid, 1, false, null)
                ?.data?.page?.notifications?.filter { it.id == getOne } ?: listOf()
        }
    }

    private fun refreshCurrentTab() {
        val state = tabStates[selected]
        state.loaded = false; state.items.clear(); state.currentPage = 1
        loadTab(selected, force = true)
        tabSwipeRefresh.isRefreshing = false
    }

    private fun loadMore() {
        val state = tabStates[selected]
        if (!state.hasNextPage || state.loading) return
        state.loading = true
        ioScope.launch {
            try {
                val list = fetchNotificationsForTab(selected, state.currentPage)
                launch(Dispatchers.Main) {
                    state.items.addAll(list)
                    if (list.isNotEmpty()) state.currentPage++
                    state.hasNextPage = list.size >= 25
                    state.loading = false
                    if (list.isNotEmpty()) {
                        tabAdapter.addAll(list.map { n ->
                            newNotificationItem(n, visibleTabTypes[selected])
                        })
                    }
                }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { state.loading = false }
            }
        }
    }

    private fun shouldLoadMore(): Boolean {
        val lm = tabRecycler.layoutManager as? LinearLayoutManager ?: return false
        val lastVisible = lm.findLastVisibleItemPosition()
        val state = tabStates[selected]
        return state.hasNextPage && !state.loading && tabAdapter.itemCount > 0 &&
                lastVisible >= tabAdapter.itemCount - 1 && !tabRecycler.canScrollVertically(1)
    }

    private fun onNotificationClick(id: Int, optional: Int?, type: NotificationClickType) {
        val intent = when (type) {
            NotificationClickType.USER -> Intent(this, ProfileActivity::class.java).apply { putExtra("userId", id) }
            NotificationClickType.MEDIA -> Intent(this, MediaDetailsActivity::class.java).apply { putExtra("mediaId", id) }
            NotificationClickType.ACTIVITY -> Intent(this, FeedActivity::class.java).apply { putExtra("activityId", id) }
            NotificationClickType.COMMENT -> Intent(this, MediaDetailsActivity::class.java).apply {
                putExtra("FRAGMENT_TO_LOAD", "COMMENTS")
                putExtra("mediaId", id)
                putExtra("commentId", optional ?: -1)
            }
            NotificationClickType.TMDB_MEDIA -> Intent(this, TmdbDetailsActivity::class.java).apply {
                putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, optional?.let { if (it == 1) "tv" else "movie" } ?: "tv")
                putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
            }
            NotificationClickType.UNDEFINED -> null
        }
        intent?.let { ContextCompat.startActivity(this, it, null) }
    }

    private fun resetCountIfNeeded(idx: Int) {
        if (getOne != -1) return
        when (visibleTabTypes[idx]) {
            TabType.USER -> { userCount = 0; PrefManager.setVal(PrefName.UnreadUserNotifications, 0) }
            TabType.MEDIA -> { mediaCount = 0; PrefManager.setVal(PrefName.UnreadMediaNotifications, 0) }
            TabType.SUBSCRIPTION -> { subsCount = 0; PrefManager.setVal(PrefName.UnreadSubscriptionNotifications, 0) }
            TabType.COMMENT -> if (CommentsEnabled) { commentCount = 0; PrefManager.setVal(PrefName.UnreadCommentNotifications, 0) }
            TabType.ONE -> {}
        }
        saveCounts()
    }

    private fun updateCounts() {
        userCount = PrefManager.getVal(PrefName.UnreadUserNotifications, 0)
        mediaCount = PrefManager.getVal(PrefName.UnreadMediaNotifications, 0)
        subsCount = PrefManager.getVal(PrefName.UnreadSubscriptionNotifications, 0)
        commentCount = PrefManager.getVal(PrefName.UnreadCommentNotifications, 0)
    }

    private fun saveCounts() {
        PrefManager.setVal(PrefName.UnreadUserNotifications, userCount)
        PrefManager.setVal(PrefName.UnreadMediaNotifications, mediaCount)
        PrefManager.setVal(PrefName.UnreadSubscriptionNotifications, subsCount)
        PrefManager.setVal(PrefName.UnreadCommentNotifications, commentCount)
        Anilist.unreadNotificationCount = subsCount + commentCount
    }

    override fun onResume() {
        super.onResume()
        updateCounts()
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }
}
