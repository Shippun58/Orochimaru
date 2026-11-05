package ani.sanin.notifications.subscription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger

class SubscriptionNotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("SubscriptionNotificationWorker: doWork")
        if (!PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications)) {
            Logger.log("SubscriptionNotificationWorker: disabled by pref")
            return Result.success()
        }
        PrefManager.init(applicationContext)
        if (System.currentTimeMillis() - lastCheck < 60000) {
            Logger.log("SubscriptionNotificationWorker: doWork skipped")
            return Result.success()
        }
        lastCheck = System.currentTimeMillis()
        return if (SubscriptionNotificationTask().execute(applicationContext)) {
            Result.success()
        } else {
            Logger.log("SubscriptionNotificationWorker: doWork failed")
            Result.retry()
        }
    }

    companion object {
        val checkIntervals = arrayOf(0L, 480, 720, 1440)
        const val WORK_NAME =
            "ani.sanin.notifications.subscription.SubscriptionNotificationWorker"
        private var lastCheck = 0L
    }
}