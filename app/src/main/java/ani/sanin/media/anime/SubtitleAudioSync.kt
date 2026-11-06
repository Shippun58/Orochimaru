package ani.sanin.media.anime

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

data class SpeechSegment(val startMs: Long, val endMs: Long)

object SubtitleAudioSync {
    private const val MAX_OFFSET_MS = 10 * 60_000L
    private const val COARSE_STEP_MS = 200L
    private const val REFINE_RADIUS_MS = 300L
    private const val REFINE_STEP_MS = 10L
    private const val ONSET_TOLERANCE_MS = 200L
    private const val PRESENCE_TOLERANCE_MS = 350L
    private const val MIN_CAPTURE_WINDOW_MS = 120_000L
    private const val AFFINE_MIN_CAPTURE_WINDOW_MS = 180_000L
    private const val MIN_SPEECH_MS = 25_000L
    private const val MIN_CUES = 10
    private const val MIN_SEGMENTS = 10
    private const val MIN_CONSIDERED_CUES = 30
    private const val MIN_ONSET_SHARE = 0.45
    private const val MIN_PRESENCE_SHARE = 0.75
    private const val MIN_SCORE_MARGIN = 0.16
    private const val MIN_APPLIED_OFFSET_MS = 250L
    private const val MIN_HALF_CUES = 8
    private const val MIN_HALF_ONSET_SHARE = 0.30
    private const val MIN_HALF_PRESENCE_SHARE = 0.55
    private const val MIN_RATE_DELTA = 0.0015
    private const val AFFINE_MIN_RATE = 0.95
    private const val AFFINE_MAX_RATE = 1.05
    private const val AFFINE_COARSE_RATE_STEPS = 20
    private const val AFFINE_COARSE_OFFSET_STEP_MS = 500L
    private const val AFFINE_REFINE_RADIUS_MS = 500L
    private const val AFFINE_REFINE_OFFSET_STEP_MS = 20L
    private const val AFFINE_REFINE_RATE_RADIUS = 0.005
    private const val AFFINE_REFINE_RATE_STEP = 0.001
    private const val MERGE_GAP_MS = 250L
    private const val MIN_SEGMENT_MS = 120L

    data class Result(
        val offsetMs: Long,
        val rate: Double,
        val onsetShare: Double,
        val presenceShare: Double,
        val consideredCues: Int,
    ) {
        val score: Double
            get() = onsetShare + presenceShare * 0.5
    }

    private data class Prepared(
        val segments: List<SpeechSegment>,
        val onsets: LongArray,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val cueStarts: LongArray,
    )

    private data class Candidate(
        val offsetMs: Long,
        val considered: Int,
        val onsetMatches: Int,
        val presenceMatches: Int,
    ) {
        val onsetShare: Double
            get() = if (considered == 0) 0.0 else onsetMatches.toDouble() / considered
        val presenceShare: Double
            get() = if (considered == 0) 0.0 else presenceMatches.toDouble() / considered
        val score: Double
            get() = onsetShare + presenceShare * 0.5
    }

    private data class AffineCandidate(
        val rate: Double,
        val scaledStarts: LongArray,
        val candidate: Candidate,
    )

