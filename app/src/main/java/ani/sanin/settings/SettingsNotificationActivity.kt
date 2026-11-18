package ani.sanin.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.api.NotificationType
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.media.Media
import ani.sanin.navBarHeight
import ani.sanin.notifications.TaskScheduler
import ani.sanin.notifications.push.PushSync
import ani.sanin.notifications.subscription.NotificationPopupActivity
import ani.sanin.notifications.subscription.SubscriptionHelper
import ani.sanin.notifications.subscription.SubscriptionNotificationWorker
import ani.sanin.notifications.subscription.TmdbSubscriptionHelper
import ani.sanin.notifications.anilist.AnilistNotificationWorker
import ani.sanin.notifications.comment.CommentNotificationWorker
import ani.sanin.openSettings
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsNotificationActivity : AppCompatActivity() {
    private var pendingAlarmReschedule = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        val binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = getString(R.string.notifications)
        binding.subscreenSubtitle.text = "Alerts, subscriptions, push & popups"
        binding.subscreenIcon.setImageResource(R.drawable.ic_round_notifications_active_24)

        Simkl.getSavedToken()
        val notificationsEnabled = PrefManager.getVal<Boolean>(PrefName.NotificationsEnabled)
        val simklLoggedIn = Simkl.token != null

        val timeNames = SubscriptionNotificationWorker.checkIntervals.map {
            val mins = it % 60
            val hours = it / 60
            if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
            else getString(R.string.do_not_update)
        }.toTypedArray()

        val aTimeNames = AnilistNotificationWorker.checkIntervals.map { it.toInt() }
        val aItems = aTimeNames.map {
            val mins = it % 60
            val hours = it / 60
            if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
            else getString(R.string.do_not_update)
        }

        val cTimeNames = CommentNotificationWorker.checkIntervals.map { it.toInt() }
        val cItems = cTimeNames.map {
            val mins = it % 60
            val hours = it / 60
            if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
            else getString(R.string.do_not_update)
        }

        fun buildSections(): List<SubscreenBuilder.Section> = listOf(
            SubscreenBuilder.Section(
                "General", R.drawable.ic_round_notifications_active_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Notifications",
                        desc = "Master switch for every alert, popup and background check",
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        switch = notificationsEnabled to { enabled: Boolean ->
                            PrefManager.setVal(PrefName.NotificationsEnabled, enabled)
                            TaskScheduler.refresh(context)
                            PushSync.sync(context)
                            toast(if (enabled) "Notifications enabled" else "Notifications disabled")
                            recreate()
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Subscriptions", R.drawable.ic_round_notifications_none_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.subscriptions_checking_time),
                        desc = timeNames[PrefManager.getVal<Int>(PrefName.SubscriptionNotificationInterval)],
                        iconRes = R.drawable.ic_round_notifications_none_24,
                        choice = SubscreenBuilder.Choice(
                            title = getString(R.string.subscriptions_checking_time),
                            options = timeNames,
                            currentIndex = PrefManager.getVal<Int>(PrefName.SubscriptionNotificationInterval),
                        ) { i ->
                            PrefManager.setVal(PrefName.SubscriptionNotificationInterval, i)
                            TaskScheduler.refresh(context)
                        },
                        onLongClick = {
                            TaskScheduler.refresh(context)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.view_subscriptions),
                        desc = "View and edit anime and manga subscriptions",
                        iconRes = R.drawable.ic_round_search_24,
                        onClick = {
                            SubscriptionsBottomDialog.newInstance(SubscriptionHelper.getSubscriptions())
                                .show(supportFragmentManager, "subscriptions")
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "View TMDB subscriptions",
                        desc = "View and remove TV show subscriptions",
                        iconRes = R.drawable.ic_round_search_24,
                        onClick = {
                            val subs = TmdbSubscriptionHelper.getSubscriptions().values.toList()
                            if (subs.isEmpty()) {
                                toast("No TMDB subscriptions")
                            } else {
                                val titles = subs.map { it.name }.toTypedArray()
                                val selected = BooleanArray(titles.size)
                                context.customAlertDialog().apply {
                                    setTitle("TMDB subscriptions")
                                    multiChoiceItems(titles, selected) {}
                                    setPosButton("Remove selected") {
                                        var removed = 0
                                        selected.forEachIndexed { index, checked ->
                                            if (checked) {
                                                TmdbSubscriptionHelper.delete(subs[index].id)
                                                removed++
                                            }
                                        }
                                        toast("Removed $removed subscriptions")
                                    }
                                    setNegButton(R.string.cancel)
                                    show()
                                }
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.import_anilist_lists_to_subscriptions),
                        desc = getString(R.string.import_anilist_lists_to_subscriptions_desc),
                        iconRes = R.drawable.ic_round_playlist_add_24,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val userId = (Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull())
                                    if (userId == null) { withContext(Dispatchers.Main) { toast(getString(R.string.login_to_anilist_first)) }; return@launch }
                                    val animeLists = Anilist.query.getMediaLists(true, userId)
                                    val mangaLists = Anilist.query.getMediaLists(false, userId)
                                    val selectableLists = linkedMapOf<String, ArrayList<Media>>()
                                    fun addSelectableLists(prefix: String, lists: MutableMap<String, ArrayList<Media>>) {
                                        lists.forEach { (name, media) ->
                                            if (name != "All" && name != "Favourites" && media.isNotEmpty())
                                                selectableLists["$prefix \u2022 $name"] = media
                                        }
                                    }
                                    addSelectableLists(getString(R.string.anime), animeLists)
                                    addSelectableLists(getString(R.string.manga), mangaLists)
                                    if (selectableLists.isEmpty()) { withContext(Dispatchers.Main) { toast(getString(R.string.no_lists_available_to_import)) }; return@launch }
                                    val titles = selectableLists.keys.toTypedArray()
                                    val selected = BooleanArray(titles.size) { true }
                                    withContext(Dispatchers.Main) {
                                        context.customAlertDialog().apply {
                                            setTitle(R.string.select_lists_to_import)
                                            multiChoiceItems(titles, selected) {}
                                            setPosButton(R.string.import_action) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                                    var count = 0
                                                    titles.forEachIndexed { index, listTitle ->
                                                        if (selected[index]) {
                                                            val media = selectableLists[listTitle] ?: return@forEachIndexed
                                                            media.forEach { m ->
                                                                if (!existingIds.contains(m.id)) { existingIds.add(m.id); count++; SubscriptionHelper.saveSubscription(m, true) }
                                                            }
                                                        }
                                                    }
                                                    withContext(Dispatchers.Main) { toast(getString(R.string.imported_subscriptions_count, count)) }
                                                }
                                            }
                                            setNegButton(R.string.cancel)
                                            show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { toast(e.message ?: "Import failed") }
                                }
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.import_anilist_statuses_to_subscriptions),
                        desc = getString(R.string.import_anilist_statuses_to_subscriptions_desc),
                        iconRes = R.drawable.ic_round_playlist_add_24,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val userId = (Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull())
                                    if (userId == null) { withContext(Dispatchers.Main) { toast(getString(R.string.login_to_anilist_first)) }; return@launch }
                                    val animeLists = Anilist.query.getMediaLists(true, userId)
                                    val mangaLists = Anilist.query.getMediaLists(false, userId)
                                    val animeAll = animeLists["All"] ?: arrayListOf()
                                    val mangaAll = mangaLists["All"] ?: arrayListOf()
                                    if (animeAll.isEmpty() && mangaAll.isEmpty()) { withContext(Dispatchers.Main) { toast(getString(R.string.no_lists_available_to_import)) }; return@launch }
                                    val statusKeys = resources.getStringArray(R.array.status)
                                    val animeStatuses = resources.getStringArray(R.array.status_anime)
                                    val mangaStatuses = resources.getStringArray(R.array.status_manga)
                                    val count = minOf(statusKeys.size, animeStatuses.size, mangaStatuses.size)
                                    val titles = ArrayList<String>(count * 2)
                                    val statusMeta = ArrayList<Pair<Boolean, String>>(count * 2)
                                    repeat(count) { index ->
                                        titles.add("${getString(R.string.anime)} \u2022 ${animeStatuses[index]}")
                                        statusMeta.add(true to statusKeys[index])
                                        titles.add("${getString(R.string.manga)} \u2022 ${mangaStatuses[index]}")
                                        statusMeta.add(false to statusKeys[index])
                                    }
                                    val selected = BooleanArray(titles.size) { false }
                                    withContext(Dispatchers.Main) {
                                        context.customAlertDialog().apply {
                                            setTitle(R.string.select_statuses_to_import)
                                            multiChoiceItems(titles.toTypedArray(), selected) {}
                                            setPosButton(R.string.import_action) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val animeSelected = mutableSetOf<String>()
                                                    val mangaSelected = mutableSetOf<String>()
                                                    statusMeta.forEachIndexed { index, (isAnime, status) ->
                                                        if (selected[index]) { if (isAnime) animeSelected.add(status) else mangaSelected.add(status) }
                                                    }
                                                    val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                                    var importedCount = 0
                                                    fun importMedia(list: List<Media>, statuses: Set<String>) {
                                                        list.forEach { media ->
                                                            if (media.userStatus != null && statuses.contains(media.userStatus)) {
                                                                if (!existingIds.contains(media.id)) { existingIds.add(media.id); importedCount++; SubscriptionHelper.saveSubscription(media, true) }
                                                            }
                                                        }
                                                    }
                                                    importMedia(animeAll, animeSelected)
                                                    importMedia(mangaAll, mangaSelected)
                                                    withContext(Dispatchers.Main) { toast(getString(R.string.imported_subscriptions_count, importedCount)) }
                                                }
                                            }
                                            setNegButton(R.string.cancel)
                                            show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { toast(e.message ?: "Import failed") }
                                }
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Import Simkl library",
                        desc = "Add shows you are watching on Simkl to subscriptions",
                        iconRes = R.drawable.ic_round_playlist_add_24,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    if (!Simkl.getSavedToken() || Simkl.token == null) {
                                        withContext(Dispatchers.Main) { toast("Log in to Simkl first") }
                                        return@launch
                                    }
                                    val library = Simkl.getLibrary()
                                    if (library == null) {
                                        withContext(Dispatchers.Main) { toast("Could not load Simkl library") }
                                        return@launch
                                    }
                                    val watching = (library.shows ?: emptyList()).filter {
                                        val status = it.status?.lowercase()
                                        status == "watching" || status == "current"
                                    }
                                    var imported = 0
                                    watching.forEach { item ->
                                        val name = item.title ?: return@forEach
                                        val image = Simkl.imageUrl(item.poster)
                                        val anilistId = item.ids?.anilist
                                        val tmdbId = item.ids?.tmdb
                                        when {
                                            anilistId != null && anilistId > 0 -> {
                                                if (SubscriptionHelper.importSubscription(anilistId, name, image)) imported++
                                            }
                                            tmdbId != null && tmdbId > 0 -> {
                                                if (!TmdbSubscriptionHelper.isSubscribed(tmdbId)) {
                                                    TmdbSubscriptionHelper.subscribe(tmdbId, name, "tv", image, null)
                                                    imported++
                                                }
                                            }
                                        }
                                    }
                                    PushSync.sync(context)
                                    withContext(Dispatchers.Main) {
                                        toast(
                                            if (watching.isEmpty()) "No watching shows found on Simkl"
                                            else "Imported $imported subscriptions from Simkl"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { toast(e.message ?: "Import failed") }
                                }
                            }
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "AniList & Comments", R.drawable.ic_anilist,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Ignored Categories",
                        desc = "Checked categories are ignored. Uncheck a category to receive it",
                        iconRes = R.drawable.ic_anilist,
                        onClick = {
                            val types = NotificationType.entries.map { it.name }
                            val filteredTypes = PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes).toMutableSet()
                            val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
                            context.customAlertDialog().apply {
                                setTitle(R.string.anilist_notification_filters)
                                multiChoiceItems(
                                    types.map { name ->
                                        name.replace("_", " ").lowercase().replaceFirstChar {
                                            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                                        }
                                    }.toTypedArray(), selected
                                ) { updatedSelected ->
                                    types.forEachIndexed { index, type ->
                                        if (updatedSelected[index]) filteredTypes.add(type) else filteredTypes.remove(type)
                                    }
                                    PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
                                }
                                show()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.anilist_notifications_checking_time),
                        desc = aItems[PrefManager.getVal(PrefName.AnilistNotificationInterval)],
                        iconRes = R.drawable.ic_round_notifications_none_24,
                        choice = SubscreenBuilder.Choice(
                            title = getString(R.string.anilist_notifications_checking_time),
                            options = aItems.toTypedArray(),
                            currentIndex = PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval),
                        ) { i ->
                            PrefManager.setVal(PrefName.AnilistNotificationInterval, i)
                            TaskScheduler.refresh(context)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.comment_notification_checking_time),
                        desc = cItems[PrefManager.getVal(PrefName.CommentNotificationInterval)],
                        iconRes = R.drawable.ic_round_notifications_none_24,
                        choice = SubscreenBuilder.Choice(
                            title = getString(R.string.comment_notification_checking_time),
                            options = cItems.toTypedArray(),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CommentNotificationInterval),
                        ) { i ->
                            PrefManager.setVal(PrefName.CommentNotificationInterval, i)
                            TaskScheduler.refresh(context)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Push Notifications", R.drawable.ic_round_new_releases_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Server push (FCM)",
                        desc = "Alerts for AniList, TMDB and Simkl titles sent from our push server. These can arrive up to an hour late, so on-device checks are usually faster.",
                        iconRes = R.drawable.ic_round_new_releases_24,
                        isEnabled = notificationsEnabled,
                        switch = PrefManager.getVal<Boolean>(PrefName.PushNotifications) to { enabled: Boolean ->
                            PrefManager.setVal(PrefName.PushNotifications, enabled)
                            PushSync.sync(context)
                            if (enabled) {
                                toast("Push enabled. Delivery can take up to an hour")
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Include Simkl titles",
                        desc = if (simklLoggedIn) "Add shows marked watching on Simkl to push alerts"
                        else "Log in to Simkl to use this",
                        iconRes = R.drawable.ic_anilist,
                        isEnabled = notificationsEnabled && simklLoggedIn,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationSimkl) to { enabled: Boolean ->
                            PrefManager.setVal(PrefName.NotificationSimkl, enabled)
                            PushSync.sync(context)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Alert Popups", R.drawable.ic_round_notifications_active_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Show checking progress",
                        desc = "Display a progress notification while subscriptions are being checked",
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.SubscriptionCheckingNotifications) to {
                            PrefManager.setVal(PrefName.SubscriptionCheckingNotifications, it)
                        },
                        onLongClick = { openSettings(context, null) },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.subscription_prompt_at_end),
                        desc = getString(R.string.subscription_prompt_at_end_desc),
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.SubscriptionPromptAtEnd) to {
                            PrefManager.setVal(PrefName.SubscriptionPromptAtEnd, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.notification_popup),
                        desc = "Show a popup at the top of the screen when a new alert arrives",
                        iconRes = R.drawable.ic_round_notifications_none_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationPopup) to {
                            PrefManager.setVal(PrefName.NotificationPopup, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "AniList Airing Alerts",
                        desc = "New episodes from shows you follow on AniList",
                        iconRes = R.drawable.ic_round_new_releases_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationEpisodeAiring) to {
                            PrefManager.setVal(PrefName.NotificationEpisodeAiring, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "AniList Replies & Mentions",
                        desc = "Replies and mentions in AniList activity",
                        iconRes = R.drawable.ic_round_comment_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationNewComment) to {
                            PrefManager.setVal(PrefName.NotificationNewComment, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Media Data Changes",
                        desc = "AniList entries are edited (metadata updates)",
                        iconRes = R.drawable.ic_round_playlist_add_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationCompletedEpisode) to {
                            PrefManager.setVal(PrefName.NotificationCompletedEpisode, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Media Merge & Removal",
                        desc = "AniList duplicates merged, entries removed or related media added",
                        iconRes = R.drawable.ic_round_playlist_play_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationCompletedAnime) to {
                            PrefManager.setVal(PrefName.NotificationCompletedAnime, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "New Follower",
                        desc = "Someone follows you on AniList",
                        iconRes = R.drawable.ic_round_person_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationNewFollower) to {
                            PrefManager.setVal(PrefName.NotificationNewFollower, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Detailed list update popup",
                        desc = "Show cover and status when you change a list status. Off shows a generic \"List updated\" popup",
                        iconRes = R.drawable.ic_round_playlist_play_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.ListStatusNotification) to {
                            PrefManager.setVal(PrefName.ListStatusNotification, it)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "System", R.drawable.ic_baseline_dns_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.use_alarm_manager_reliable),
                        desc = getString(R.string.use_alarm_manager_reliable_desc),
                        iconRes = R.drawable.ic_anilist,
                        switch = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager) to { isChecked ->
                            if (isChecked) {
                                context.customAlertDialog().apply {
                                    setTitle(R.string.use_alarm_manager)
                                    setMessage(R.string.use_alarm_manager_confirm)
                                    setPosButton(R.string.use) {
                                        PrefManager.setVal(PrefName.UseAlarmManager, true)
                                        var waitingForPermission = false
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                            if (!alarmManager.canScheduleExactAlarms()) {
                                                waitingForPermission = true
                                                pendingAlarmReschedule = true
                                                context.startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"))
                                            }
                                        }
                                        if (!waitingForPermission) {
                                            TaskScheduler.refresh(context)
                                            context.recreate()
                                        }
                                    }
                                    setNegButton(R.string.cancel) {
                                        PrefManager.setVal(PrefName.UseAlarmManager, false)
                                        context.recreate()
                                    }
                                    show()
                                }
                            } else {
                                PrefManager.setVal(PrefName.UseAlarmManager, false)
                                TaskScheduler.refresh(context)
                                context.recreate()
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "AniList Notifications",
                        desc = "Master switch for AniList alerts and the bell badge count",
                        iconRes = R.drawable.ic_anilist,
                        switch = PrefManager.getVal<Boolean>(PrefName.AnilistNotifications) to { v: Boolean ->
                            PrefManager.setVal(PrefName.AnilistNotifications, v)
                            TaskScheduler.refresh(context)
                            PushSync.sync(context)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Subscription Alerts",
                        desc = "Check your anime and TMDB subscriptions for new episodes",
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications) to { v: Boolean ->
                            PrefManager.setVal(PrefName.EpisodeNotifications, v)
                            TaskScheduler.refresh(context)
                            PushSync.sync(context)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Test Notification",
                        desc = "Show a test notification popup",
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        onClick = {
                            context.startActivity(Intent(context, NotificationPopupActivity::class.java).apply {
                                putExtra("title", "Test Notification")
                                putExtra("text", "This is a test popup notification!")
                            })
                        },
                    ),
                ),
            ),
        )

        SubscreenBuilder.build(this, binding.subscreenContent, buildSections())
    }

    override fun onResume() {
        super.onResume()
        if (pendingAlarmReschedule) {
            pendingAlarmReschedule = false
            TaskScheduler.refresh(this)
            recreate()
        }
    }
}
