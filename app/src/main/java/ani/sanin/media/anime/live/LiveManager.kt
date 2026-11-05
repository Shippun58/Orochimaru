package ani.sanin.media.anime.live

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.ref.WeakReference

const val LIVE_MARGIN = 6_000L

const val PREFERRED_LIVE_OFFSET = 5_000L

const val CHUNK_VARIANCE = 3000L

class LivestreamChunk(
    durationMs: Long, val receiveTimeMs: Long = System.currentTimeMillis()
) {

    val targetPosition = maxOf(0,minOf(
        durationMs - PREFERRED_LIVE_OFFSET,
        durationMs / 2 - CHUNK_VARIANCE
    ))

    fun isPositionLive(position: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val livePosition = targetPosition + (currentTime - receiveTimeMs)
        val withinLive = position + LIVE_MARGIN > livePosition - PREFERRED_LIVE_OFFSET

        return withinLive
    }

    fun getTimeAheadOfLive(position: Long): Long {
        val currentTime = System.currentTimeMillis()
        val livePosition = targetPosition + (currentTime - receiveTimeMs)

        return position - livePosition
    }
}

class LiveManager {
    private var _currentPlayer: WeakReference<Player>? = null
    val currentPlayer: Player? get() = _currentPlayer?.get()

    constructor(player: Player?) {
        _currentPlayer = WeakReference(player)
    }

    private var lastLivestreamChunk: LivestreamChunk? = null

    fun submitLivestreamChunk(chunk: LivestreamChunk) {
        lastLivestreamChunk = chunk
    }

    fun getTimeAheadOfLive(position: Long): Long {
        val player = currentPlayer ?: return 0
        if (!player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) return 0

        val ahead = if (player.currentLiveOffset != C.TIME_UNSET && player.currentLiveOffset < player.duration) {
            val relativeOffset = player.currentLiveOffset - player.currentPosition + position
            PREFERRED_LIVE_OFFSET - relativeOffset
        } else {
            lastLivestreamChunk?.getTimeAheadOfLive(position) ?: 0
        }

        return maxOf(0, ahead)
    }

    fun isAtLiveEdge(): Boolean {
        val player = currentPlayer ?: return false
        if (!player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) return false

        return if (player.currentLiveOffset != C.TIME_UNSET && player.currentLiveOffset < player.duration) {
            player.currentLiveOffset < LIVE_MARGIN + PREFERRED_LIVE_OFFSET
        } else {
            lastLivestreamChunk?.isPositionLive(player.currentPosition) == true
        }
    }
}