    fun correlate(cues: List<SyncCue>, rawSegments: List<SpeechSegment>): Result? {
        val prepared = prepare(cues, rawSegments) ?: return null

        val coarseCandidates = ArrayList<Candidate>(4096)
        var offset = -MAX_OFFSET_MS
        while (offset <= MAX_OFFSET_MS) {
            val candidate = evaluate(
                prepared.cueStarts, prepared.segments, prepared.onsets,
                prepared.windowStartMs, prepared.windowEndMs, offset,
            )
            if (candidate.considered >= MIN_CONSIDERED_CUES) coarseCandidates.add(candidate)
            offset += COARSE_STEP_MS
        }
        if (coarseCandidates.isEmpty()) return null
        val coarseBest = coarseCandidates.maxByOrNull { it.score } ?: return null
        if (coarseBest.onsetShare < MIN_ONSET_SHARE ||
            coarseBest.presenceShare < MIN_PRESENCE_SHARE
        ) {
            return null
        }

        var best = coarseBest
        var fineOffset = coarseBest.offsetMs - REFINE_RADIUS_MS
        while (fineOffset <= coarseBest.offsetMs + REFINE_RADIUS_MS) {
            val candidate = evaluate(
                prepared.cueStarts, prepared.segments, prepared.onsets,
                prepared.windowStartMs, prepared.windowEndMs, fineOffset,
            )
            if (candidate.considered >= MIN_CONSIDERED_CUES && candidate.score > best.score) {
                best = candidate
            }
            fineOffset += REFINE_STEP_MS
        }

        val runnerUp = coarseCandidates
            .asSequence()
            .filter { abs(it.offsetMs - best.offsetMs) > 2_000L }
            .maxOfOrNull { it.score }
        if (runnerUp != null && best.score - runnerUp < MIN_SCORE_MARGIN) return null
        if (best.onsetShare < MIN_ONSET_SHARE ||
            best.presenceShare < MIN_PRESENCE_SHARE
        ) {
            return null
        }
        if (!passesHalfCheck(prepared.cueStarts, prepared, best.offsetMs)) return null
        if (shiftedFirstCue(prepared.cueStarts, best.offsetMs) < -5_000L) return null

        return Result(
            offsetMs = appliedOffset(best.offsetMs),
            rate = 1.0,
            onsetShare = best.onsetShare,
            presenceShare = best.presenceShare,
            consideredCues = best.considered,
        )
    }

    fun correlateAffine(cues: List<SyncCue>, rawSegments: List<SpeechSegment>): Result? {
        val prepared = prepare(cues, rawSegments) ?: return null
        if (prepared.windowEndMs - prepared.windowStartMs < AFFINE_MIN_CAPTURE_WINDOW_MS) {
            return null
        }

        var best: AffineCandidate? = null
        for (step in 0..AFFINE_COARSE_RATE_STEPS) {
            val rate = AFFINE_MIN_RATE +
                (AFFINE_MAX_RATE - AFFINE_MIN_RATE) * step / AFFINE_COARSE_RATE_STEPS
            val scaledStarts = scaleCueStarts(prepared.cueStarts, rate)
            var offset = -MAX_OFFSET_MS
            while (offset <= MAX_OFFSET_MS) {
                val candidate = evaluate(
                    scaledStarts, prepared.segments, prepared.onsets,
                    prepared.windowStartMs, prepared.windowEndMs, offset,
                )
                if (candidate.considered >= MIN_CONSIDERED_CUES) {
                    val affine = AffineCandidate(rate, scaledStarts, candidate)
                    if (best == null || affine.candidate.score > best.candidate.score) {
                        best = affine
                    }
                }
                offset += AFFINE_COARSE_OFFSET_STEP_MS
            }
        }
        var refined = best ?: return null
        val refineCenterRate = refined.rate
        val refineCenterOffset = refined.candidate.offsetMs

        var fineRate = refineCenterRate - AFFINE_REFINE_RATE_RADIUS
        while (fineRate <= refineCenterRate + AFFINE_REFINE_RATE_RADIUS + 1e-9) {
            val scaledStarts = scaleCueStarts(prepared.cueStarts, fineRate)
            var fineOffset = refineCenterOffset - AFFINE_REFINE_RADIUS_MS
            while (fineOffset <= refineCenterOffset + AFFINE_REFINE_RADIUS_MS) {
                val candidate = evaluate(
                    scaledStarts, prepared.segments, prepared.onsets,
                    prepared.windowStartMs, prepared.windowEndMs, fineOffset,
                )
                if (candidate.considered >= MIN_CONSIDERED_CUES &&
                    candidate.score > refined.candidate.score
                ) {
                    refined = AffineCandidate(fineRate, scaledStarts, candidate)
                }
                fineOffset += AFFINE_REFINE_OFFSET_STEP_MS
            }
            fineRate += AFFINE_REFINE_RATE_STEP
        }

        val bestCandidate = refined.candidate
        if (bestCandidate.onsetShare < MIN_ONSET_SHARE ||
            bestCandidate.presenceShare < MIN_PRESENCE_SHARE
        ) {
            return null
        }
        if (!passesHalfCheck(refined.scaledStarts, prepared, bestCandidate.offsetMs)) return null
        if (shiftedFirstCue(
                prepared.cueStarts,
                bestCandidate.offsetMs,
                refined.rate,
            ) < -5_000L
        ) {
            return null
        }

        val finalRate = if (abs(refined.rate - 1.0) < MIN_RATE_DELTA) 1.0 else refined.rate
        return Result(
            offsetMs = appliedOffset(bestCandidate.offsetMs),
            rate = finalRate,
            onsetShare = bestCandidate.onsetShare,
            presenceShare = bestCandidate.presenceShare,
            consideredCues = bestCandidate.considered,
        )
    }

