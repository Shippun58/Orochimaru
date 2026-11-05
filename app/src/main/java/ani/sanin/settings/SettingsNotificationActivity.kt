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
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.media.Media
import ani.sanin.navBarHeight
import ani.sanin.notifications.TaskScheduler
import ani.sanin.notifications.subscription.NotificationPopupActivity
import ani.sanin.notifications.subscription.SubscriptionHelper
import ani.sanin.notifications.subscription.SubscriptionNotificationWorker
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
        binding.subscreenSubtitle.text = "Alerts, subscriptions & popups"
        binding.subscreenIcon.setImageResource(R.drawable.ic_round_notifications_active_24)

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
                            TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(context)
                        },
                        onLongClick = {
                            TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(context)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.view_subscriptions),
                        desc = getString(R.string.view_subscriptions_desc),
                        iconRes = R.drawable.ic_round_search_24,
                        onClick = {
                            SubscriptionsBottomDialog.newInstance(SubscriptionHelper.getSubscriptions())
                                .show(supportFragmentManager, "subscriptions")
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
                ),
            ),

            SubscreenBuilder.Section(
                "AniList & Comments", R.drawable.ic_anilist,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.anilist_notification_filters),
                        desc = getString(R.string.anilist_notification_filters_desc),
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
                            TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(context)
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
                            TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(context)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Alert Popups", R.drawable.ic_round_notifications_active_24,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.notification_for_checking_subscriptions),
                        desc = getString(R.string.notification_for_checking_subscriptions_desc),
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
                        desc = getString(R.string.notification_popup_desc),
                        iconRes = R.drawable.ic_round_notifications_none_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationPopup) to {
                            PrefManager.setVal(PrefName.NotificationPopup, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "New Episode Airing",
                        desc = "Show popup when a new episode airs",
                        iconRes = R.drawable.ic_round_new_releases_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationEpisodeAiring) to {
                            PrefManager.setVal(PrefName.NotificationEpisodeAiring, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "New Comment Reply",
                        desc = "Show popup when someone replies to your comment",
                        iconRes = R.drawable.ic_round_comment_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationNewComment) to {
                            PrefManager.setVal(PrefName.NotificationNewComment, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Completed Episode",
                        desc = "Show popup when an episode is marked complete",
                        iconRes = R.drawable.ic_round_playlist_add_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationCompletedEpisode) to {
                            PrefManager.setVal(PrefName.NotificationCompletedEpisode, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Completed Anime",
                        desc = "Show popup when an anime is marked complete",
                        iconRes = R.drawable.ic_round_playlist_play_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationCompletedAnime) to {
                            PrefManager.setVal(PrefName.NotificationCompletedAnime, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "New Follower",
                        desc = "Show popup when someone follows you",
                        iconRes = R.drawable.ic_round_person_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.NotificationNewFollower) to {
                            PrefManager.setVal(PrefName.NotificationNewFollower, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "List Status Notification",
                        desc = "Show popup when anime list status changes",
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
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            if (!(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
                                                context.startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"))
                                            }
                                        }
                                    }
                                    setNegButton(R.string.cancel) {
                                        PrefManager.setVal(PrefName.UseAlarmManager, false)
                                        recreate()
                                    }
                                    show()
                                }
                            } else {
                                PrefManager.setVal(PrefName.UseAlarmManager, false)
                                TaskScheduler.create(context, true).cancelAllTasks()
                                TaskScheduler.create(context, false).scheduleAllTasks(context)
                            }
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "AniList Count",
                        desc = "Fetch notification count from AniList and display it as a badge on the bell icon",
                        iconRes = R.drawable.ic_anilist,
                        switch = PrefManager.getVal<Boolean>(PrefName.AnilistNotifications) to { v: Boolean -> PrefManager.setVal(PrefName.AnilistNotifications, v) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "New Episode Alerts",
                        desc = "Notify when new episodes air for shows you are tracking",
                        iconRes = R.drawable.ic_round_notifications_active_24,
                        switch = PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications) to { v: Boolean -> PrefManager.setVal(PrefName.EpisodeNotifications, v) },
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
}
