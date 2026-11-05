package ani.sanin.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

class DownloadNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private fun enabled(): Boolean = PrefManager.getVal<Boolean>(PrefName.DownloadNotifications)

    private fun completedEnabled(): Boolean =
        enabled() && PrefManager.getVal<Boolean>(PrefName.DownloadCompletedNotification)

    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
                manager.createNotificationChannel(ch)
            }
        }
        return CHANNEL_ID
    }

    fun progress(item: DownloadItem) {
        if (!enabled()) return
        val indeterminate = item.totalBytes <= 0L
        val pct = if (indeterminate) 0 else (item.progress * 100).toInt().coerceIn(0, 100)
        val notif = NotificationCompat.Builder(context, channel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.mediaName)
            .setContentIntent(PendingIntent.getActivity(context, 7200, Intent(context, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_media_pause, "Pause", PendingIntent.getBroadcast(context, item.id.hashCode(), Intent(context, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.ACTION_PAUSE).putExtra(DownloadActionReceiver.EXTRA_ITEM_ID, item.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_delete, "Delete", PendingIntent.getBroadcast(context, item.id.hashCode() + 1, Intent(context, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.ACTION_DELETE).putExtra(DownloadActionReceiver.EXTRA_ITEM_ID, item.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setContentText(
                context.getString(R.string.downloading_episode, item.episodeNumber),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .build()
        notifySafely(PROGRESS_ID, notif)
    }

    fun completed(item: DownloadItem) {
        manager.cancel(PROGRESS_ID)
        if (!completedEnabled()) return
        val notif = NotificationCompat.Builder(context, channel())
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.download_complete))
            .setContentText("${item.mediaName} • ${item.episodeNumber}")
            .setContentIntent(PendingIntent.getActivity(context, 7200, Intent(context, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        notifySafely(COMPLETE_ID, notif)
    }

    fun error(item: DownloadItem, message: String?) {
        manager.cancel(PROGRESS_ID)
        if (!enabled()) return
        val notif = NotificationCompat.Builder(context, channel())
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText("${item.mediaName} • ${item.episodeNumber}: ${message ?: ""}")
            .setContentIntent(PendingIntent.getActivity(context, 7200, Intent(context, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        notifySafely(ERROR_ID, notif)
    }

    fun clearActive() {
        manager.cancel(PROGRESS_ID)
    }

    private fun notifySafely(id: Int, notif: android.app.Notification) {
        try {
            manager.notify(id, notif)
        } catch (_: SecurityException) {

        }
    }

    companion object {
        const val CHANNEL_ID = "anime_downloads"
        const val PROGRESS_ID = 7201
        const val COMPLETE_ID = 7202
        const val ERROR_ID = 7203
    }
}