    private fun prepare(cues: List<SyncCue>, rawSegments: List<SpeechSegment>): Prepared? {
        if (cues.size < MIN_CUES || rawSegments.size < MIN_SEGMENTS) return null
        val segments = merge(rawSegments)
        if (segments.size < MIN_SEGMENTS) return null
        val totalSpeech = segments.sumOf { it.endMs - it.startMs }
        if (totalSpeech < MIN_SPEECH_MS) return null
        val windowStartMs = segments.first().startMs
        val windowEndMs = segments.last().endMs
        if (windowEndMs - windowStartMs < MIN_CAPTURE_WINDOW_MS) return null

        val onsets = LongArray(segments.size) { segments[it].startMs }
        val usableCues = cues
            .filter { it.startTimeMs >= 0L && it.durationMs > 0L }
            .sortedBy { it.startTimeMs }
        if (usableCues.size < MIN_CUES) return null
        val cueStarts = LongArray(usableCues.size) { usableCues[it].startTimeMs }
        return Prepared(segments, onsets, windowStartMs, windowEndMs, cueStarts)
    }

    private fun scaleCueStarts(starts: LongArray, rate: Double): LongArray {
        return LongArray(starts.size) { (starts[it] * rate).roundToLong() }
    }

    private fun shiftedFirstCue(cueStarts: LongArray, offsetMs: Long, rate: Double = 1.0): Long {
        if (cueStarts.isEmpty()) return Long.MAX_VALUE
        return (cueStarts.first() * rate).roundToLong() + offsetMs
    }

    private fun appliedOffset(offsetMs: Long): Long {
        return if (abs(offsetMs) < MIN_APPLIED_OFFSET_MS) 0L else offsetMs
    }

    private fun passesHalfCheck(cueStarts: LongArray, prepared: Prepared, offsetMs: Long): Boolean {
        val mid = (prepared.windowStartMs + prepared.windowEndMs) / 2
        return halfPasses(cueStarts, prepared, prepared.windowStartMs, mid, offsetMs) &&
            halfPasses(cueStarts, prepared, mid, prepared.windowEndMs, offsetMs)
    }

    private fun halfPasses(
        cueStarts: LongArray,
        prepared: Prepared,
        startMs: Long,
        endMs: Long,
        offsetMs: Long,
    ): Boolean {
        val candidate = evaluate(
            cueStarts, prepared.segments, prepared.onsets, startMs, endMs, offsetMs,
        )
        if (candidate.considered < MIN_HALF_CUES) return true
        return candidate.onsetShare >= MIN_HALF_ONSET_SHARE &&
            candidate.presenceShare >= MIN_HALF_PRESENCE_SHARE
    }

