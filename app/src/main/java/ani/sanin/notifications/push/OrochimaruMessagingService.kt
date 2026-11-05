package ani.sanin.notifications.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.sanin.MainActivity
import ani.sanin.R
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbDetailsActivity.Companion.ARG_MEDIA_ID
import ani.sanin.cloudstream.TmdbDetailsActivity.Companion.ARG_MEDIA_TYPE
import ani.sanin.util.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import eu.kanade.tachiyomi.data.notification.Notifications

class OrochimaruMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushSync.sync(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data ?: return
        if (data["type"] != "airing") return

        val source = data["source"]
        val mediaId = data["mediaId"]?.toIntOrNull()
        val tmdbId = data["tmdbId"]?.toIntOrNull()
        val episode = data["episode"] ?: ""

        val contentIntent = buildContentIntent(source, mediaId, tmdbId) ?: return

        val title = message.notification?.title
            ?: data["title"]
            ?: getString(R.string.push_new_episode_title)
        val body = message.notification?.body
            ?: getString(R.string.push_new_episode_body)

        val notifyId = ("push-$source-$mediaId-$tmdbId-$episode").hashCode()

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ANIME_PUSH)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    notifyId,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            val manager = NotificationManagerCompat.from(this)
            if (manager.areNotificationsEnabled()) {
                manager.notify(notifyId, notification)
            }
        } catch (e: SecurityException) {
            Logger.log("OrochimaruMessagingService notify failed: ${e.message}")
        }
    }

    private fun buildContentIntent(source: String?, mediaId: Int?, tmdbId: Int?): Intent? =
        when {
            source == "tmdb" && tmdbId != null -> {
                Intent(this, TmdbDetailsActivity::class.java)
                    .putExtra(ARG_MEDIA_TYPE, "tv")
                    .putExtra(ARG_MEDIA_ID, tmdbId)
            }
            source == "anilist" && mediaId != null -> {
                Intent(this, MainActivity::class.java)
                    .putExtra("mediaId", mediaId)
                    .putExtra("mediaType", "ANIME")
            }
            else -> null
        }.also { it?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }

    companion object {
        const val ACTION_OPEN_MEDIA = "ani.sanin.OPEN_MEDIA"
    }
}