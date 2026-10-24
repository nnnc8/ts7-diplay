package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: the bundled software encoder also works on
 * head units without a platform Opus encoder. Probe it before advertising the microphone formats,
 * without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    private val processAvailability by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CachedOpusEncoderAvailability(
            sdkInt = Build.VERSION.SDK_INT,
            platformAvailable = ::platformAvailable,
            software = { SoftwareOpusEncoder(bitrate = 48_000) },
        )
    }

    fun isAvailable(): Boolean = processAvailability.isAvailable()

    /** Avoids probing when CarPlay will not be offered a microphone encoder. */
    fun isAvailable(microphoneOffered: Boolean): Boolean =
        isAvailableIfOffered(microphoneOffered) { isAvailable() }

    internal fun isAvailableIfOffered(microphoneOffered: Boolean, probe: () -> Boolean): Boolean =
        microphoneOffered && probe()

    internal fun isAvailable(
        sdkInt: Int = Build.VERSION.SDK_INT,
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean = probe(sdkInt, platformAvailable, software)

    internal fun probe(
        sdkInt: Int,
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        if (sdkInt >= Build.VERSION_CODES.Q && runCatching(platformAvailable).getOrDefault(false)) return true
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
}

/** Thread-safe lazy probe whose inputs can be injected in JVM tests. */
internal class CachedOpusEncoderAvailability(
    private val sdkInt: Int,
    private val platformAvailable: () -> Boolean,
    private val software: () -> MicrophoneOpusEncoder,
) {
    private val cached by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpusEncoderSupport.probe(sdkInt, platformAvailable, software)
    }

    fun isAvailable(): Boolean = cached
}
