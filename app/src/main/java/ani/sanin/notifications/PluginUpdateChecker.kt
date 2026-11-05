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
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsSource
import ani.sanin.settings.ExtensionsActivity
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PluginUpdateChecker {

    data class UpdateInfo(
        val id: String,
        val displayName: String,
        val type: String,
        val installedVersion: Int,
        val availableVersion: Int,
        val repoUrl: String
    )

    suspend fun checkAndNotify(context: Context) {
        withContext(Dispatchers.IO) {
            PrefManager.init(context)
            if (!PrefManager.getVal<Boolean>(PrefName.CheckUpdate)) return@withContext

            val autoUpdate = PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions)

            val csUpdates = checkCloudStreamUpdates(context)
            val animeUpdates = checkAnimeExtensionUpdates()

            if (autoUpdate) {
                val updatedPlugins = mutableListOf<String>()

                for (info in csUpdates) {
                    val repoSource = findRepoSource(context, info)
                    if (repoSource != null) {
                        runCatching {
                            CsRepos.install(context, info.repoUrl, repoSource)
                            updatedPlugins.add(info.displayName)
                            Logger.log("PluginUpdateChecker: Auto-updated CS plugin ${info.displayName}")
                        }
                    }
                }

                if (updatedPlugins.isNotEmpty()) {
                    showNotification(
                        context,
                        Notifications.CHANNEL_PLUGIN_UPDATE,
                        Notifications.ID_PLUGIN_UPDATES,
                        "Plugins Updated",
                        "Updated ${updatedPlugins.size} plugin(s): ${updatedPlugins.joinToString(", ")}",
                        extensionsActivityPendingIntent(
                            context,
                            Notifications.ID_PLUGIN_UPDATES,
                            cloudStreamMode = true
                        )
                    )
                }

                if (animeUpdates.isNotEmpty()) {
                    val extensionLines = animeUpdates.map { it.name }
                    showNotification(
                        context,
                        Notifications.CHANNEL_EXTENSIONS_UPDATE,
                        Notifications.ID_UPDATES_TO_EXTS,
                        "Extensions Updated",
                        "Updated ${animeUpdates.size} extension(s): ${extensionLines.joinToString(", ")}\n" +
                            "Installation still required — tap to install.",
                        extensionsActivityPendingIntent(
                            context,
                            Notifications.ID_UPDATES_TO_EXTS,
                            installUpdates = true
                        )
                    )
                }
            } else {

                if (csUpdates.isNotEmpty()) {
                    val pluginLines = csUpdates.map {
                        "${it.displayName} (v${it.installedVersion} → v${it.availableVersion})"
                    }
                    showNotification(
                        context,
                        Notifications.CHANNEL_PLUGIN_UPDATE,
                        Notifications.ID_PLUGIN_UPDATES,
                        "${csUpdates.size} Plugin Update${if (csUpdates.size > 1) "s" else ""} Available",
                        pluginLines.joinToString("\n"),
                        extensionsActivityPendingIntent(
                            context,
                            Notifications.ID_PLUGIN_UPDATES,
                            cloudStreamMode = true
                        )
                    )
                }

                if (animeUpdates.isNotEmpty()) {
                    val extensionLines = animeUpdates.map { it.name }
                    showNotification(
                        context,
                        Notifications.CHANNEL_EXTENSIONS_UPDATE,
                        Notifications.ID_UPDATES_TO_EXTS,
                        "${animeUpdates.size} Extension Update${if (animeUpdates.size > 1) "s" else ""} Available",
                        extensionLines.joinToString("\n"),
                        extensionsActivityPendingIntent(
                            context,
                            Notifications.ID_UPDATES_TO_EXTS
                        )
                    )
                }
            }
        }
    }

    private suspend fun checkCloudStreamUpdates(context: Context): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val installed = CsRepos.installed(context)
        val updates = mutableListOf<UpdateInfo>()
        val seenIds = mutableSetOf<String>()

        for (source in installed) {
            if (source.id in seenIds) continue
            val repos = CsRepos.repos()
            for (repoUrl in repos) {
                runCatching {
                    val plugins = CsRepos.getRepoPlugins(repoUrl)
                    val available = plugins.find { it.id == source.id }
                    if (available != null && available.version > source.version) {
                        updates.add(
                            UpdateInfo(
                                id = source.id,
                                displayName = source.name,
                                type = source.type,
                                installedVersion = source.version,
                                availableVersion = available.version,
                                repoUrl = repoUrl
                            )
                        )
                        seenIds.add(source.id)
                    }
                }
            }
        }
        updates
    }

    private suspend fun checkAnimeExtensionUpdates(): List<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Installed> {
        return try {
            val manager: AnimeExtensionManager = Injekt.get()
            val installed = manager.installedExtensionsFlow.first()
            installed.filter { it.hasUpdate }
        } catch (e: Exception) {
            Logger.log("PluginUpdateChecker: Failed to check anime extensions: ${e.message}")
            emptyList()
        }
    }

    private suspend fun findRepoSource(context: Context, info: UpdateInfo): CsSource? = withContext(Dispatchers.IO) {
        runCatching {
            val plugins = CsRepos.getRepoPlugins(info.repoUrl)
            plugins.find { it.id == info.id && it.version == info.availableVersion }
        }.getOrNull()
    }

    private fun extensionsActivityPendingIntent(
        context: Context,
        requestCode: Int,
        cloudStreamMode: Boolean = false,
        installUpdates: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, ExtensionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ExtensionsActivity.EXTRA_CLOUDSTREAM_MODE, cloudStreamMode)
            putExtra(ExtensionsActivity.EXTRA_INSTALL_UPDATES, installUpdates)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
        pendingIntent: PendingIntent
    ) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(notificationId, notification)
        }
    }
}
