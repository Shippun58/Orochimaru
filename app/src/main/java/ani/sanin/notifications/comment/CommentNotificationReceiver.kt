package ani.sanin.notifications.comment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.sanin.notifications.AlarmManagerScheduler
import ani.sanin.notifications.TaskScheduler
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.runBlocking

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive")
        runBlocking {
            CommentNotificationTask().execute(context)
        }
        val commentInterval =
            CommentNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.CommentNotificationInterval)]
        AlarmManagerScheduler(context).scheduleRepeatingTask(
            TaskScheduler.TaskType.COMMENT_NOTIFICATION,
            commentInterval
        )
    }
}