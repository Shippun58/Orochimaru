package ani.sanin.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File

object DownloadManager {

    private lateinit var appContext: Context
    private lateinit var downloader: Downloader
    private lateinit var notifier: DownloadNotifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val items = mutableListOf<DownloadItem>()
    private var completedItems = mutableListOf<DownloadedItem>()
    private val activeJobs = mutableMapOf<String, Job>()

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _completed = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val completed: StateFlow<List<DownloadedItem>> = _completed.asStateFlow()

    private var lastPersist = 0L

    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 3_000L

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        downloader = Downloader(appContext, Downloader.defaultClient())
        notifier = DownloadNotifier(appContext)
        items.clear()
        items.addAll(DownloadStore.loadQueue())
        completedItems = DownloadStore.loadCompleted().toMutableList()

        items.forEach {

            it.totalBytes = 0L
            it.downloadedBytes = 0L
            it.progress = 0f
            it.speed = 0L
            if (it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PREPARING ||
                it.status == DownloadStatus.RETRYING
            ) {
                it.status = DownloadStatus.QUEUED
            }
        }
        publish()

        publishCompleted()
        Log.i(
            "AnimeDownload",
            "init: queue=${items.size} completed=${completedItems.size} " +
                "statuses=${items.joinToString { it.status.toString() }}",
        )

