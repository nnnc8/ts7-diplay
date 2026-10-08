// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.baseline

import android.annotation.TargetApi
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.nio.ByteBuffer

/** Failure-only platform control. The caller must retain the original sink gate failure. */
@TargetApi(27)
internal object PlatformCodecProbe {
    data class Result(
        val status: String,
        val inputQueued: Int,
        val outputReleased: Int,
        val frames: Int,
        val redFrames: Int,
    )

    fun run(fixture: SyntheticAvc, renderSurface: Boolean = true): Result {
        var owner: SurfaceFrameProbe? = null
        var codec: MediaCodec? = null
        var started = false
        var interrupted = false
        var stage = "FIXTURE_INVALID"
        var status = "FIXTURE_INVALID"
        var inputQueued = 0
        var outputReleased = 0
        var frames = 0
        var redFrames = 0

        try {
            if (Build.VERSION.SDK_INT != 27) fail("API27_REQUIRED")
            checkInterrupted()
            val (sps, pps) = MediaCodecSupport.avcParameterSets(fixture.config)
            if (sps.isEmpty() || sps[0].toInt() and 31 != 7 ||
                pps.isEmpty() || pps[0].toInt() and 31 != 8 || fixture.frames.size != FRAME_COUNT) {
                fail("FIXTURE_INVALID")
            }
            // Keep the encoder's complete IDR and dependent-picture access units.
            val accessUnits = fixture.frames.map { MediaCodecSupport.toAnnexB(it) }
            if (accessUnits.any { it.isEmpty() || it.size > MAX_INPUT_SIZE } ||
                !MediaCodecSupport.isRandomAccess(accessUnits.first(), VideoCodec.H264)) {
                fail("FIXTURE_INVALID")
            }

            stage = "SURFACE_INIT_FAILED"
            val output = if (renderSurface) SurfaceFrameProbe().also { owner = it } else null
            output?.let(::requireHealthy)
            stage = "CODEC_CREATE_FAILED"
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec = it }
            stage = "CODEC_CONFIGURE_FAILED"
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 160, 96).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
            }
            decoder.configure(format, output?.surface, null, 0)
            stage = "CODEC_START_FAILED"
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()

            @Suppress("DEPRECATION")
            fun drainOne() {
                checkInterrupted()
                output?.let(::requireHealthy)
                stage = "OUTPUT_FAILED"
                val index = decoder.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> {
                        if (!renderSurface) {
                            val buffer = decoder.getOutputBuffer(index) ?: fail("OUTPUT_BUFFER_INVALID")
                            if (info.size <= 0 || info.offset < 0 || info.offset.toLong() + info.size > buffer.capacity()) {
                                fail("OUTPUT_BUFFER_INVALID")
                            }
                        }
                        decoder.releaseOutputBuffer(index, renderSurface)
                        outputReleased++
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> fail("OUTPUT_FAILED")
                }
            }

            for (accessUnit in accessUnits) {
                val inputDeadline = SystemClock.elapsedRealtime() + INPUT_WAIT_MS
                var index = -1
                while (index < 0 && SystemClock.elapsedRealtime() < inputDeadline) {
                    drainOne()
                    stage = "INPUT_FAILED"
                    index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                }
                if (index < 0) fail("INPUT_TIMEOUT")
                stage = "INPUT_FAILED"
                val input = decoder.getInputBuffer(index) ?: fail("INPUT_BUFFER_INVALID")
                input.clear()
                if (accessUnit.size > input.remaining()) fail("INPUT_BUFFER_INVALID")
                input.put(accessUnit)
                decoder.queueInputBuffer(index, 0, accessUnit.size, System.nanoTime() / 1_000, 0)
                inputQueued++

                // Drain during the same 100ms feed cadence, allowing the fresh consumer to run.
                val cadenceDeadline = SystemClock.elapsedRealtime() + FRAME_INTERVAL_MS
                while (SystemClock.elapsedRealtime() < cadenceDeadline) {
                    drainOne()
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }

            val finalDeadline = SystemClock.elapsedRealtime() + FINAL_WAIT_MS
            while (SystemClock.elapsedRealtime() < finalDeadline) {
                drainOne()
                if (outputReleased >= REQUIRED_FRAMES && (output == null ||
                    (output.frames.get() >= REQUIRED_FRAMES && output.redPixels.get() >= REQUIRED_FRAMES &&
                        output.timestamp.get() > 0))) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            output?.let(::requireHealthy)
            status = when {
                outputReleased < REQUIRED_FRAMES -> "OUTPUT_INSUFFICIENT"
                output == null -> "BYTEBUFFER_OUTPUT_PASS"
                output.frames.get() < REQUIRED_FRAMES -> "FRAMES_INSUFFICIENT"
                output.redPixels.get() < REQUIRED_FRAMES -> "RED_FRAMES_INSUFFICIENT"
                output.timestamp.get() <= 0 -> "TIMESTAMP_MISSING"
                else -> "PASS"
            }
        } catch (failure: Throwable) {
            status = when (failure) {
                is ProbeFailure -> failure.status
                is InterruptedException -> {
                    interrupted = true
                    "INTERRUPTED"
                }
                else -> stage
            }
        } finally {
            frames = owner?.frames?.get() ?: 0
            redFrames = owner?.redPixels?.get() ?: 0
            // Clear interruption for the owner's bounded shutdown; restore it afterward.
            interrupted = Thread.interrupted() || interrupted
            var cleanupFailed = false
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    cleanupFailed = true
                    if (failure is InterruptedException) interrupted = true
                }
            }
            // Each cleanup runs even if the previous one throws. The Surface remains owned
            // by its consumer until the codec has been stopped and released.
            if (started) cleanup { codec?.stop() }
            cleanup { codec?.release() }
            cleanup {
                owner?.close()
                // Also reject a consumer fault arriving between the final check and shutdown.
                owner?.demandHealthy()
                if (owner?.releasedSurfaceIsValid() == true) fail("CLEANUP_FAILED")
            }
            if (cleanupFailed && status in setOf("PASS", "BYTEBUFFER_OUTPUT_PASS")) status = "CLEANUP_FAILED"
            if (interrupted) Thread.currentThread().interrupt()
        }

        return Result(status, inputQueued.coerceIn(0, MAX_COUNT), outputReleased.coerceIn(0, MAX_COUNT),
            frames.coerceIn(0, MAX_COUNT), redFrames.coerceIn(0, MAX_COUNT))
    }

    private fun requireHealthy(output: SurfaceFrameProbe) {
        try {
            output.demandHealthy()
            if (!output.surface.isValid) fail("SURFACE_UNHEALTHY")
        } catch (_: Throwable) {
            fail("SURFACE_UNHEALTHY")
        }
    }

    private fun checkInterrupted() {
        if (Thread.interrupted()) throw InterruptedException()
    }

    private class ProbeFailure(val status: String) : Exception()
    private fun fail(status: String): Nothing = throw ProbeFailure(status)

    private const val FRAME_COUNT = 12
    private const val REQUIRED_FRAMES = 3
    private const val MAX_COUNT = 999
    private const val MAX_INPUT_SIZE = 8 * 1024 * 1024
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val INPUT_WAIT_MS = 1_000L
    private const val FRAME_INTERVAL_MS = 100L
    private const val POLL_INTERVAL_MS = 10L
    private const val FINAL_WAIT_MS = 5_000L
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
}
