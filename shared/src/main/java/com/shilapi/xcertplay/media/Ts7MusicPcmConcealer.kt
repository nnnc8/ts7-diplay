package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Keeps the last 8 ms of TS7 PCM writable so a later gap can fade the true packet boundary. */
internal class Ts7MusicPcmConcealer(
    private val sampleRate: Int,
    private val channels: Int,
) {
    data class Result(
        val chunks: List<Chunk>,
        val repeatFrames: Long,
    )
    data class Chunk(val pcm: ByteArray, val silence: Boolean = false)

    private val frameBytes = channels.coerceAtLeast(1) * 2
    private val tailFrames = maxOf(1, sampleRate * TAIL_MILLIS / 1000)
    private val crossfadeFrames = maxOf(1, sampleRate * CROSSFADE_MILLIS / 1000)
    private var tail = ByteArray(0)
    private var repeatSource: ByteArray? = null
    private var joinNextBlock = false

    val hasRepeatSource: Boolean get() = repeatSource != null

    fun process(decodedPcm: ByteArray, missingFrames: Int): Result {
        val current = decodedPcm.copyOf(decodedPcm.size - decodedPcm.size % frameBytes)
        val previousTail = tail
        val outputs = ArrayList<Chunk>(3)
        val shortGap = missingFrames in 1..(MAX_SHORT_PACKETS * Ts7MusicGapConcealer.AAC_FRAMES)
        var repeated = 0L

        if (previousTail.isNotEmpty()) {
            if (missingFrames >= LONG_GAP_PACKETS * Ts7MusicGapConcealer.AAC_FRAMES) fadeOut(previousTail)
            outputs += Chunk(previousTail)
        }

        var gapPcm: ByteArray? = null
        if (missingFrames > 0) {
            if (shortGap) shortGapsConcealed++ else longGapsConcealed++
            val source = repeatSource
            if (shortGap && source != null && source.isNotEmpty()) {
                gapPcm = repeatFrames(source, missingFrames)
                repeated = missingFrames.toLong()
            } else {
                gapPcm = ByteArray(missingFrames * frameBytes)
            }
            if (shortGap && source != null && previousTail.isNotEmpty()) {
                crossfadeInto(gapPcm, previousTail)
            }
            outputs += Chunk(gapPcm, silence = repeated == 0L)
            if (shortGap && source != null) {
                crossfadeInto(current, gapPcm)
            } else if (!shortGap) {
                fadeIn(current)
            }
        } else if (joinNextBlock && previousTail.isNotEmpty()) {
            crossfadeInto(current, previousTail)
        }
        joinNextBlock = false

        repeatSource = current.copyOf()
        if (current.isNotEmpty()) {
            val frameCount = current.size / frameBytes
            val heldFrames = minOf(tailFrames, frameCount)
            val prefixBytes = (frameCount - heldFrames) * frameBytes
            if (prefixBytes > 0) outputs += Chunk(current.copyOfRange(0, prefixBytes))
            tail = current.copyOfRange(prefixBytes, current.size)
        } else {
            tail = ByteArray(0)
        }
        return Result(outputs, repeated)
    }

    fun markJoinNeeded() {
        joinNextBlock = true
    }

    fun flushTail(): ByteArray? = tail.takeIf { it.isNotEmpty() }?.also { tail = ByteArray(0) }

    /** Forget PCM from the previous song while keeping cumulative concealment counters. */
    fun resetTimeline() {
        tail = ByteArray(0)
        repeatSource = null
        joinNextBlock = false
    }

    internal fun resetForTest() {
        resetTimeline()
        shortGapsConcealed = 0
        longGapsConcealed = 0
        repeatFrames = 0
    }

    var shortGapsConcealed = 0L
        private set
    var longGapsConcealed = 0L
        private set
    var repeatFrames = 0L
        private set

    private fun repeatFrames(source: ByteArray, frames: Int): ByteArray {
        val result = ByteArray(frames * frameBytes)
        val sourceFrames = source.size / frameBytes
        val packets = (frames + Ts7MusicGapConcealer.AAC_FRAMES - 1) / Ts7MusicGapConcealer.AAC_FRAMES
        var writtenFrames = 0
        var previousSegment: ByteArray? = null
        repeat(packets) { packetIndex ->
            val count = minOf(Ts7MusicGapConcealer.AAC_FRAMES, frames - writtenFrames)
            val segment = ByteArray(count * frameBytes)
            val gain = ATTENUATION_PER_PACKET.pow(packetIndex.toDouble()).toFloat()
            for (frame in 0 until count) {
                val sourceFrame = frame % sourceFrames
                for (channel in 0 until channels.coerceAtLeast(1)) {
                    val sourceOffset = sourceFrame * frameBytes + channel * 2
                    val destinationOffset = frame * frameBytes + channel * 2
                    val sample = readSample(source, sourceOffset)
                    writeSample(segment, destinationOffset, (sample * gain).roundToInt())
                }
            }
            // The outer process() blends the first repeated block with the prior PCM tail.
            // Only blend subsequent repeated packets here, or the first join would be applied twice.
            previousSegment?.let { crossfadeInto(segment, it) }
            segment.copyInto(result, writtenFrames * frameBytes)
            writtenFrames += count
            previousSegment = segment
        }
        repeatFrames += frames
        return result
    }

    /** Equal-power blend into the beginning of [next], preserving every output frame. */
    private fun crossfadeInto(next: ByteArray, previous: ByteArray) {
        val nextFrames = next.size / frameBytes
        val previousFrames = previous.size / frameBytes
        val frames = minOf(crossfadeFrames, nextFrames, previousFrames)
        if (frames <= 0) return
        val previousStart = previousFrames - frames
        for (frame in 0 until frames) {
            val angle = (frame + 1).toDouble() / frames * PI / 2.0
            val oldGain = cos(angle)
            val newGain = sin(angle)
            for (channel in 0 until channels.coerceAtLeast(1)) {
                val old = readSample(previous, (previousStart + frame) * frameBytes + channel * 2)
                val fresh = readSample(next, frame * frameBytes + channel * 2)
                writeSample(next, frame * frameBytes + channel * 2, (old * oldGain + fresh * newGain).roundToInt())
            }
        }
    }

    private fun fadeOut(bytes: ByteArray) = applyFade(bytes, fadeIn = false)
    private fun fadeIn(bytes: ByteArray) = applyFade(bytes, fadeIn = true)

    private fun applyFade(bytes: ByteArray, fadeIn: Boolean) {
        val frames = minOf(sampleRate * LONG_FADE_MILLIS / 1000, bytes.size / frameBytes)
        if (frames <= 0) return
        val offsetFrame = if (fadeIn) 0 else bytes.size / frameBytes - frames
        for (index in 0 until frames) {
            val gain = if (fadeIn) (index + 1).toDouble() / frames else (frames - index - 1).toDouble() / frames
            val frame = offsetFrame + index
            for (channel in 0 until channels.coerceAtLeast(1)) {
                val offset = frame * frameBytes + channel * 2
                writeSample(bytes, offset, (readSample(bytes, offset) * gain).roundToInt())
            }
        }
    }

    private fun readSample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()

    private fun writeSample(bytes: ByteArray, offset: Int, value: Int) {
        val bounded = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[offset] = bounded.toByte()
        bytes[offset + 1] = (bounded shr 8).toByte()
    }

    companion object {
        const val TAIL_MILLIS = 8
        const val CROSSFADE_MILLIS = 5
        const val LONG_FADE_MILLIS = 8
        const val MAX_SHORT_PACKETS = 3
        const val LONG_GAP_PACKETS = 4
        private const val ATTENUATION_PER_PACKET = 0.5011872336272722
    }
}
