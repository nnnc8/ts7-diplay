package com.shilapi.xcertplay.airplay

import java.io.File
import java.util.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with DIPLAY_GENERATE_BC_VECTORS=1 to regenerate the native C cross-check fixture. */
class BcVectorGeneratorTest {
    @Test fun generateBcVectorsWhenEnabled() {
        assumeTrue("Fixture generation is disabled by default", System.getenv(GENERATE_ENV) == "1")

        val random = Random(0x8439L)
        val lengths = mutableListOf(0, 1, 15, 16, 17, 63, 64, 65, 6000)
        repeat(60 - lengths.size) { lengths += random.nextInt(6001) }
        val lines = buildList {
            add("# key nonce aad plaintext ciphertext_and_tag; generated with BouncyCastle")
            lengths.forEachIndexed { index, plaintextLength ->
                val key = ByteArray(32).also(random::nextBytes)
                val nonce = ByteArray(12).also(random::nextBytes)
                val aadLength = if (index < 41) index else random.nextInt(41)
                val aad = ByteArray(aadLength).also(random::nextBytes)
                val plaintext = ByteArray(plaintextLength).also(random::nextBytes)
                val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plaintext, aad)
                add(listOf(key, nonce, aad, plaintext, sealed).joinToString(" ") { it.hexOrDash() })
            }
        }
        val fixture = File(repositoryRoot(), "shared/src/test/native/bc_vectors.txt")
        check(fixture.parentFile?.mkdirs() == true || fixture.parentFile?.isDirectory == true)
        fixture.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
        check(lengths.size == 60)
    }

    private fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory.parentFile != null) {
            if (File(directory, "shared/src/main/jni/Android.mk").isFile) return directory
            directory = checkNotNull(directory.parentFile)
        }
        error("Could not locate the DiPlay repository root")
    }

    private fun ByteArray.hexOrDash(): String = if (isEmpty()) "-" else joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { const val GENERATE_ENV = "DIPLAY_GENERATE_BC_VECTORS" }
}
