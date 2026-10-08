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
            demand(digest == "62a054a4e4f667f7c95a0dd774f7a81caa0e8f1145c4b964e017cf80f007cc54", "FIXTURE_DIGEST_MISMATCH")
            val units = splitAnnexB(data)
            val sps = units.first { it[0].toInt() and 31 == 7 }
            val pps = units.first { it[0].toInt() and 31 == 8 }
            val config = ByteArrayOutputStream().apply {
                write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
                writeSize(sps.size); write(sps); write(1); writeSize(pps.size); write(pps)
            }.toByteArray()
            val frames = units.filter { it[0].toInt() and 31 == 5 }.map { unit ->
                ByteArrayOutputStream().apply {
                    for (shift in listOf(24, 16, 8, 0)) write(unit.size ushr shift and 255)
                    write(unit)
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