        pump()
    }

    fun isDownloaded(mediaId: Int, episodeNumber: String): Boolean =
        completedItems.any { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    fun downloadedFor(mediaId: Int): List<DownloadedItem> =
        completedItems.filter { it.mediaId == mediaId }

    fun findCompleted(mediaId: Int, episodeNumber: String): DownloadedItem? =
        completedItems.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    fun itemFor(mediaId: Int, episodeNumber: String): DownloadItem? =
        items.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNumber }

    fun enqueue(item: DownloadItem) {

        if (isDownloaded(item.mediaId, item.episodeNumber)) return
        val existing = items.firstOrNull {
            it.mediaId == item.mediaId && it.episodeNumber == item.episodeNumber
        }
        if (existing != null) {

            if (existing.id == item.id &&
                (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.ERROR)
            ) {
                existing.status = DownloadStatus.QUEUED
                existing.error = null
                persist()
                publish()
                pump()
            }
            return
        }

        items.add(item)
        persist()
        publish()
        pump()
    }

    fun pause(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        when (item.status) {
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PREPARING,
            DownloadStatus.RETRYING,
            -> {
                item.status = DownloadStatus.PAUSED
                activeJobs[id]?.cancel()
            }

            DownloadStatus.QUEUED -> item.status = DownloadStatus.PAUSED
            else -> return
        }
        persist()
        publish()
    }

    fun resume(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        item.status = DownloadStatus.QUEUED
        item.error = null
        persist()
        publish()
        pump()
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        activeJobs[id]?.cancel()
        items.firstOrNull { it.id == id }?.let { Downloader.cleanup(appContext, it) }
        items.removeAll { it.id == id }
        persist()
        publish()
        pump()
    }

    fun pauseAll() {
        var changed = false
        items.forEach { item ->
            when (item.status) {
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PREPARING,
                DownloadStatus.RETRYING,
                -> {
                    item.status = DownloadStatus.PAUSED
                    activeJobs[item.id]?.cancel()
                    changed = true
                }

                DownloadStatus.QUEUED -> {
                    item.status = DownloadStatus.PAUSED
                    changed = true
                }

                else -> {}
            }
        }
        if (changed) {
            persist()
            publish()
        }
    }

    fun resumeAll() {
        var changed = false
        items.forEach { item ->
            if (item.status == DownloadStatus.PAUSED) {
                item.status = DownloadStatus.QUEUED
                item.error = null
                changed = true
            }
        }
        if (changed) {
            persist()
            publish()
            pump()
        }
    }

    fun removeCompleted(id: String, deleteFile: Boolean = true) {
        val item = completedItems.firstOrNull { it.id == id } ?: return
        if (deleteFile) deleteDownloadedFile(item)
        completedItems.removeAll { it.id == id }
        persistCompleted()
        publishCompleted()
    }

    fun clearCompleted(deleteFiles: Boolean = true) {
        if (deleteFiles) completedItems.forEach { deleteDownloadedFile(it) }
        completedItems.clear()
        persistCompleted()
        publishCompleted()
    }

    fun deleteAll() {
        activeJobs.keys.toList().forEach { id ->
                activeJobs[id]?.cancel()
        }
        items.toList().forEach { Downloader.cleanup(appContext, it) }
        items.clear()
        persist()
        publish()
        clearCompleted(true)
    }

    private fun deleteDownloadedFile(item: DownloadedItem) {
        if (item.path.startsWith(DownloadHlsCache.LOCATOR_PREFIX)) {
            val sourceUrl = item.sourceUrl ?: return
            scope.launch {
                try {
                    DownloadHlsCache.remove(
                        appContext,
                        sourceUrl,
                        item.headers.orEmpty(),
                        item.videoType ?: ani.sanin.parsers.VideoType.M3U8,
                        item.playbackUrl,
                    )
                } catch (e: Exception) {
                    Log.w("AnimeDownload", "Could not remove cached HLS ${item.id}", e)
                }
            }
            return
        }
        try {
            if (item.path.startsWith("content://")) {
                android.net.Uri.parse(item.path).let { uri ->
                    android.provider.DocumentsContract.deleteDocument(
                        appContext.contentResolver,
                        uri,
                    )
                }
            } else {
                File(item.path).takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun maxConcurrent(): Int =
        PrefManager.getVal<Int>(PrefName.DownloadConcurrency).coerceIn(1, 10)

    private fun pump() {
        if (!mutex.tryLock()) return
        try {
            while (activeJobs.size < maxConcurrent()) {
                val next = items.firstOrNull {
                    it.status == DownloadStatus.QUEUED && !activeJobs.containsKey(it.id)
                } ?: break
                start(next)
            }
            if (items.none { it.isActive } && activeJobs.isEmpty()) {
                notifier.clearActive()
                DownloadService.stop(appContext)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun start(item: DownloadItem) {
        if (PrefManager.getVal<Boolean>(PrefName.DownloadOnlyOverWifi) && !isOnWifi()) {
            item.status = DownloadStatus.PAUSED
            item.error = "Waiting for Wi-Fi"
            persist()
            publish()
            return
        }
        DownloadService.start(appContext)

        item.status = DownloadStatus.PREPARING
        item.error = null
        persist()
        publish()
        val job = scope.launch(start = CoroutineStart.LAZY) { run(item) }
        activeJobs[item.id] = job
        job.start()
    }

    private suspend fun run(item: DownloadItem) {
        publish()
        var temp: File? = null
        try {
            var lastPublish = 0L
            var speedTime = System.currentTimeMillis()
            var speedBytes = 0L
            var progressLogged = false

            try {
                DownloadMetadata.prepare(appContext, item)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (items.none { it.id == item.id } || item.status != DownloadStatus.PREPARING) {
                throw DownloadCancelledException()
            }

            item.status = DownloadStatus.DOWNLOADING
            publish()
            var attempt = 0
            while (true) {
                try {
                    temp = downloader.download(
                        item,
                        onProgress = { done, total, fraction ->
                    item.downloadedBytes = done

                    if (total > 0L) item.totalBytes = total
                    item.progress = if (item.totalBytes > 0L) {
                        (done.toDouble() / item.totalBytes).toFloat().coerceIn(0f, 1f)
                    } else if (fraction.isFinite() && fraction in 0f..1f) {
                        fraction
                    } else {
                        item.progress
                    }
                    if (!progressLogged) {
                        if (item.progress > 0f) {
                            progressLogged = true
                            Log.i(
                                "AnimeDownload",
                                "progress ${item.id}: ${(item.progress * 100).toInt()}% " +
                                    "source(total=$total fraction=$fraction done=$done)",
                            )
                        } else if (done > 1_000_000L) {
                            progressLogged = true
                            Log.i(
                                "AnimeDownload",
                                "progress ${item.id}: STUCK at 0% — no total, no fraction. " +
                                    "total=$total fraction=$fraction estimate=${item.totalBytes} done=$done",
                            )
                        }
                    }
                    val now = System.currentTimeMillis()
                    val dt = now - speedTime
                    if (dt >= 500L) {
                        item.speed = ((done - speedBytes) * 1000L / dt).coerceAtLeast(0L)
                        speedTime = now
                        speedBytes = done
                    }
                    if (now - lastPublish > 300L) {
                        lastPublish = now
                        publish()
                        notifier.progress(item)
                    }
                },
                isActive = { item.status == DownloadStatus.DOWNLOADING },
            )
            break
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            attempt++
            if (attempt > MAX_RETRIES) throw e
            item.status = DownloadStatus.RETRYING
            item.error = e.message
            persist()
            publish()
            Log.w("AnimeDownload", "retry $attempt for ${item.id}: ${item.error}")
            delay(RETRY_DELAY_MS)
        }
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (items.none { it.id == item.id } || item.status != DownloadStatus.DOWNLOADING) {
                throw DownloadCancelledException()
            }
            val isAdaptive = item.videoType == ani.sanin.parsers.VideoType.M3U8 ||
                item.videoType == ani.sanin.parsers.VideoType.DASH
            val size = if (isAdaptive) item.downloadedBytes else temp.length()
            if (!isAdaptive && size <= 0L) throw DownloadException("Downloaded file is empty")
            val locator = if (isAdaptive) {
                DownloadHlsCache.locator(item.id)
            } else {
                DownloadStorageHelper.commit(appContext, item, temp)
                    ?: throw DownloadException("Could not save file")
            }

            item.progress = 1f
            item.totalBytes = size
            item.downloadedBytes = size
            item.status = DownloadStatus.FINISHED
            addCompleted(item, locator, size)
            items.removeAll { it.id == item.id }
            persist()
            publish()
            notifier.completed(item)
            Log.i("AnimeDownload", "saved ${item.mediaName} ep${item.episodeNumber} ($size bytes) -> $locator")
        } catch (e: DownloadCancelledException) {
            if (items.none { it.id == item.id }) {
                Downloader.cleanup(appContext, item)
            }
            if (items.any { it.id == item.id }) {
                item.status = DownloadStatus.PAUSED
                persist()
                publish()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            item.status = DownloadStatus.ERROR
            item.error = e.message ?: "Download failed"
            persist()
            publish()
            notifier.error(item, item.error)
            Log.e("AnimeDownload", "failed ${item.mediaName} ep${item.episodeNumber}: ${item.error}", e)
        } finally {
            val runningJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            if (activeJobs[item.id] === runningJob) activeJobs.remove(item.id)
            publish()
            pump()
        }
    }

    private fun addCompleted(item: DownloadItem, path: String, size: Long) {
        completedItems.removeAll { it.id == item.id }
        completedItems.add(
            0,
            DownloadedItem(
                id = item.id,
                mediaId = item.mediaId,
                mediaName = item.mediaName,
                cover = item.cover,
                thumbnail = item.thumbnail,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                path = path,
                sizeBytes = size,
                timestamp = System.currentTimeMillis(),
                synopsis = item.synopsis,
                genres = item.genres,
                thumbPath = item.thumbPath,
                logoPath = item.logoPath,
                videoType = item.videoType,
                sourceUrl = if (item.videoType == ani.sanin.parsers.VideoType.M3U8 ||
                    item.videoType == ani.sanin.parsers.VideoType.DASH) item.url else null,
                playbackUrl = if (item.videoType == ani.sanin.parsers.VideoType.M3U8 ||
                    item.videoType == ani.sanin.parsers.VideoType.DASH) item.offlinePlaybackUrl else null,
                headers = if (item.videoType == ani.sanin.parsers.VideoType.M3U8 ||
                    item.videoType == ani.sanin.parsers.VideoType.DASH) item.headers else emptyMap(),
            ),
        )
        persistCompleted()
        publishCompleted()
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun publish() {
        _queue.value = items.map { it.copy() }
        val now = System.currentTimeMillis()
        if (now - lastPersist > 1500L) {
            lastPersist = now
            persist()
        }
    }

    private fun publishCompleted() {
        val list = completedItems.toList()
        Log.i("AnimeDownload", "publishCompleted: size=${list.size}")
        _completed.value = list
    }

    private fun persist() = DownloadStore.saveQueue(items)

    private fun persistCompleted() = DownloadStore.saveCompleted(completedItems)
}