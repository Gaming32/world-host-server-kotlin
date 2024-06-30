package io.github.gaming32.worldhostserver.util

import java.security.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object MinecraftCrypt {
    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(1024)
        generateKeyPair()
    }

    fun digestData(id: String, publicKey: PublicKey, secretKey: SecretKey) =
        digestData(id.toByteArray(Charsets.ISO_8859_1), secretKey.encoded, publicKey.encoded)

    fun digestData(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").apply {
            parts.forEach { update(it) }
        }.digest()

    fun decryptByteToSecretKey(key: PrivateKey, data: ByteArray) = SecretKeySpec(decryptUsingKey(key, data), "AES")

    fun decryptUsingKey(key: Key, data: ByteArray) = cipherData(2, key, data)

    private fun cipherData(mode: Int, key: Key, data: ByteArray): ByteArray =
        setupCipher(mode, key.algorithm, key).doFinal(data)

    private fun setupCipher(index: Int, algorithm: String, key: Key) = Cipher.getInstance(algorithm).apply {
        init(index, key)
    }
}
