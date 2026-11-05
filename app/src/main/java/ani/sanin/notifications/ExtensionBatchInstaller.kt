package ani.sanin.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.sanin.R
import ani.sanin.settings.ExtensionsActivity
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

object ExtensionBatchInstaller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptions = CompositeSubscription()
    private var running = false

    fun installPending(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext

        scope.launch {
            val manager: AnimeExtensionManager = Injekt.get()

            val pending = withTimeoutOrNull(15_000) {
                manager.installedExtensionsFlow.first { list -> list.any { it.hasUpdate } }
            }?.filter { it.hasUpdate }.orEmpty()

            if (pending.isEmpty()) {
                running = false
                return@launch
            }

            val succeeded = Collections.synchronizedList(mutableListOf<String>())
            val remaining = AtomicInteger(pending.size)

            fun onFinished() {
                if (remaining.decrementAndGet() == 0) {
                    running = false
                    subscriptions.clear()
                    val names = synchronized(succeeded) { succeeded.toList() }
                    if (names.isNotEmpty()) notifyInstalled(appContext, names)
                }
            }

            for (extension in pending) {
                val name = extension.name
                val subscription = manager.updateExtension(extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { step ->
                            if (step == InstallStep.Installed) {
                                synchronized(succeeded) {
                                    if (name !in succeeded) succeeded.add(name)
                                }
                            }
                        },
                        { error ->
                            Logger.log("ExtensionBatchInstaller: failed to update $name: ${error.message}")
                            onFinished()
                        },
                        { onFinished() },
                    )
                subscriptions.add(subscription)
            }
        }
    }

    private fun notifyInstalled(context: Context, names: List<String>) {
        val text = "${names.joinToString(", ")} installed successfully"
        val intent = Intent(context, ExtensionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            Notifications.ID_UPDATES_TO_EXTS,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_EXTENSIONS_UPDATE)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Extensions Installed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(Notifications.ID_UPDATES_TO_EXTS, notification)
        }
    }
}