    private fun merge(raw: List<SpeechSegment>): List<SpeechSegment> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.startMs }
        val merged = ArrayList<SpeechSegment>(sorted.size)
        var start = sorted[0].startMs
        var end = sorted[0].endMs
        for (i in 1 until sorted.size) {
            val segment = sorted[i]
            if (segment.startMs - end <= MERGE_GAP_MS) {
                if (segment.endMs > end) end = segment.endMs
            } else {
                if (end - start >= MIN_SEGMENT_MS) merged.add(SpeechSegment(start, end))
                start = segment.startMs
                end = segment.endMs
            }
        }
        if (end - start >= MIN_SEGMENT_MS) merged.add(SpeechSegment(start, end))
        return merged
    }

    private fun evaluate(
        cueStarts: LongArray,
        segments: List<SpeechSegment>,
        onsets: LongArray,
        windowStartMs: Long,
        windowEndMs: Long,
        offsetMs: Long,
    ): Candidate {
        val lowerTime = windowStartMs - 2_000L - offsetMs
        val upperTime = windowEndMs + 2_000L - offsetMs
        var index = lowerBound(cueStarts, lowerTime)
        val end = upperBound(cueStarts, upperTime)
        var considered = 0
        var onsetMatches = 0
        var presenceMatches = 0
        while (index < end) {
            val shiftedStart = cueStarts[index] + offsetMs
            considered++
            if (hasOnsetNear(onsets, shiftedStart)) onsetMatches++
            if (hasSpeechNear(segments, shiftedStart)) presenceMatches++
            index++
        }
        return Candidate(offsetMs, considered, onsetMatches, presenceMatches)
    }

    private fun lowerBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= target) low = mid + 1 else high = mid
        }
        return low
    }

    private fun hasOnsetNear(onsets: LongArray, timeMs: Long): Boolean {
        val target = timeMs - ONSET_TOLERANCE_MS
        var low = 0
        var high = onsets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (onsets[mid] < target) low = mid + 1 else high = mid
        }
        return low < onsets.size && onsets[low] <= timeMs + ONSET_TOLERANCE_MS
    }

    private fun hasSpeechNear(segments: List<SpeechSegment>, timeMs: Long): Boolean {
        val target = timeMs + PRESENCE_TOLERANCE_MS
        var low = 0
        var high = segments.size - 1
        var index = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (segments[mid].startMs <= target) {
                index = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (index < 0) return false
        return segments[index].endMs >= timeMs - PRESENCE_TOLERANCE_MS
    }
}

@UnstableApi
class AudioSpeechRecorder {
    private val lock = Any()
    private val segments = ArrayList<SpeechSegment>()
    private var speechRunStartMs = -1L
    private var speechRunEndMs = -1L
    private var silenceRunMs = 0L

    @Volatile private var enabled = false
    @Volatile private var released = false
    @Volatile private var rebasePending = true

    private var inputRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var streamAnchorMs = -1L
    private var baseAnchorMs = -1L
    private var samplesConsumed = 0L
    private var baseSampleIndex = 0L
    private var nextOutputPosition = 0.0
    private var previousSampleA = 0f
    private var previousSampleB = 0f
    private var hasPreviousSamples = false
    private var outputFrames = 0L
    private var monoBuffer = FloatArray(0)
    private val vadFrame = ShortArray(FRAME_SAMPLES)
    private var vadFill = 0
    @Volatile private var vad: VadWebRTC? = null
    @Volatile private var vadUnavailable = false

    fun setEnabled(value: Boolean) {
        if (released) return
        if (value) {
            if (!enabled) rebasePending = true
            enabled = true

            ensureVad()
        } else {
            enabled = false
            closeOpenSegment()
        }
    }

    fun clearSegments() {
        synchronized(lock) {
            segments.clear()
            speechRunStartMs = -1L
            speechRunEndMs = -1L
            silenceRunMs = 0L
        }
    }

    fun snapshotSegments(): List<SpeechSegment> {
        synchronized(lock) {
            if (segments.isEmpty()) return emptyList()
            val newest = segments.maxOf { it.endMs }
            val cutoff = newest - MAX_WINDOW_MS
            return segments.filter { it.endMs >= cutoff }
        }
    }

