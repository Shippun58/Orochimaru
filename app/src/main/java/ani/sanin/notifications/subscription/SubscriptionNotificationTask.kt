package ani.sanin.notifications.subscription

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.sanin.App
import ani.sanin.FileUrl
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.anilist.UrlMedia
import ani.sanin.MainActivity
import ani.sanin.hasNotificationPermission
import ani.sanin.notifications.Task
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.Episode
import ani.sanin.parsers.MangaChapter
import ani.sanin.parsers.MangaSources
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import ani.sanin.util.TvKeyboardUtil
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK_PROGRESS
import eu.kanade.tachiyomi.data.notification.Notifications.ID_SUBSCRIPTION_CHECK_PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubscriptionNotificationTask : Task {
    private var currentlyPerforming = false

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: Context): Boolean {
        PrefManager.init(context)
        if (!currentlyPerforming) {
            try {
                withContext(Dispatchers.IO) {
                    currentlyPerforming = true
                    App.context = context
                    Logger.log("SubscriptionNotificationTask: execute")
                    var newSubscriptionCount = 0
                    val notificationManager = NotificationManagerCompat.from(context)

                    runCatching {
                        newSubscriptionCount += checkTmdbSubscriptions(context, notificationManager)
                    }.onFailure {
                        Logger.log("SubscriptionNotificationTask(TMDB): ${it.message}")
                    }

                    val subscriptions = SubscriptionHelper.getSubscriptions()
                    if (subscriptions.isNotEmpty()) {
                        var timeout = 15_000L
                        do {
                            delay(1000)
                            timeout -= 1000
                        } while (timeout > 0 && !AnimeSources.isInitialized && !MangaSources.isInitialized)
                        Logger.log("SubscriptionNotificationTask: timeout: $timeout")
                        if (timeout > 0) {
                            newSubscriptionCount += checkAnimeAndMangaSubscriptions(
                                context, notificationManager, subscriptions
                            )
                        }
                    }

                    if (newSubscriptionCount > 0) {
                        val currentSubsCount =
                            PrefManager.getVal<Int>(PrefName.UnreadSubscriptionNotifications)
                        PrefManager.setVal(
                            PrefName.UnreadSubscriptionNotifications,
                            currentSubsCount + newSubscriptionCount
                        )
                    }

                    currentlyPerforming = false
                }
                return true
            } catch (e: Exception) {
                Logger.log("SubscriptionNotificationTask: ${e.message}")
                Logger.log(e)
                return false
            }
        } else {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkAnimeAndMangaSubscriptions(
        context: Context,
        notificationManager: NotificationManagerCompat,
        subscriptions: Map<Int, SubscriptionHelper.Companion.SubscribeMedia>
    ): Int {
        var i = 0
        val index = subscriptions.map { i++; it.key to i }.toMap()

        val progressEnabled: Boolean =
            PrefManager.getVal(PrefName.SubscriptionCheckingNotifications)
        val progressNotification = if (progressEnabled) getProgressNotification(
            context,
            subscriptions.size
        ) else null
        if (progressNotification != null && hasNotificationPermission(context)) {
            notificationManager.notify(
                ID_SUBSCRIPTION_CHECK_PROGRESS,
                progressNotification.build()
            )
            CoroutineScope(Dispatchers.Main).launch {
                delay(5 * subscriptions.size * 1000L)
                notificationManager.cancel(ID_SUBSCRIPTION_CHECK_PROGRESS)
            }
        }

        fun progress(progress: Int, parser: String, media: String) {
            if (progressNotification != null && hasNotificationPermission(context))
                notificationManager.notify(
                    ID_SUBSCRIPTION_CHECK_PROGRESS,
                    progressNotification
                        .setProgress(subscriptions.size, progress, false)
                        .setContentText("$media on $parser")
                        .build()
                )
        }

        var newSubscriptionCount = 0
        subscriptions.toList().map {
            val media = it.second
            var episodeNumber: Int? = null
            var episodeTitle: String? = null
            var episodeThumbnail: String? = null
            val text = if (media.isAnime) {
                val parser =
                    SubscriptionHelper.getAnimeParser(media.id)
                progress(index[it.first]!!, parser.name, media.name)
                val ep: Episode? =
                    SubscriptionHelper.getEpisode(
                        parser,
                        media
                    )
                if (ep != null) {
                    episodeNumber = ep.number.toIntOrNull()
                    episodeTitle = ep.title
                    episodeThumbnail = ep.thumbnail?.url
                    context.getString(R.string.episode) + "${ep.number}${
                        if (ep.title != null) " : ${ep.title}" else ""
                    }${
                        if (ep.isFiller) " [Filler]" else ""
                    } " + context.getString(R.string.just_released) to ep.thumbnail
                }
                else null
            } else {
                val parser =
                    SubscriptionHelper.getMangaParser(media.id)
                progress(index[it.first]!!, parser.name, media.name)
                val ep: MangaChapter? =
                    SubscriptionHelper.getChapter(
                        parser,
                        media
                    )
                if (ep != null) {
                    episodeNumber = ep.number.toIntOrNull()
                    ep.number + " " + context.getString(R.string.just_released) to null
                }
                else null
            } ?: return@map
            addSubscriptionToStore(
                SubscriptionStore(
                    media.name,
                    text.first,
                    media.id,
                    image = media.image,
                    banner = media.banner,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    thumbnail = episodeThumbnail
                )
            )
            newSubscriptionCount++
            val notification = createNotification(
                context.applicationContext,
                media,
                text.first,
                text.second
            )
            if (hasNotificationPermission(context)) {
                notificationManager.notify(
                    CHANNEL_SUBSCRIPTION_CHECK,
                    System.currentTimeMillis().toInt(),
                    notification
                )
            }
            if (PrefManager.getVal<Boolean>(PrefName.NotificationPopup) && !TvKeyboardUtil.isTv(context)) {
                App.currentActivity()?.let {
                    val popupIntent = Intent(
                        context.applicationContext,
                        NotificationPopupActivity::class.java
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        putExtra("title", media.name)
                        putExtra("text", text.first)
                        putExtra("coverUrl", media.image)
                    }
                    context.startActivity(popupIntent)
                }
            }
        }
        if (progressNotification != null) notificationManager.cancel(
            ID_SUBSCRIPTION_CHECK_PROGRESS
        )
        return newSubscriptionCount
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkTmdbSubscriptions(
        context: Context,
        notificationManager: NotificationManagerCompat
    ): Int {

        val subs = TmdbSubscriptionHelper.getSubscriptions().toMutableMap()

        if (subs.isEmpty()) return 0
        Logger.log("SubscriptionNotificationTask: checking ${subs.size} TMDB subscriptions")

        var newSubscriptionCount = 0
        for ((id, item) in subs) {
            if (item.type != "tv") continue
            val detail = Tmdb.detail("tv", id) ?: run {
                Logger.log("SubscriptionNotificationTask: TMDB detail unavailable for '$id'")
                continue
            }

            var candidate = detail.lastEpisodeToAir
            if (candidate == null && detail.nextEpisodeToAir != null) {
                val airDate = detail.nextEpisodeToAir.airDate
                if (!airDate.isNullOrBlank()) {
                    val today = java.time.LocalDate.now()
                    val aired = try {
                        java.time.LocalDate.parse(airDate).isBefore(today) ||
                            java.time.LocalDate.parse(airDate).isEqual(today)
                    } catch (e: Exception) { false }
                    if (aired) candidate = detail.nextEpisodeToAir
                }
            }
            if (candidate == null) continue
            val season = candidate.seasonNumber
            val episode = candidate.episodeNumber

            if (item.lastSeason == 0 && item.lastEpisode == 0) {
                TmdbSubscriptionHelper.save(
                    item.copy(lastSeason = season, lastEpisode = episode)
                )
                Logger.log("SubscriptionNotificationTask(TMDB): baseline for '$id' = S${season}E$episode")
                continue
            }

            val isNewer = season > item.lastSeason ||
                (season == item.lastSeason && episode > item.lastEpisode)
            if (!isNewer) continue

            val title = candidate.name
            val text = context.getString(R.string.episode) + "$episode${
                if (!title.isNullOrBlank()) " : $title" else ""
            } " + context.getString(R.string.just_released)
            Logger.log("SubscriptionNotificationTask(TMDB): new episode '$id' S${season}E$episode")

            addSubscriptionToStore(
                SubscriptionStore(
                    item.name,
                    text,
                    id,
                    image = item.image,
                    banner = item.banner,
                    tmdbType = item.type,
                    episodeNumber = episode,
                    episodeTitle = title,
                    thumbnail = candidate.stillPath?.let { Tmdb.imageUrl(it, 500) },
                    airDate = candidate.airDate
                )
            )
            newSubscriptionCount++
            val notification = createTmdbNotification(context, item, text)
            if (hasNotificationPermission(context)) {
                notificationManager.notify(
                    CHANNEL_SUBSCRIPTION_CHECK,
                    System.currentTimeMillis().toInt(),
                    notification
                )
            }
            if (PrefManager.getVal<Boolean>(PrefName.NotificationPopup) && !TvKeyboardUtil.isTv(context)) {
                App.currentActivity()?.let {
                    val popupIntent = Intent(
                        context.applicationContext,
                        NotificationPopupActivity::class.java
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        putExtra("title", item.name)
                        putExtra("text", text)
                        putExtra("coverUrl", item.image)
                    }
                    context.startActivity(popupIntent)
                }
            }

            TmdbSubscriptionHelper.save(item.copy(lastSeason = season, lastEpisode = episode))
        }
        return newSubscriptionCount
    }

    @SuppressLint("MissingPermission")
    private fun createNotification(
        context: Context,
        media: SubscriptionHelper.Companion.SubscribeMedia,
        text: String,
        thumbnail: FileUrl?
    ): android.app.Notification {
        val pendingIntent = getIntent(context, media)
        val icon =
            if (media.isAnime) R.drawable.ic_round_movie_filter_24 else R.drawable.ic_round_menu_book_24

        val builder = NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK)
            .setSmallIcon(icon)
            .setContentTitle(media.name)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (thumbnail != null) {
            val bitmap = getBitmapFromUrl(thumbnail.url)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }

        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun createTmdbNotification(
        context: Context,
        media: TmdbSubscriptionHelper.Companion.TmdbSubscriptionItem,
        text: String
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK)
            .setSmallIcon(R.drawable.ic_round_movie_filter_24)
            .setContentTitle(media.name)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getTmdbIntent(context, media))
            .setAutoCancel(true)

        media.image?.let { url ->
            val bitmap = getBitmapFromUrl(url)
            if (bitmap != null) builder.setLargeIcon(bitmap)
        }

        return builder.build()
    }

    private fun getProgressNotification(
        context: Context,
        size: Int
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.checking_subscriptions_title))
            .setProgress(size, 0, false)
            .setOngoing(true)
            .setAutoCancel(false)
    }

    private fun getBitmapFromUrl(url: String): Bitmap? {
        return try {
            val inputStream = java.net.URL(url).openStream()
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    private fun getIntent(
        context: Context,
        media: SubscriptionHelper.Companion.SubscribeMedia
    ): PendingIntent {
        val notifyIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("mediaId", media.id)
            putExtra("media", media.id)
            putExtra("mediaType", if (media.isAnime) "ANIME" else "MANGA")
            putExtra("continue", true)
            action = "SUBSCRIPTION_${media.id}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            media.id,
            notifyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }

    private fun getTmdbIntent(
        context: Context,
        media: TmdbSubscriptionHelper.Companion.TmdbSubscriptionItem
    ): PendingIntent {
        val notifyIntent = Intent(context, TmdbDetailsActivity::class.java)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, media.type)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
            .setAction("tmdb_${media.type}_${media.id}")
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        return PendingIntent.getActivity(
            context, "tmdb_${media.type}_${media.id}".hashCode(), notifyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
        )
    }

    private fun addSubscriptionToStore(notification: SubscriptionStore) {
        val notificationStore = PrefManager.getNullableVal<List<SubscriptionStore>>(
            PrefName.SubscriptionNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()
        if (newStore.size >= 100) {
            newStore.remove(newStore.minByOrNull { it.time })
        }
        if (newStore.any { it.title == notification.title && it.content == notification.content }) {
            return
        }

        newStore.add(notification)
        PrefManager.setVal(PrefName.SubscriptionNotificationStore, newStore)
    }
}
