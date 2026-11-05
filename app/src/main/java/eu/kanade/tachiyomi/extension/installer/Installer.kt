package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.annotation.CallSuper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ani.sanin.media.MediaType
import ani.sanin.media.Type
import ani.sanin.parsers.novel.NovelExtensionManager
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.injectLazy
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

abstract class Installer(private val service: Service) {

    private val animeExtensionManager: AnimeExtensionManager by injectLazy()

    private var waitingInstall = AtomicReference<Entry>(null)
    private val queue = Collections.synchronizedList(mutableListOf<Entry>())

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1).takeIf { it >= 0 } ?: return
            cancelQueue(downloadId)
        }
    }

    abstract var ready: Boolean

    fun addToQueue(type: Type, downloadId: Long, uri: Uri) {
        queue.add(Entry(type, downloadId, uri))
        checkQueue()
    }

    @CallSuper
    open fun processEntry(entry: Entry) {
        if (entry.type is MediaType) {
            animeExtensionManager.setInstalling(entry.downloadId)
        }
    }

    open fun cancelEntry(entry: Entry): Boolean {
        return true
    }

    fun continueQueue(resultStep: InstallStep) {
        val completedEntry = waitingInstall.getAndSet(null)
        if (completedEntry != null) {
            if (completedEntry.type is MediaType) {
                animeExtensionManager.updateInstallStep(
                    completedEntry.downloadId,
                    resultStep
                )
            } else {

            }
            checkQueue()
        }
    }

    private fun checkQueue() {
        if (!ready) {
            return
        }
        if (queue.isEmpty()) {
            service.stopSelf()
            return
        }
        val nextEntry = queue.first()
        if (waitingInstall.compareAndSet(null, nextEntry)) {
            queue.removeAt(0)
            processEntry(nextEntry)
        }
    }

    @CallSuper
    open fun onDestroy() {
        LocalBroadcastManager.getInstance(service).unregisterReceiver(cancelReceiver)
        queue.forEach {

            if (it.type is MediaType) {
                animeExtensionManager.updateInstallStep(
                    it.downloadId,
                    InstallStep.Error
                )
            } else {

            }
        }
        queue.clear()
        waitingInstall.set(null)
    }

    protected fun getActiveEntry(): Entry? = waitingInstall.get()

    private fun cancelQueue(downloadId: Long) {
        val waitingInstall = this.waitingInstall.get()
        val toCancel = queue.find { it.downloadId == downloadId } ?: waitingInstall ?: return
        if (cancelEntry(toCancel)) {
            queue.remove(toCancel)
            if (waitingInstall == toCancel) {

                this.waitingInstall.set(null)
                checkQueue()
            }
            if (toCancel.type is MediaType) {
                val manager = when (toCancel.type) {
                    MediaType.ANIME -> animeExtensionManager
                }
                manager?.updateInstallStep(downloadId, InstallStep.Idle)
            } else {

            }
        }
    }

    data class Entry(val type: Type, val downloadId: Long, val uri: Uri)

    init {
        val filter = IntentFilter(ACTION_CANCEL_QUEUE)
        LocalBroadcastManager.getInstance(service).registerReceiver(cancelReceiver, filter)
    }

    companion object {
        private const val ACTION_CANCEL_QUEUE = "Installer.action.CANCEL_QUEUE"
        private const val EXTRA_DOWNLOAD_ID = "Installer.extra.DOWNLOAD_ID"

        fun cancelInstallQueue(context: Context, downloadId: Long) {
            val intent = Intent(ACTION_CANCEL_QUEUE)
            intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
