package ani.sanin.notifications

import android.content.Context
import ani.sanin.notifications.anilist.AnilistNotificationWorker
import ani.sanin.notifications.comment.CommentNotificationWorker
import ani.sanin.notifications.subscription.SubscriptionNotificationWorker
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

interface TaskScheduler {
    fun scheduleRepeatingTask(taskType: TaskType, interval: Long)
    fun cancelTask(taskType: TaskType)

    fun cancelAllTasks() {
        for (taskType in TaskType.entries) {
            cancelTask(taskType)
        }
    }

    fun scheduleAllTasks(context: Context) {
        for (taskType in TaskType.entries) {
            val interval = when (taskType) {
                TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.CommentNotificationInterval
                )]

                TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.AnilistNotificationInterval
                )]

                TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.SubscriptionNotificationInterval
                )]
            }
            scheduleRepeatingTask(taskType, interval)
        }
    }

    companion object {
        fun create(context: Context, useAlarmManager: Boolean): TaskScheduler {
            return if (useAlarmManager) {
                AlarmManagerScheduler(context)
            } else {
                WorkManagerScheduler(context)
            }
        }

        fun scheduleSingleWork(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
                workManager.enqueueUniqueWork(
                    CommentNotificationWorker.WORK_NAME + "_single",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequest.Builder(CommentNotificationWorker::class.java)
                        .build()
                )
            }
            if (PrefManager.getVal<Boolean>(PrefName.AnilistNotifications) &&
                AnilistNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.AnilistNotificationInterval)] > 0
            ) {
                workManager.enqueueUniqueWork(
                    AnilistNotificationWorker.WORK_NAME + "_single",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequest.Builder(AnilistNotificationWorker::class.java)
                        .build()
                )
            }
            if (PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications) &&
                SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.SubscriptionNotificationInterval)] > 0
            ) {
                workManager.enqueueUniqueWork(
                    SubscriptionNotificationWorker.WORK_NAME + "_single",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequest.Builder(SubscriptionNotificationWorker::class.java)
                        .build()
                )
            }
        }
    }

    enum class TaskType {
        COMMENT_NOTIFICATION,
        ANILIST_NOTIFICATION,
        SUBSCRIPTION_NOTIFICATION
    }
}

interface Task {
    suspend fun execute(context: Context): Boolean
}
