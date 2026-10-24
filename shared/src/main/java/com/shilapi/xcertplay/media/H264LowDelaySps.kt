package com.shilapi.xcertplay.media

/**
 * Rewrites an H.264 sequence parameter set so a decoder may output each picture as soon as it is decoded.
 *
 * Without VUI bitstream_restriction, H.264 lets a decoder assume pictures can be reordered up to the
 * full DPB, so it holds several decoded frames before releasing the first. The iPhone's CarPlay H.264 has
 * no reordered (B) frames. TS7's OMX.sprd.h264.decoder has no vendor low-latency key and measured about
 * 190 ms from input to output, which is several 30 fps frames held for reordering. Declaring
 * max_num_reorder_frames = 0 is the decoder-independent way to ask for output in decoding order.
 *
 * Use it only on a stream without B slices: [containsBSlice] checks each access unit.
 * Kept free of Android types so it stays testable on the JVM.
 */
object H264LowDelaySps {
    private const val NAL_SPS = 7

    // Profiles whose SPS carries chroma_format_idc and the scaling matrices (H.264 7.3.2.1.1).
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    // Values H.264 E.2.1 infers when bitstream_restriction is absent.
    private const val INFERRED_MAX_BYTES_PER_PIC_DENOM = 2L
    private const val INFERRED_MAX_BITS_PER_MB_DENOM = 1L
    private const val INFERRED_LOG2_MAX_MV_LENGTH = 15L

    /**
     * [sps] is one SPS NAL unit with its one-byte header and no start code.
     * Returns the rewritten NAL unit, or null when it already declares no reordering or cannot be parsed.
     */
    fun rewrite(sps: ByteArray): ByteArray? {
        if (sps.size < 4 || (sps[0].toInt() and 0x1f) != NAL_SPS) return null
        return try {
            rewriteRbsp(unescape(sps, 1, sps.size))?.let { byteArrayOf(sps[0]) + escape(it) }
        } catch (_: MalformedSps) {
            null
        }
    }

    /**
     * Rewrites every SPS in an access unit or codec-specific data, either Annex B or 4-byte length-prefixed.
     * Returns [data] itself when nothing changed.
     */
    fun rewriteAccessUnit(data: ByteArray): ByteArray = nalUnits(data)?.let { rewriteUnits(data, it) } ?: data

    /**
     * One pass for the decoder's input: [data] with every SPS rewritten ([data] itself when none changed),
     * or null when it holds a B slice and so must keep its reordering.
     */
    fun lowDelayAccessUnit(data: ByteArray): ByteArray? {
        val units = nalUnits(data) ?: return data
        if (units.any { isBSlice(data, it) }) return null
        return rewriteUnits(data, units)
    }

    /** True when any slice of the access unit is a B slice, so output in decoding order would be wrong. */
    fun containsBSlice(data: ByteArray): Boolean = nalUnits(data)?.any { isBSlice(data, it) } ?: false

    private fun rewriteUnits(data: ByteArray, units: List<NalUnit>): ByteArray {
        if (units.none { it.type == NAL_SPS }) return data
        var changed = false
        val rewritten = units.map { unit ->
            val bytes = data.copyOfRange(unit.start, unit.end)
            if (unit.type != NAL_SPS) bytes else (rewrite(bytes)?.also { changed = true } ?: bytes)
        }
        if (!changed) return data
        val annexB = startsWithStartCode(data)
        val size = rewritten.sumOf { it.size + 4 }
        val output = ByteArray(size)
        var offset = 0
        for (unit in rewritten) {
            if (annexB) {
                output[offset + 3] = 1
            } else {
                output[offset] = (unit.size ushr 24).toByte()
                output[offset + 1] = (unit.size ushr 16).toByte()
                output[offset + 2] = (unit.size ushr 8).toByte()
                output[offset + 3] = unit.size.toByte()
            }
            offset += 4
            unit.copyInto(output, offset)
            offset += unit.size
        }
        return output
    }

    private fun isBSlice(data: ByteArray, unit: NalUnit): Boolean {
        if (unit.type != 1 && unit.type != 5) return false
        // first_mb_in_slice and slice_type fit in the first few bytes after the header.
        val prefixEnd = minOf(unit.end, unit.start + 1 + 16)
        val sliceType = try {
            BitReader(unescape(data, unit.start + 1, prefixEnd)).run { ue(); ue() }
        } catch (_: MalformedSps) {
            return false
        }
        return sliceType % 5 == 1L
    }

    private class MalformedSps : Exception()

    private class NalUnit(val start: Int, val end: Int, val type: Int)

    /** NAL unit bounds without start codes or length prefixes, or null when [data] is neither form. */
    private fun nalUnits(data: ByteArray): List<NalUnit>? =
        if (startsWithStartCode(data)) annexBUnits(data) else lengthPrefixedUnits(data)

