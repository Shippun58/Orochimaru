package ani.sanin.download

import android.content.Context
import android.util.Log
import ani.sanin.parsers.VideoType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DownloadException(message: String) : Exception(message)
class DownloadCancelledException : Exception("cancelled")

class Downloader(
    private val context: android.content.Context,
    private val client: OkHttpClient,
) {

    suspend fun download(
        item: DownloadItem,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ): File {
        val temp = DownloadStorageHelper.tempFile(context, item)
        Log.i(TAG, "download ${item.videoType} url=${item.url} -> ${temp.absolutePath}")
        try {
            when (item.videoType) {
                VideoType.M3U8, VideoType.DASH -> downloadHls(item, onProgress, isActive)
                else -> downloadDirect(item, temp, onProgress, isActive)
            }
            Log.i(TAG, "download finished url=${item.url} size=${temp.length()}")
            return temp
        } catch (error: Throwable) {

            throw error
        }
    }

    private suspend fun downloadDirect(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val existingBytes = temp.takeIf { it.exists() }?.length() ?: 0L
        val probedTotal = resolveLength(item)
        val total = probedTotal.takeIf { it in 1 until MAX_DOWNLOAD_BYTES } ?: 0L
        val segments = PrefManager.getVal<Int>(PrefName.DownloadSegments).coerceIn(1, 8)

        if (existingBytes == 0L && total >= MIN_SEGMENT_SIZE && segments > 1 && isActive() &&
            supportsRanges(item, total)
        ) {
            Log.i(TAG, "trying $segments verified range segments for ${item.id}, total=$total")
            try {
                downloadSegments(item, temp, total, segments, onProgress, isActive)
                return@withContext
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "segmented download failed; restarting as a single stream: ${e.message}")
                temp.delete()
                for (part in 0 until 8) File("${temp.path}.part$part").delete()
            }
        }
        downloadSingle(item, temp, total, onProgress, isActive)
    }

    private suspend fun downloadSingle(
        item: DownloadItem,
        temp: File,
        knownTotal: Long,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        var offset = temp.takeIf { it.exists() }?.length() ?: 0L
        val builder = Request.Builder().url(item.url).apply {
            item.headers.forEach { (k, v) -> addHeader(k, v) }
        }
        if (offset > 0L) builder.header("Range", "bytes=$offset-")
        executeCancellable(builder.build()).use { res ->
                val range = parseContentRange(res.header("Content-Range"))
                if (offset > 0L && res.code == 206) {
                    if (range == null || range.first != offset ||
                        (knownTotal > 0L && range.total != knownTotal)
                    ) {
                        temp.delete()
                        throw DownloadException("Invalid resume Content-Range for byte $offset")
                    }
                } else if (res.code == 200) {

                    offset = 0L
                } else if (!res.isSuccessful) {
                    throw DownloadException("HTTP ${res.code} for ${item.url}")
                } else if (offset > 0L && res.code != 206) {
                    temp.delete()
                    throw DownloadException("Server did not honour resume Range (HTTP ${res.code})")
                } else if (res.code == 206 && range == null) {
                    throw DownloadException("Invalid Content-Range on partial response")
                }

                val body = res.body ?: throw DownloadException("Empty response body")
                val actualTotal = range?.total?.takeIf { it > 0L }
                    ?: knownTotal.takeIf { it > 0L }
                    ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                    ?: 0L
                body.byteStream().use { input ->
                    temp.parentFile?.mkdirs()
                    java.io.FileOutputStream(temp, offset > 0L).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = offset
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!isActive()) throw DownloadCancelledException()
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, actualTotal, -1f)
                        }
                        output.fd.sync()
                    }
                }
                val finalLength = temp.length()
                if (actualTotal > 0L && finalLength != actualTotal) {
                    throw DownloadException("Incomplete download: $finalLength of $actualTotal bytes")
                }
                if (finalLength <= 0L) throw DownloadException("Downloaded file is empty")
        }
    }

    private suspend fun downloadSegments(
        item: DownloadItem,
        temp: File,
        total: Long,
        segments: Int,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val chunkSize = total / segments
        val doneTotal = AtomicLong(0L)
        val remaining = AtomicInteger(segments)
        val failure = AtomicReference<Throwable?>()

        fun partFile(i: Int) = File(temp.path + ".part$i")
        fun cleanup() {
            for (i in 0 until segments) {
                partFile(i).takeIf { it.exists() }?.delete()
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = (0 until segments).map { i ->
            val start = chunkSize * i
            val end = if (i == segments - 1) total - 1 else start + chunkSize - 1
            scope.launch {
                val part = partFile(i)
                try {
                    if (!isActive()) throw DownloadCancelledException()
                    val rangeRequest = Request.Builder().url(item.url)
                        .apply { item.headers.forEach { (k, v) -> addHeader(k, v) } }
                        .header("Range", "bytes=$start-$end")
                        .build()
                    executeCancellable(rangeRequest).use { res ->
                        val range = parseContentRange(res.header("Content-Range"))
                        if (res.code != 206 || range == null || range.first != start ||
                            range.last != end || range.total != total
                        ) {
                            throw DownloadException(
                                "Invalid range response: HTTP ${res.code}, " +
                                    "Content-Range=${res.header("Content-Range")}",
                            )
                        }
                        val rangeBody = res.body
                            ?: throw DownloadException("Empty range response body")
                        if (rangeBody.contentLength() >= 0L &&
                            rangeBody.contentLength() != end - start + 1L
                        ) throw DownloadException("Wrong range body length for segment $i")
                        rangeBody.byteStream().use { input ->
                            RandomAccessFile(part, "rw").use { raf ->
                                raf.setLength(0L)
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (!isActive()) throw DownloadCancelledException()
                                    raf.write(buffer, 0, read)
                                    doneTotal.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                    if (part.length() != end - start + 1) {
                        throw DownloadException(
                            "segment $i short: ${part.length()} != ${end - start + 1}",
                        )
                    }
                } catch (e: Throwable) {

                    val wrapped = if (!isActive() && e !is DownloadCancelledException) {
                        DownloadCancelledException()
                    } else {
                        e
                    }
                    failure.compareAndSet(null, wrapped)
                } finally {
                    remaining.decrementAndGet()
                }
            }
        }

        try {
            while (remaining.get() > 0 && failure.get() == null) {
                if (!isActive()) jobs.forEach { it.cancel() }
                onProgress(doneTotal.get(), total, -1f)
                delay(100)
            }
        } finally {
            scope.cancel()
            jobs.joinAll()
        }

        val err = failure.get()
        if (err != null) {
            cleanup()
            throw err
        }

        temp.outputStream().use { out ->
            for (i in 0 until segments) {
                partFile(i).inputStream().use { input -> input.copyTo(out) }
            }
        }
        cleanup()
        if (temp.length() != total) {
            val actual = temp.length()
            temp.delete()
            throw DownloadException("Merged file length $actual != expected $total")
        }
        onProgress(total, total, -1f)
        Log.i(TAG, "segmented download done: $segments ranges -> ${temp.length()} bytes")
    }

    private data class ParsedRange(val first: Long, val last: Long, val total: Long)

    private fun parseContentRange(value: String?): ParsedRange? {
        val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (first < 0L || last < first || total <= last) return null
        return ParsedRange(first, last, total)
    }

    private suspend fun resolveLength(item: DownloadItem): Long {
        val head = probeLength(item, head = true)
        if (head > 0L) return head
        return probeLength(item, head = false)
    }

    private suspend fun probeLength(item: DownloadItem, head: Boolean): Long {
        val builder = Request.Builder().url(item.url)
        item.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (head) builder.head() else builder.get().header("Range", "bytes=0-0")
        return try {
            executeCancellable(builder.build()).use { res ->
                if (head) {
                    if (!res.isSuccessful) return -1L
                    res.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
                } else {
                    val range = parseContentRange(res.header("Content-Range"))
                    if (res.code == 206 && range != null && range.first == 0L &&
                        range.last == 0L
                    ) range.total else -1L
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            -1L
        }
    }

    private suspend fun supportsRanges(item: DownloadItem, total: Long): Boolean {
        val builder = Request.Builder().url(item.url).get().header("Range", "bytes=0-0")
        item.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return try {
            executeCancellable(builder.build()).use { res ->
                val range = parseContentRange(res.header("Content-Range"))
                res.code == 206 && range != null && range.first == 0L &&
                    range.last == 0L && range.total == total &&
                    (res.body?.contentLength() ?: -1L).let { it == -1L || it == 1L }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val token = continuation.tryResumeWithException(e)
                    if (token != null) continuation.completeResume(token)
                }

                override fun onResponse(call: Call, response: Response) {
                    val token = continuation.tryResume(response)
                    if (token != null) continuation.completeResume(token)
                    else response.close()
                }
            })
        }

    private suspend fun downloadHls(
        item: DownloadItem,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = HlsRemuxer.download(context, item, client, onProgress, isActive)

    companion object {
        private const val TAG = "AnimeDownload"
        private const val MIN_SEGMENT_SIZE = 1024L * 1024L
        private const val MAX_DOWNLOAD_BYTES = 21474836480L
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

        fun cleanup(context: Context, item: DownloadItem) {
            val temp = DownloadStorageHelper.tempFile(context, item)
            temp.delete()
            for (part in 0 until 8) File("${temp.path}.part$part").delete()
        }

        fun defaultClient(): OkHttpClient {

            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            }
            return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        }
    }
}