package ani.sanin.download

import android.content.Context
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal object HlsRemuxer {
    suspend fun download(
        context: Context,
        item: DownloadItem,
        client: okhttp3.OkHttpClient,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) = DownloadHlsCache.download(context, item, client, onProgress, isActive)
}