    private fun startsWithStartCode(data: ByteArray): Boolean =
        data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))

    private fun lengthPrefixedUnits(data: ByteArray): List<NalUnit>? {
        val units = ArrayList<NalUnit>(4)
        var offset = 0
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 255) shl 24) or ((data[offset + 1].toInt() and 255) shl 16) or
                ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)
            offset += 4
            if (length <= 0 || length > data.size - offset) return null
            units += NalUnit(offset, offset + length, data[offset].toInt() and 0x1f)
            offset += length
        }
        return units.takeIf { offset == data.size && it.isNotEmpty() }
    }

    private fun annexBUnits(data: ByteArray): List<NalUnit> {
        val units = ArrayList<NalUnit>(4)
        var start = -1
        var index = 0
        while (index + 2 < data.size) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0 && data[index + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = index
                    // A four-byte start code's leading zero belongs to the next unit's prefix.
                    if (end > start && data[end - 1].toInt() == 0) end--
                    if (end > start) units += NalUnit(start, end, data[start].toInt() and 0x1f)
                }
                start = index + 3
                index += 3
            } else {
                index++
            }
        }
        if (start in 0 until data.size) units += NalUnit(start, data.size, data[start].toInt() and 0x1f)
        return units
    }

    /** Returns the rewritten RBSP, or null when the SPS already allows immediate output. */
    private fun rewriteRbsp(rbsp: ByteArray): ByteArray? {
        val r = BitReader(rbsp)
        val w = BitWriter(rbsp.size + 8)
        fun bits(count: Int): Long = r.bits(count).also { w.bits(it, count) }
        fun ue(): Long = r.ue().also { w.ue(it) }
        fun se() { w.se(r.se()) }

        val profile = bits(8).toInt()
        bits(8) // constraint_set flags and reserved_zero_2bits
        bits(8) // level_idc
        ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chromaFormat = ue()
            if (chromaFormat == 3L) bits(1) // separate_colour_plane_flag
            ue() // bit_depth_luma_minus8
            ue() // bit_depth_chroma_minus8
            bits(1) // qpprime_y_zero_transform_bypass_flag
            if (bits(1) == 1L) { // seq_scaling_matrix_present_flag
                repeat(if (chromaFormat != 3L) 8 else 12) { list ->
                    if (bits(1) == 1L) copyScalingList(r, w, if (list < 6) 16 else 64)
                }
            }
        }
        ue() // log2_max_frame_num_minus4
        when (ue()) { // pic_order_cnt_type
            0L -> ue() // log2_max_pic_order_cnt_lsb_minus4
            1L -> {
                bits(1) // delta_pic_order_always_zero_flag
                se() // offset_for_non_ref_pic
                se() // offset_for_top_to_bottom_field
                val cycle = ue()
                if (cycle > 255) throw MalformedSps()
                repeat(cycle.toInt()) { se() } // offset_for_ref_frame
            }
        }
        val maxNumRefFrames = ue()
        bits(1) // gaps_in_frame_num_value_allowed_flag
        ue() // pic_width_in_mbs_minus1
        ue() // pic_height_in_map_units_minus1
        if (bits(1) == 0L) bits(1) // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        bits(1) // direct_8x8_inference_flag
        if (bits(1) == 1L) repeat(4) { ue() } // frame cropping offsets

        val maxDecFrameBuffering = maxOf(1L, maxNumRefFrames)
        val vuiPresent = r.bits(1) == 1L
        w.bits(1, 1)
        if (!vuiPresent) {
            // aspect ratio, overscan, video signal, chroma location, timing, NAL HRD, VCL HRD, pic_struct
            w.bits(0, 8)
            writeRestriction(w, true, INFERRED_MAX_BYTES_PER_PIC_DENOM, INFERRED_MAX_BITS_PER_MB_DENOM,
                INFERRED_LOG2_MAX_MV_LENGTH, INFERRED_LOG2_MAX_MV_LENGTH, maxDecFrameBuffering)
        } else {
            if (bits(1) == 1L && bits(8) == 255L) bits(32) // aspect_ratio_idc, Extended_SAR
            if (bits(1) == 1L) bits(1) // overscan_appropriate_flag
            if (bits(1) == 1L) { // video_signal_type_present_flag
                bits(4) // video_format, video_full_range_flag
                if (bits(1) == 1L) bits(24) // colour description
            }
            if (bits(1) == 1L) { ue(); ue() } // chroma sample locations
            if (bits(1) == 1L) { bits(32); bits(32); bits(1) } // num_units_in_tick, time_scale, fixed_frame_rate_flag
            val nalHrd = bits(1) == 1L
            if (nalHrd) copyHrd(r, w)
            val vclHrd = bits(1) == 1L
            if (vclHrd) copyHrd(r, w)
            if (nalHrd || vclHrd) bits(1) // low_delay_hrd_flag
            bits(1) // pic_struct_present_flag
            if (r.bits(1) == 1L) {
                val overPicBoundaries = r.bits(1) == 1L
                val bytesPerPic = r.ue()
                val bitsPerMb = r.ue()
                val mvHorizontal = r.ue()
                val mvVertical = r.ue()
                val reorder = r.ue()
                val decBuffering = r.ue()
                if (reorder == 0L) return null
                if (decBuffering < maxNumRefFrames) throw MalformedSps()
                writeRestriction(w, overPicBoundaries, bytesPerPic, bitsPerMb, mvHorizontal, mvVertical,
                    maxDecFrameBuffering)
            } else {
                writeRestriction(w, true, INFERRED_MAX_BYTES_PER_PIC_DENOM, INFERRED_MAX_BITS_PER_MB_DENOM,
                    INFERRED_LOG2_MAX_MV_LENGTH, INFERRED_LOG2_MAX_MV_LENGTH, maxDecFrameBuffering)
            }
        }
        if (!r.atTrailingBits()) throw MalformedSps()
        w.bits(1, 1) // rbsp_stop_one_bit
        return w.toByteArray()
    }

    private fun writeRestriction(
        w: BitWriter,
        overPicBoundaries: Boolean,
        bytesPerPic: Long,
        bitsPerMb: Long,
        mvHorizontal: Long,
        mvVertical: Long,
        decBuffering: Long,
    ) {
        w.bits(1, 1) // bitstream_restriction_flag
        w.bits(if (overPicBoundaries) 1 else 0, 1)
        w.ue(bytesPerPic)
        w.ue(bitsPerMb)
        w.ue(mvHorizontal)
        w.ue(mvVertical)
        w.ue(0) // max_num_reorder_frames
        w.ue(decBuffering) // max_dec_frame_buffering
    }

    private fun copyScalingList(r: BitReader, w: BitWriter, size: Int) {
        var last = 8L
        var next = 8L
        repeat(size) {
            if (next != 0L) {
                val delta = r.se()
                w.se(delta)
                next = (last + delta + 256) % 256
            }
            if (next != 0L) last = next
        }
    }

    private fun copyHrd(r: BitReader, w: BitWriter) {
        val count = r.ue().also { w.ue(it) } // cpb_cnt_minus1
        if (count > 31) throw MalformedSps()
        w.bits(r.bits(8), 8) // bit_rate_scale, cpb_size_scale
        repeat(count.toInt() + 1) {
            w.ue(r.ue()) // bit_rate_value_minus1
            w.ue(r.ue()) // cpb_size_value_minus1
            w.bits(r.bits(1), 1) // cbr_flag
        }
        w.bits(r.bits(20), 20) // four delay and offset lengths
    }

    /** Removes emulation-prevention bytes (00 00 03) from data[from until to]. */
    private fun unescape(data: ByteArray, from: Int, to: Int): ByteArray {
        val output = ByteArray(to - from)
        var size = 0
        var zeros = 0
        for (index in from until to) {
            val byte = data[index]
            if (zeros >= 2 && byte.toInt() == 3) {
                zeros = 0
                continue
            }
            output[size++] = byte
            zeros = if (byte.toInt() == 0) zeros + 1 else 0
        }
        return output.copyOf(size)
    }

    /** Inserts emulation-prevention bytes so the RBSP never forms a start code. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 255
            if (zeros >= 2 && value <= 3) {
                output.write(3)
                zeros = 0
            }
            output.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun bits(count: Int): Long {
            if (count > 64 || position + count > data.size * 8) throw MalformedSps()
            var value = 0L
            repeat(count) {
                val bit = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
                value = (value shl 1) or bit.toLong()
                position++
            }
            return value
        }

        fun ue(): Long {
            var zeros = 0
            while (bits(1) == 0L) if (++zeros > 31) throw MalformedSps()
            return (1L shl zeros) - 1 + bits(zeros)
        }

        fun se(): Long {
            val code = ue()
            return if (code and 1L == 1L) (code + 1) / 2 else -(code / 2)
        }

        /** True when only rbsp_trailing_bits (a one, then zeros) remain. */
        fun atTrailingBits(): Boolean {
            if (position >= data.size * 8 || bits(1) != 1L) return false
            while (position < data.size * 8) if (bits(1) != 0L) return false
            return true
        }
    }

    private class BitWriter(capacity: Int) {
        private val output = java.io.ByteArrayOutputStream(capacity)
        private var current = 0
        private var used = 0

        fun bits(value: Long, count: Int) {
            for (shift in count - 1 downTo 0) {
                current = (current shl 1) or ((value ushr shift) and 1L).toInt()
                if (++used == 8) {
                    output.write(current)
                    current = 0
                    used = 0
                }
            }
        }

        fun ue(value: Long) {
            val code = value + 1
            val length = 63 - java.lang.Long.numberOfLeadingZeros(code)
            bits(0, length)
            bits(code, length + 1)
        }

        fun se(value: Long) = ue(if (value > 0) value * 2 - 1 else -value * 2)

        /** Pads the last byte with zero bits. */
        fun toByteArray(): ByteArray {
            if (used > 0) bits(0, 8 - used)
            return output.toByteArray()
        }
    }
}
