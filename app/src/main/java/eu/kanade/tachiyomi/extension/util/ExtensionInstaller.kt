package eu.kanade.tachiyomi.extension.util

import android.app.DownloadManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import ani.sanin.R
import ani.sanin.media.MediaType
import ani.sanin.media.Type
import ani.sanin.toast
import ani.sanin.util.Logger
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.util.storage.getUriCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

class ExtensionInstaller(private val context: Context) {

    private val downloadManager = context.getSystemService<DownloadManager>()!!

    private val downloadReceiver = DownloadCompletionReceiver()

    private val activeDownloads = hashMapOf<String, Long>()

    private val downloadsRelay = PublishRelay.create<Pair<Long, InstallStep>>()

    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    fun <T : Type> downloadAndInstall(
        url: String,
        pkgName: String,
        name: String,
        type: T
    ): Observable<InstallStep> = Observable.defer {
        val oldDownload = activeDownloads[pkgName]
        if (oldDownload != null) {
            deleteDownload(pkgName)
        }

        downloadReceiver.register()

        val downloadUri = url.toUri()
        val request = DownloadManager.Request(downloadUri)
            .setTitle(name)
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                downloadUri.lastPathSegment
            )
            .setDescription(type.asText())
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = downloadManager.enqueue(request)
        activeDownloads[pkgName] = id

        downloadsRelay.filter { it.first == id }
            .map { it.second }
            .mergeWith(pollStatus(id))
            .takeUntil { it.isCompleted() }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnUnsubscribe { deleteDownload(pkgName) }
    }

    private fun pollStatus(id: Long): Observable<InstallStep> {
        val query = DownloadManager.Query().setFilterById(id)

        return Observable.interval(0, 1, TimeUnit.SECONDS)

            .map {
                downloadManager.query(query).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            }

            .distinctUntilChanged()

            .takeUntil { it == DownloadManager.STATUS_SUCCESSFUL || it == DownloadManager.STATUS_FAILED }

            .flatMap { status ->
                when (status) {
                    DownloadManager.STATUS_PENDING -> Observable.just(InstallStep.Pending)
                    DownloadManager.STATUS_RUNNING -> Observable.just(InstallStep.Downloading)
                    else -> Observable.empty()
                }
            }
    }

    fun installApk(type: Type, downloadId: Long, uri: Uri) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(uri, APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (type is MediaType) {
                    intent.putExtra(EXTRA_EXTENSION_TYPE, type)
                }

                context.startActivity(intent)
            }

            else -> {
                val intent =
                    ExtensionInstallService.getIntent(context, type, downloadId, uri, installer)
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: RuntimeException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                        toast(context.getString(R.string.error_msg, context.getString(R.string.foreground_service_not_allowed)))
                    } else {
                        toast(context.getString(R.string.error_msg, e.message))
                    }
                    Logger.log(e)
                }
            }
        }
    }

    fun cancelInstall(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName) ?: return
        downloadManager.remove(downloadId)
        Installer.cancelInstallQueue(context, downloadId)
    }

    fun uninstallApk(pkgName: String) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            Intent(Intent.ACTION_DELETE).setData("package:$pkgName".toUri())
        else
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())

        context.startActivity(intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        downloadsRelay.call(downloadId to step)
    }

    private fun deleteDownload(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName)
        if (downloadId != null) {
            downloadManager.remove(downloadId)
        }
        if (activeDownloads.isEmpty()) {
            downloadReceiver.unregister()
        }
    }

    private inner class DownloadCompletionReceiver : BroadcastReceiver() {

        private var isRegistered = false

        fun register() {
            if (isRegistered) return
            isRegistered = true

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        fun unregister() {
            if (!isRegistered) return
            isRegistered = false

            context.unregisterReceiver(this)
        }

        override fun onReceive(context: Context, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0) ?: return

            if (id !in activeDownloads.values) return

            val uri = downloadManager.getUriForDownloadedFile(id)

            if (uri == null) {
                Logger.log("Couldn't locate downloaded APK")
                downloadsRelay.call(id to InstallStep.Error)
                return
            }

            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                    ).removePrefix(FILE_SCHEME)
                    val type = MediaType.fromText(
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION),
                        )
                    ) ?: return

                    installApk(type, id, File(localUri).getUriCompat(context))
                }
            }
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
        const val EXTRA_EXTENSION_TYPE = "ExtensionInstaller.extra.EXTENSION_TYPE"
        const val EXTRA_ADDON_TYPE = "ExtensionInstaller.extra.ADDON_TYPE"
        const val FILE_SCHEME = "file://"
    }
}
