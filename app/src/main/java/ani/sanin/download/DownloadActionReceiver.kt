package ani.sanin.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID) ?: return
        DownloadManager.init(context.applicationContext)
        when (intent.action) {
            ACTION_PAUSE -> DownloadManager.pause(itemId)
            ACTION_DELETE -> DownloadManager.cancel(itemId)
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "download_item_id"
        const val ACTION_PAUSE = "ani.sanin.download.PAUSE"
        const val ACTION_DELETE = "ani.sanin.download.DELETE"
    }
}
