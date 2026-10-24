package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H264LowDelaySpsTest {
    // SPS from macOS VideoToolbox at 1280x720 with frame reordering off, like the iPhone's CarPlay encoder:
    // no VUI, so a decoder may hold frames for reordering.
    private val appleHigh = hex("2764001fac56805005b9")
    private val appleMain = hex("274d001fab402802dc80")
    private val appleBaseline = hex("2742001fab402802dc80")

    @Test fun appleSpsWithoutVuiGainsARestrictionWithNoReordering() {
        for (sps in listOf(appleHigh, appleMain, appleBaseline)) {
            val rewritten = H264LowDelaySps.rewrite(sps)
            assertNotNull(rewritten)
            // Header, profile, constraints and level stay; a second pass finds nothing to change.
            assertArrayEquals(sps.copyOf(4), rewritten!!.copyOf(4))
            assertNull(H264LowDelaySps.rewrite(rewritten))
        }
    }

    @Test fun addsTheExactVuiTheStandardDescribes() {
        val fields = SpsFields(profile = 100, level = 31, refFrames = 1, widthMbs = 80, heightMbs = 45)
        val expected = fields.copy(vui = Vui(restriction = Restriction(reorder = 0, decBuffering = 1))).nal()
        assertArrayEquals(expected, H264LowDelaySps.rewrite(fields.nal()))
    }

    @Test fun keepsExistingVuiFieldsAndOnlyClearsReordering() {
        val vui = Vui(
            aspectIdc = 255, sarWidth = 1, sarHeight = 1,
            timing = longArrayOf(1001, 60000), colour = intArrayOf(1, 1, 1),
            hrd = true, restriction = Restriction(reorder = 2, decBuffering = 4, mvHorizontal = 11, mvVertical = 9),
        )
        val fields = SpsFields(profile = 100, level = 40, refFrames = 3, widthMbs = 120, heightMbs = 68,
            crop = intArrayOf(0, 0, 0, 4), vui = vui)
        val expected = fields.copy(vui = vui.copy(restriction = vui.restriction!!.copy(reorder = 0, decBuffering = 3)))
        assertArrayEquals(expected.nal(), H264LowDelaySps.rewrite(fields.nal()))
    }

    @Test fun addsARestrictionToVuiThatHasNone() {
        val vui = Vui(timing = longArrayOf(1, 60))
        val fields = SpsFields(profile = 77, level = 31, refFrames = 2, widthMbs = 80, heightMbs = 45, vui = vui)
        val expected = fields.copy(vui = vui.copy(restriction = Restriction(reorder = 0, decBuffering = 2)))
        assertArrayEquals(expected.nal(), H264LowDelaySps.rewrite(fields.nal()))
    }

    @Test fun copiesScalingMatricesAndPictureOrderCycles() {
        val fields = SpsFields(profile = 100, level = 31, refFrames = 1, widthMbs = 80, heightMbs = 45,
            scaling = true, pocType = 1)
        val expected = fields.copy(vui = Vui(restriction = Restriction(reorder = 0, decBuffering = 1)))
        assertArrayEquals(expected.nal(), H264LowDelaySps.rewrite(fields.nal()))
    }

    @Test fun leavesAlreadyLowDelayAndBrokenParameterSetsAlone() {
        val lowDelay = SpsFields(profile = 66, level = 31, refFrames = 1, widthMbs = 80, heightMbs = 45,
            vui = Vui(restriction = Restriction(reorder = 0, decBuffering = 1))).nal()
        assertNull(H264LowDelaySps.rewrite(lowDelay))
        assertNull(H264LowDelaySps.rewrite(appleHigh.copyOf(6)))
        assertNull(H264LowDelaySps.rewrite(hex("2801020304"))) // a PPS
        assertNull(H264LowDelaySps.rewrite(appleHigh + byteArrayOf(0x55))) // data after the stop bit
    }

    @Test fun rewritesOnlyTheSpsOfAnAccessUnitInEitherFraming() {
        val pps = hex("28ee3cb0")
        val idr = hex("6588840021ff")
        val rewritten = H264LowDelaySps.rewrite(appleHigh)!!

        val annexB = START + appleHigh + START + pps + START + idr
        assertArrayEquals(START + rewritten + START + pps + START + idr, H264LowDelaySps.rewriteAccessUnit(annexB))

        val shortCodes = byteArrayOf(0, 0, 1) + appleHigh + byteArrayOf(0, 0, 1) + pps
        assertArrayEquals(START + rewritten + START + pps, H264LowDelaySps.rewriteAccessUnit(shortCodes))

        val prefixed = prefix(appleHigh) + prefix(pps) + prefix(idr)
        assertArrayEquals(prefix(rewritten) + prefix(pps) + prefix(idr), H264LowDelaySps.rewriteAccessUnit(prefixed))

        val noSps = START + idr
        assertSame(noSps, H264LowDelaySps.rewriteAccessUnit(noSps))
        val malformed = byteArrayOf(0, 0, 0, 9, 0x67)
        assertSame(malformed, H264LowDelaySps.rewriteAccessUnit(malformed))
    }

    @Test fun findsBSlicesSoReorderedStreamsKeepTheirOrder() {
        // first_mb_in_slice = 0, then slice_type: 1 and 6 are B, 0 and 5 are P, 2 and 7 are I.
        assertTrue(H264LowDelaySps.containsBSlice(START + byteArrayOf(0x01, 0xA0.toByte(), 0x80.toByte())))
        assertTrue(H264LowDelaySps.containsBSlice(prefix(byteArrayOf(0x01, 0x9C.toByte(), 0x80.toByte()))))
        assertFalse(H264LowDelaySps.containsBSlice(START + byteArrayOf(0x41, 0x98.toByte(), 0x80.toByte())))
        assertFalse(H264LowDelaySps.containsBSlice(START + byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())))
        assertFalse(H264LowDelaySps.containsBSlice(START + appleHigh))
    }

    @Test fun escapesEmulatedStartCodesInTheRewrittenSet() {
        // Zero-heavy cropping produces 00 00 0x runs in the RBSP that need emulation prevention.
        val fields = SpsFields(profile = 66, level = 10, refFrames = 0, widthMbs = 1, heightMbs = 1,
            crop = intArrayOf(0, 0, 0, 0), frameNumBits = 0)
        val rewritten = H264LowDelaySps.rewrite(fields.nal())!!
        for (index in 1 until rewritten.size - 2) {
            assertFalse(rewritten[index].toInt() == 0 && rewritten[index + 1].toInt() == 0 &&
                (rewritten[index + 2].toInt() and 0xff) <= 2)
        }
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun prefix(nal: ByteArray) = byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
    }

    private data class Restriction(
        val reorder: Long,
        val decBuffering: Long,
        val mvHorizontal: Long = 15,
        val mvVertical: Long = 15,
    )

    private data class Vui(
        val aspectIdc: Int? = null,
        val sarWidth: Int = 0,
        val sarHeight: Int = 0,
        val timing: LongArray? = null,
        val colour: IntArray? = null,
        val hrd: Boolean = false,
        val restriction: Restriction? = null,
    )

    /** An SPS written field by field from H.264 7.3.2.1.1 and E.1.1, as an independent oracle. */
    private data class SpsFields(
        val profile: Int,
        val level: Int,
        val refFrames: Long,
        val widthMbs: Long,
        val heightMbs: Long,
        val crop: IntArray? = null,
        val vui: Vui? = null,
        val scaling: Boolean = false,
        val pocType: Int = 0,
        val frameNumBits: Long = 4,
    ) {
        fun nal(): ByteArray {
            val w = Bits()
            w.u(profile.toLong(), 8); w.u(0, 8); w.u(level.toLong(), 8); w.ue(0)
            if (profile == 100) {
                w.ue(1); w.ue(0); w.ue(0); w.u(0, 1)
                w.u(if (scaling) 1 else 0, 1)
                if (scaling) repeat(8) { list ->
                    val present = list == 0 || list == 6
                    w.u(if (present) 1 else 0, 1)
                    if (present) repeat(if (list < 6) 16 else 64) { w.se(if (it == 0) 4 else 0) }
                }
            }
            w.ue(frameNumBits)
            w.ue(pocType.toLong())
            if (pocType == 0) w.ue(2)
            if (pocType == 1) { w.u(0, 1); w.se(-2); w.se(1); w.ue(2); w.se(3); w.se(-1) }
            w.ue(refFrames); w.u(0, 1); w.ue(widthMbs - 1); w.ue(heightMbs - 1)
            w.u(1, 1); w.u(1, 1)
            w.u(if (crop != null) 1 else 0, 1)
            crop?.forEach { w.ue(it.toLong()) }
            w.u(if (vui != null) 1 else 0, 1)
            vui?.let { v ->
                w.u(if (v.aspectIdc != null) 1 else 0, 1)
                v.aspectIdc?.let { w.u(it.toLong(), 8); if (it == 255) { w.u(v.sarWidth.toLong(), 16); w.u(v.sarHeight.toLong(), 16) } }
                w.u(0, 1)
                w.u(if (v.colour != null) 1 else 0, 1)
                v.colour?.let { w.u(5, 3); w.u(0, 1); w.u(1, 1); it.forEach { c -> w.u(c.toLong(), 8) } }
                w.u(0, 1)
                w.u(if (v.timing != null) 1 else 0, 1)
                v.timing?.let { w.u(it[0], 32); w.u(it[1], 32); w.u(1, 1) }
                w.u(if (v.hrd) 1 else 0, 1)
                if (v.hrd) { w.ue(0); w.u(4, 4); w.u(6, 4); w.ue(9999); w.ue(19999); w.u(0, 1); w.u(23, 5); w.u(23, 5); w.u(23, 5); w.u(24, 5) }
                w.u(0, 1)
                if (v.hrd) w.u(0, 1)
                w.u(0, 1)
                w.u(if (v.restriction != null) 1 else 0, 1)
                v.restriction?.let { r -> w.u(1, 1); w.ue(2); w.ue(1); w.ue(r.mvHorizontal); w.ue(r.mvVertical); w.ue(r.reorder); w.ue(r.decBuffering) }
            }
            w.u(1, 1)
            return byteArrayOf(0x67) + escape(w.bytes())
        }

        private fun escape(rbsp: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            var zeros = 0
            for (b in rbsp) {
                if (zeros >= 2 && (b.toInt() and 0xff) <= 3) { out += 3; zeros = 0 }
                out += b
                zeros = if (b.toInt() == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }

    private class Bits {
        private val bits = StringBuilder()
        fun u(value: Long, count: Int) { for (s in count - 1 downTo 0) bits.append(if ((value shr s) and 1L == 1L) '1' else '0') }
        fun ue(value: Long) { val code = value + 1; val length = 63 - java.lang.Long.numberOfLeadingZeros(code); u(0, length); u(code, length + 1) }
        fun se(value: Long) = ue(if (value > 0) value * 2 - 1 else -value * 2)
        fun bytes(): ByteArray {
            while (bits.length % 8 != 0) bits.append('0')
            return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        }
    }
}
