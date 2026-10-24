package com.shilapi.xcertplay.media

/** Where an H.264 access-unit delimiter is sent relative to a frame. */
enum class H264FrameEndMarkerMode(val key: String) {
    OFF("off"),
    APPEND("append"),
    SEPARATE("separate");

    companion object {
        fun fromKey(key: String?): H264FrameEndMarkerMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Exact codec input bytes after preparing one Annex B H.264 access unit for its selected marker mode. */
internal data class H264FrameInput(
    /** Frame bytes used for keyframe and reference-chain checks. */
    val referenceBytes: ByteArray,
    /** Bytes queued as the frame input buffer. */
    val frameBytes: ByteArray,
    /** A separate AUD-only input, or null when no second input buffer is needed. */
    val separateMarker: ByteArray?,
)

/** Byte-only AUD preparation, kept independent of MediaCodec for deterministic JVM tests. */
internal object H264FrameEndMarkerBytes {
    private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    fun prepare(annexB: ByteArray, mode: H264FrameEndMarkerMode): H264FrameInput {
        if (mode == H264FrameEndMarkerMode.OFF) return H264FrameInput(annexB, annexB, null)

        val frame = stripLeadingAud(annexB)
        return when (mode) {
            H264FrameEndMarkerMode.OFF -> H264FrameInput(frame, frame, null)
            H264FrameEndMarkerMode.APPEND -> H264FrameInput(frame, frame + AUD, null)
            H264FrameEndMarkerMode.SEPARATE -> H264FrameInput(frame, frame, AUD.copyOf())
        }
    }

    private fun stripLeadingAud(annexB: ByteArray): ByteArray {
        val prefixSize = when {
            annexB.startsWithStartCode(4) -> 4
            annexB.startsWithStartCode(3) -> 3
            else -> return annexB
        }
        if (prefixSize >= annexB.size || (annexB[prefixSize].toInt() and 0x1f) != 9) return annexB
        val nextStart = annexB.nextStartCode(prefixSize + 1)
        return if (nextStart >= 0) annexB.copyOfRange(nextStart, annexB.size) else ByteArray(0)
    }

    private fun ByteArray.startsWithStartCode(size: Int): Boolean =
        this.size >= size && this[0] == 0.toByte() && this[1] == 0.toByte() &&
            if (size == 4) this[2] == 0.toByte() && this[3] == 1.toByte() else this[2] == 1.toByte()

    private fun ByteArray.nextStartCode(from: Int): Int {
        for (index in from until size - 2) {
            if (this[index] != 0.toByte() || this[index + 1] != 0.toByte()) continue
            if (this[index + 2] == 0.toByte() && index + 3 < size && this[index + 3] == 1.toByte()) return index
            if (this[index + 2] == 1.toByte()) return index
        }
        return -1
    }
}
