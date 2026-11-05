package ani.sanin.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader as Media3Downloader
import ani.sanin.parsers.VideoType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@UnstableApi
object DownloadHlsCache {
    const val LOCATOR_PREFIX = "sanin-hls-cache://"
    private const val TAG = "DownloadAdaptiveCache"

    @Volatile private var cache: SimpleCache? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "sanin-adaptive-download").apply { isDaemon = true }
    }

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        val directory = File(DownloadStorageHelper.appRoot(context), ".hls-cache")
        if (!directory.exists() && !directory.mkdirs()) {
            throw DownloadException("Could not create adaptive download cache")
        }
        return SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context.applicationContext))
            .also { cache = it }
    }

    fun locator(id: String): String = LOCATOR_PREFIX + Uri.encode(id)

    private fun mediaItem(url: String, type: VideoType): MediaItem =
        MediaItem.Builder().setUri(url).setMimeType(
            when (type) {
                VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
                VideoType.DASH -> MimeTypes.APPLICATION_MPD
                else -> throw DownloadException("Unsupported adaptive type: $type")
            },
        ).build()

    fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        offlineOnly: Boolean,
        client: OkHttpClient = Downloader.defaultClient(),
    ): CacheDataSource.Factory {
        val factory = CacheDataSource.Factory().setCache(getCache(context))
        if (!offlineOnly) {
            val httpFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)
            factory.setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        }
        return factory
    }

    fun offlineDataSourceFactory(context: Context): CacheDataSource.Factory =
        dataSourceFactory(context, emptyMap(), offlineOnly = true)

    suspend fun download(
        context: Context,
        item: DownloadItem,
        client: OkHttpClient,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        if (!isActive()) throw DownloadCancelledException()
        val playbackUrl = when (item.videoType) {
            VideoType.M3U8 -> resolveHlsPlaybackUrl(item, client)
            VideoType.DASH -> item.url
            else -> throw DownloadException("Adaptive cache does not support ${item.videoType}")
        }
        item.offlinePlaybackUrl = playbackUrl
        val factory = dataSourceFactory(context, item.headers, offlineOnly = false, client = client)
        val media = mediaItem(playbackUrl, item.videoType)
        val downloader: Media3Downloader = when (item.videoType) {
            VideoType.M3U8 -> HlsDownloader.Factory(factory).setExecutor(executor).create(media)
            VideoType.DASH -> DashDownloader.Factory(factory).setExecutor(executor).create(media)
            else -> throw DownloadException("Unsupported adaptive type: ${item.videoType}")
        }
        val job = currentCoroutineContext().job
        val cancellation = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) downloader.cancel()
        }
        try {
            runInterruptible(Dispatchers.IO) {
                downloader.download { contentLength, bytesDownloaded, percentDownloaded ->
                    val fraction = if (percentDownloaded.isFinite() && percentDownloaded >= 0f) {
                        (percentDownloaded / 100f).coerceIn(0f, 1f)
                    } else -1f
                    onProgress(bytesDownloaded, contentLength.coerceAtLeast(0L), fraction)
                }
            }
            if (!isActive()) throw DownloadCancelledException()
            Log.i(TAG, "cached type=${item.videoType} id=${item.id}")
        } finally {
            cancellation.dispose()
        }
    }

    private suspend fun resolveHlsPlaybackUrl(item: DownloadItem, client: OkHttpClient): String =
        runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(item.url).apply {
                item.headers.forEach { (name, value) -> addHeader(name, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadException("HTTP ${response.code} while resolving HLS playlist")
                }
                val playlist = response.body?.string()
                    ?: throw DownloadException("Empty HLS playlist response")
                val finalUrl = response.request.url.toString()
                val lines = playlist.lineSequence().map(String::trim).toList()
                val variants = mutableListOf<Pair<String, Int?>>()
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val variantUri = lines.drop(index + 1).firstOrNull {
                            it.isNotBlank() && !it.startsWith("#")
                        }
                        if (!variantUri.isNullOrBlank()) {
                            val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            variants += URI(finalUrl).resolve(variantUri).toString() to height
                        }
                    }
                }
                if (variants.isEmpty()) return@runInterruptible finalUrl
                val wanted = item.quality?.takeIf { it > 0 }
                val selected = if (wanted == null) {
                    variants.maxByOrNull { it.second ?: 0 }
                } else {
                    variants.minWithOrNull(compareBy<Pair<String, Int?>>(
                        { it.second?.let { height -> kotlin.math.abs(height - wanted) } ?: Int.MAX_VALUE },
                        { if ((it.second ?: Int.MAX_VALUE) <= wanted) 0 else 1 },
                    ))
                }
                selected?.first ?: finalUrl
            }
        }

    suspend fun remove(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>,
        type: VideoType = VideoType.M3U8,
        playbackUrl: String? = null,
    ) {
        val factory = dataSourceFactory(context, headers, offlineOnly = false)
        suspend fun removeUrl(url: String) {
            val media = mediaItem(url, type)
            val downloader: Media3Downloader = when (type) {
                VideoType.M3U8 -> HlsDownloader.Factory(factory).setExecutor(executor).create(media)
                VideoType.DASH -> DashDownloader.Factory(factory).setExecutor(executor).create(media)
                else -> return
            }
            runInterruptible(Dispatchers.IO) { downloader.remove() }
        }
        removeUrl(sourceUrl)
        if (!playbackUrl.isNullOrBlank() && playbackUrl != sourceUrl) removeUrl(playbackUrl)
        Log.i(TAG, "adaptive cache removed type=$type")
    }
}
