package com.shilapi.xcertplay.airplay

import javax.crypto.AEADBadTagException

internal interface NativeChaChaBackend {
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray
}

/** Thin wrapper around the portable C implementation. */
internal object NativeChaCha20Poly1305 : NativeChaChaBackend {
    private val initializedAndTested by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary("native_chacha20poly1305")
            check(runSelfTest())
        }.isSuccess
    }

    fun availableBackend(): NativeChaChaBackend? = if (initializedAndTested) this else null

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        val output = ByteArray(plaintext.size + TAG_SIZE)
        val written = nativeSeal(key, nonce, plaintext, 0, plaintext.size, aad, 0, aad.size, output)
        check(written == output.size) { "Native ChaCha20-Poly1305 seal failed" }
        return output
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        if (ciphertextAndTag.size < TAG_SIZE) throw AEADBadTagException("Ciphertext is shorter than its tag")
        val output = ByteArray(ciphertextAndTag.size - TAG_SIZE)
        val written = nativeOpen(
            key, nonce, ciphertextAndTag, 0, ciphertextAndTag.size, aad, 0, aad.size, output,
        )
        if (written < 0) throw AEADBadTagException("ChaCha20-Poly1305 tag check failed")
        check(written == output.size) { "Native ChaCha20-Poly1305 open returned an invalid length" }
        return output
    }

    private fun runSelfTest(): Boolean {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
        )
        val sealed = ByteArray(plaintext.size + TAG_SIZE)
        if (nativeSeal(key, nonce, plaintext, 0, plaintext.size, aad, 0, aad.size, sealed) != sealed.size ||
            !sealed.contentEquals(expected)) return false
        val opened = ByteArray(plaintext.size)
        return nativeOpen(key, nonce, sealed, 0, sealed.size, aad, 0, aad.size, opened) == opened.size &&
            opened.contentEquals(plaintext)
    }

    private external fun nativeSeal(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        output: ByteArray,
    ): Int

    private external fun nativeOpen(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        output: ByteArray,
    ): Int

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
}
