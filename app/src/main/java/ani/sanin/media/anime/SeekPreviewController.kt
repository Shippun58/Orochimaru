package ani.sanin.media.anime

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * Extracts seek preview frames with Media3's FrameExtractor.
 *
 * Everything is kept in memory: frames are cached in a small LruCache and the extractor is
 * released as soon as the player source changes or the player goes away. No preview data is
 * written to disk.
 */
@UnstableApi
class SeekPreviewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private var extractor: FrameExtractor? = null
    private var mediaItem: MediaItem? = null
    private var mediaSourceFactory: MediaSource.Factory? = null
    private var durationMs = 0L
    private var enabled = false
    private var closed = false

    private var debounceRunnable: Runnable? = null
    private var pendingFuture: ListenableFuture<FrameExtractor.Frame>? = null
    private var pendingBucket = NO_BUCKET
    private var requestGeneration = 0

    private var preloadStartRunnable: Runnable? = null
    private var preloading = false
    private var preloadIndex = 0
    private var preloadGeneration = 0
    private var preloadCompleted = false
    private var preloadAttempted = false

    fun configure(
        enabled: Boolean,
        preload: Boolean,
        mediaItem: MediaItem?,
        mediaSourceFactory: MediaSource.Factory?,
        durationMs: Long,
        allowPreload: Boolean,
    ) {
        if (closed) return

        val sourceChanged = mediaSourceFactory !== this.mediaSourceFactory
        val itemChanged =
            mediaItem?.localConfiguration?.uri?.toString() !=
                this.mediaItem?.localConfiguration?.uri?.toString()

        this.mediaItem = mediaItem
        this.mediaSourceFactory = mediaSourceFactory
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.enabled = enabled && mediaItem != null && mediaSourceFactory != null

        if (itemChanged) {
            cancelPreload()
            releaseExtractor()
            cache.evictAll()
            preloadCompleted = false
            preloadAttempted = false
        } else if (sourceChanged) {
            releaseExtractor()
        }

        if (!this.enabled) {
            cancelPreload()
            releaseExtractor()
            cache.evictAll()
            return
        }

        if (preload && allowPreload && !preloadCompleted && !preloadAttempted) {
            schedulePreload()
        } else if (!preload || !allowPreload) {
            cancelPreload()
        }
    }

    fun request(positionMs: Long, callback: (positionMs: Long, bitmap: Bitmap) -> Unit) {
        if (!enabled || closed) return

        val bucket = bucketOf(positionMs)
        cache.get(bucket)?.let {
            callback(bucket, it)
            return
        }

        cancelPreload()

        if (pendingBucket == bucket && (pendingFuture != null || debounceRunnable != null)) {
            return
        }

        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            debounceRunnable = null
            dispatchRequest(bucket, callback)
        }
        debounceRunnable = runnable
        mainHandler.postDelayed(runnable, REQUEST_DEBOUNCE_MS)
    }

    fun clear() {
        enabled = false
        cancelPreload()
        releaseExtractor()
        cache.evictAll()
        preloadCompleted = false
        preloadAttempted = false
    }

    fun close() {
        if (closed) return
        closed = true
        clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun dispatchRequest(bucket: Long, callback: (Long, Bitmap) -> Unit) {
        if (!enabled || closed) return

        val activeExtractor = extractor ?: buildExtractor() ?: return
        pendingFuture?.cancel(true)
        pendingFuture = null
        pendingBucket = bucket
        val generation = ++requestGeneration

        val future =
            try {
                activeExtractor.getFrame(bucket)
            } catch (_: Throwable) {
                return
            }

        pendingFuture = future
        future.addListener(
            {
                mainHandler.post {
                    if (closed || generation != requestGeneration) return@post
                    val frame = runCatching { future.get() }.getOrNull() ?: return@post
                    val bitmap = frame.bitmap
                    if (bitmap.isRecycled) return@post
                    cache.put(bucket, bitmap)
                    callback(bucket, bitmap)
                }
            },
            DIRECT_EXECUTOR,
        )
    }

    private fun buildExtractor(): FrameExtractor? {
        extractor?.let { return it }

        val item = mediaItem ?: return null
        val sourceFactory = mediaSourceFactory ?: return null

        return try {
            FrameExtractor
                .Builder(appContext, item)
                .setMediaSourceFactory(sourceFactory)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setEffects(listOf<Effect>(Presentation.createForHeight(PREVIEW_HEIGHT_PX)))
                .build()
                .also { extractor = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun releaseExtractor() {
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null
        pendingFuture?.cancel(true)
        pendingFuture = null
        pendingBucket = NO_BUCKET
        requestGeneration++
        runCatching { extractor?.close() }
        extractor = null
    }

    private fun schedulePreload() {
        if (preloadStartRunnable != null || preloading || preloadCompleted || preloadAttempted) return

        val runnable = Runnable {
            preloadStartRunnable = null
            beginPreload()
        }
        preloadStartRunnable = runnable
        mainHandler.postDelayed(runnable, PRELOAD_DELAY_MS)
    }

    private fun beginPreload() {
        if (!enabled || closed || preloadCompleted || durationMs < MIN_PRELOAD_DURATION_MS) return
        preloadAttempted = true
        preloading = true
        preloadIndex = 0
        stepPreload(++preloadGeneration)
    }

    private fun stepPreload(generation: Int) {
        if (!preloading || generation != preloadGeneration || !enabled || closed) return

        if (preloadIndex >= PRELOAD_FRAME_COUNT) {
            preloading = false
            preloadCompleted = true
            return
        }

        val positionMs =
            ((preloadIndex + 0.5f) / PRELOAD_FRAME_COUNT * durationMs)
                .toLong()
                .coerceAtLeast(0L)
        preloadIndex++

        val bucket = bucketOf(positionMs)
        if (cache.get(bucket) != null) {
            stepPreload(generation)
            return
        }

        val activeExtractor = extractor ?: buildExtractor() ?: run {
            preloading = false
            return
        }

        val future =
            try {
                activeExtractor.getFrame(bucket)
            } catch (_: Throwable) {
                preloading = false
                return
            }

        future.addListener(
            {
                mainHandler.post {
                    if (generation != preloadGeneration) return@post
                    val frame = runCatching { future.get() }.getOrNull()
                    val bitmap = frame?.bitmap
                    if (bitmap != null && !bitmap.isRecycled) {
                        cache.put(bucket, bitmap)
                    }
                    stepPreload(generation)
                }
            },
            DIRECT_EXECUTOR,
        )
    }

    private fun cancelPreload() {
        preloadStartRunnable?.let { mainHandler.removeCallbacks(it) }
        preloadStartRunnable = null
        preloading = false
        preloadGeneration++
    }

    private fun bucketOf(positionMs: Long): Long {
        val bucket = (positionMs / BUCKET_SIZE_MS) * BUCKET_SIZE_MS
        val maxBucket = max(0L, durationMs - BUCKET_SIZE_MS)
        return bucket.coerceIn(0L, maxBucket)
    }

    private companion object {
        private const val BUCKET_SIZE_MS = 1_000L
        private const val REQUEST_DEBOUNCE_MS = 120L
        private const val PRELOAD_DELAY_MS = 8_000L
        private const val PRELOAD_FRAME_COUNT = 16
        private const val MIN_PRELOAD_DURATION_MS = 5_000L
        private const val PREVIEW_HEIGHT_PX = 180
        private const val CACHE_BYTES = 12 * 1024 * 1024
        private const val NO_BUCKET = Long.MIN_VALUE
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