    fun onFlush(positionOffsetUs: Long, format: AudioProcessor.AudioFormat) {
        if (released) return
        closeOpenSegment()
        inputRate = format.sampleRate
        channelCount = format.channelCount
        encoding = format.encoding
        streamAnchorMs = if (positionOffsetUs == C.TIME_UNSET || positionOffsetUs < 0L) {
            -1L
        } else {
            positionOffsetUs / 1000L
        }
        samplesConsumed = 0L
        resetResampler()
        rebasePending = true
    }

    fun onStreamEnd() {
        closeOpenSegment()
    }

    fun release() {
        released = true
        enabled = false
        closeOpenSegment()
        synchronized(lock) { segments.clear() }
        try {
            vad?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "VAD close failed", t)
        }
        vad = null
    }

    fun onPcm(buffer: ByteBuffer, format: AudioProcessor.AudioFormat) {
        if (released) return

        if (inputRate <= 0) {
            inputRate = format.sampleRate
            channelCount = format.channelCount
            encoding = format.encoding
        }
        if (format.sampleRate != inputRate ||
            format.channelCount != channelCount ||
            format.encoding != encoding
        ) {

            streamAnchorMs = currentStreamPositionMs()
            samplesConsumed = 0L
            inputRate = format.sampleRate
            channelCount = format.channelCount
            encoding = format.encoding
            resetResampler()
            rebasePending = true
        }
        if (inputRate <= 0 || channelCount <= 0) return

        val bytesPerFrame = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2 * channelCount
            C.ENCODING_PCM_8BIT -> channelCount
            C.ENCODING_PCM_24BIT -> 3 * channelCount
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4 * channelCount
            else -> return
        }
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return

        val canCapture = enabled &&
            encoding == C.ENCODING_PCM_16BIT &&
            streamAnchorMs >= 0L
        if (!canCapture) {
            if (!enabled) closeOpenSegment()
            samplesConsumed += frames
            return
        }

        if (rebasePending) {
            baseAnchorMs = streamAnchorMs + samplesConsumed * 1000L / inputRate
            resetResampler()
            rebasePending = false
        }

        ensureMonoCapacity(frames)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var frame = 0
        while (frame < frames) {
            var sum = 0
            var channel = 0
            while (channel < channelCount) {
                sum += buffer.getShort().toInt()
                channel++
            }
            monoBuffer[frame] = sum.toFloat() / channelCount
            frame++
        }

        val step = inputRate.toDouble() / VAD_SAMPLE_RATE.toDouble()
        val lastSampleIndex = baseSampleIndex + frames - 1
        while (true) {
            val sampleIndex = floor(nextOutputPosition).toLong()
            if (sampleIndex + 1 > lastSampleIndex) break
            val first = sampleAt(sampleIndex)
            val second = sampleAt(sampleIndex + 1)
            val fraction = (nextOutputPosition - sampleIndex).toFloat()
            appendSample(first + (second - first) * fraction)
            nextOutputPosition += step
        }

        if (frames >= 2) {
            previousSampleA = monoBuffer[frames - 2]
            previousSampleB = monoBuffer[frames - 1]
            hasPreviousSamples = true
        } else if (frames == 1) {
            previousSampleA = previousSampleB
            previousSampleB = monoBuffer[0]
            hasPreviousSamples = true
        }
        baseSampleIndex += frames
        samplesConsumed += frames
    }

    private fun currentStreamPositionMs(): Long =
        if (streamAnchorMs < 0L) -1L
        else streamAnchorMs + samplesConsumed * 1000L / inputRate.coerceAtLeast(1)

    private fun resetResampler() {
        baseSampleIndex = 0L
        nextOutputPosition = 0.0
        previousSampleA = 0f
        previousSampleB = 0f
        hasPreviousSamples = false
        outputFrames = 0L
        vadFill = 0
    }

    private fun ensureMonoCapacity(size: Int) {
        if (monoBuffer.size < size) monoBuffer = FloatArray(size)
    }

    private fun sampleAt(absoluteIndex: Long): Float {
        val relative = absoluteIndex - baseSampleIndex
        return when {
            relative >= 0L -> monoBuffer[relative.toInt()]
            relative == -1L && hasPreviousSamples -> previousSampleB
            relative == -2L && hasPreviousSamples -> previousSampleA
            else -> 0f
        }
    }

    private fun appendSample(value: Float) {
        vadFrame[vadFill++] = value.toInt().coerceIn(-32768, 32767).toShort()
        if (vadFill < FRAME_SAMPLES) return
        val frameStartMs = baseAnchorMs + outputFrames * FRAME_MS
        outputFrames++
        vadFill = 0
        val detector = ensureVad() ?: return
        val speech = try {
            detector.isSpeech(vadFrame)
        } catch (t: Throwable) {
            Log.w(TAG, "VAD frame failed", t)
            false
        }
        pushVadFrame(frameStartMs, speech)
    }

    private fun ensureVad(): VadWebRTC? {
        if (vadUnavailable) return null
        vad?.let { return it }
        return try {
            VadWebRTC(
                SampleRate.SAMPLE_RATE_16K,
                FrameSize.FRAME_SIZE_160,
                Mode.AGGRESSIVE,
                0,
                0,
            ).also { vad = it }
        } catch (t: Throwable) {
            vadUnavailable = true
            Log.w(TAG, "WebRTC VAD unavailable", t)
            null
        }
    }

    private fun pushVadFrame(startMs: Long, speech: Boolean) {
        synchronized(lock) {
            if (speech) {
                if (speechRunStartMs < 0L) speechRunStartMs = startMs
                speechRunEndMs = startMs + FRAME_MS
                silenceRunMs = 0L
            } else if (speechRunStartMs >= 0L) {
                silenceRunMs += FRAME_MS
                if (silenceRunMs > SPEECH_MERGE_GAP_MS) {
                    addSegmentLocked(speechRunStartMs, speechRunEndMs)
                    speechRunStartMs = -1L
                    speechRunEndMs = -1L
                    silenceRunMs = 0L
                }
            }
        }
    }

    private fun closeOpenSegment() {
        synchronized(lock) {
            if (speechRunStartMs >= 0L) {
                addSegmentLocked(speechRunStartMs, speechRunEndMs)
            }
            speechRunStartMs = -1L
            speechRunEndMs = -1L
            silenceRunMs = 0L
        }
    }

    private fun addSegmentLocked(startMs: Long, endMs: Long) {
        if (endMs - startMs < SPEECH_MIN_SEGMENT_MS) return
        segments.add(SpeechSegment(startMs, endMs))
        if (segments.size > MAX_SEGMENTS) {
            segments.subList(0, segments.size - MAX_SEGMENTS / 2).clear()
        }
    }

    companion object {
        private const val TAG = "SubtitleAudioSync"
        private const val VAD_SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 160
        private const val FRAME_MS = 10L
        private const val SPEECH_MERGE_GAP_MS = 250L
        private const val SPEECH_MIN_SEGMENT_MS = 120L
        private const val MAX_WINDOW_MS = 10 * 60_000L
        private const val MAX_SEGMENTS = 4_000
    }
}

@UnstableApi
private class SubtitleSyncAudioProcessor(
    private val recorder: AudioSpeechRecorder,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        try {
            recorder.onPcm(inputBuffer.duplicate(), inputAudioFormat)
        } catch (t: Throwable) {
            Log.w("SubtitleAudioSync", "Audio capture failed", t)
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        recorder.onFlush(streamMetadata.positionOffsetUs, inputAudioFormat)
    }

    override fun onQueueEndOfStream() {
        recorder.onStreamEnd()
    }

    override fun onReset() {
        recorder.onStreamEnd()
    }
}

@UnstableApi
class SubtitleSyncRenderersFactory(
    context: Context,
    private val recorder: AudioSpeechRecorder,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(SubtitleSyncAudioProcessor(recorder)))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .build()
}
