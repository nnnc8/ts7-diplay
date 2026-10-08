// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.baseline

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Test-only, self-generated red frames. No phone capture, keys or borrowed receiver code. */
internal data class SyntheticAvc(val config: ByteArray, val frames: List<ByteArray>) {
    companion object {
        fun load(context: Context): SyntheticAvc {
            val encoded = context.assets.open("ts7-baseline/red.h264.base64").use { it.readBytes() }
            val data = Base64.decode(encoded, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            demand(digest == "167967c78c074f7e3fc9335d9374c359703da9f0e8e64cea328867e8521d7f9e", "FIXTURE_DIGEST_MISMATCH")
            val units = splitAnnexB(data)
            val sps = units.first { it[0].toInt() and 31 == 7 }
            val pps = units.first { it[0].toInt() and 31 == 8 }
            val config = ByteArrayOutputStream().apply {
                write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
                writeSize(sps.size); write(sps); write(1); writeSize(pps.size); write(pps)
            }.toByteArray()
            // Preserve encoder AU boundaries and in-band headers, not isolated VCL slices.
            val accessUnits = mutableListOf<MutableList<ByteArray>>()
            for (unit in units) {
                if (unit[0].toInt() and 31 == 9) accessUnits.add(mutableListOf())
                demand(accessUnits.isNotEmpty(), "FIXTURE_ACCESS_UNIT_DELIMITER_MISSING")
                accessUnits.last().add(unit)
            }
            val frames = accessUnits.mapIndexed { index, accessUnit ->
                val expectedTypes = if (index == 0) listOf(9, 7, 8, 5) else listOf(9, 1)
                demand(accessUnit.map { it[0].toInt() and 31 } == expectedTypes, "FIXTURE_ACCESS_UNIT_STRUCTURE_INVALID")
                ByteArrayOutputStream().apply {
                    for (unit in accessUnit) {
                        for (shift in listOf(24, 16, 8, 0)) write(unit.size ushr shift and 255)
                        write(unit)
                    }
                }.toByteArray()
            }
            demand(frames.size == 12, "FIXTURE_FRAME_COUNT_MISMATCH")
            return SyntheticAvc(config, frames)
        }

        private fun ByteArrayOutputStream.writeSize(size: Int) {
            write(size ushr 8 and 255); write(size and 255)
        }

        private fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var cursor = 0
            while (cursor + 3 < data.size) {
                val length = when {
                    data[cursor] == 0.toByte() && data[cursor + 1] == 0.toByte() &&
                        data[cursor + 2] == 0.toByte() && data[cursor + 3] == 1.toByte() -> 4
                    data[cursor] == 0.toByte() && data[cursor + 1] == 0.toByte() && data[cursor + 2] == 1.toByte() -> 3
                    else -> 0
                }
                if (length > 0) { starts.add(cursor to length); cursor += length } else cursor++
            }
            return starts.mapIndexed { index, (offset, prefix) ->
                data.copyOfRange(offset + prefix, starts.getOrNull(index + 1)?.first ?: data.size)
            }.filter { it.isNotEmpty() }
        }
    }
}
