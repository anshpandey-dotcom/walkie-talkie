package com.fmc.walkie

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureChannel {

    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val random = SecureRandom()

    @Volatile
    private var key: SecretKeySpec? = null

    val publicKey: ByteArray = keyPair.public.encoded

    var code = ""
        private set

    fun agree(peerKey: ByteArray): Boolean {
        return try {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerKey))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(keyPair.private)
            agreement.doPhase(peer, true)
            val secret = agreement.generateSecret()
            val ordered = if (compare(publicKey, peerKey) < 0) publicKey to peerKey else peerKey to publicKey
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("fmc-radio-v1".toByteArray())
            digest.update(secret)
            digest.update(ordered.first)
            digest.update(ordered.second)
            key = SecretKeySpec(digest.digest(), "AES")
            val fingerprint = MessageDigest.getInstance("SHA-256").run {
                update(ordered.first)
                update(ordered.second)
                digest()
            }
            val number = (ByteBuffer.wrap(fingerprint).int.toLong() and 0xFFFFFFFFL) % 1_000_000
            code = "%03d %03d".format(number / 1000, number % 1000)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    fun seal(plain: ByteArray): ByteArray? {
        val secret = key ?: return null
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plain)
    }

    fun open(data: ByteArray, offset: Int): ByteArray? {
        val secret = key ?: return null
        if (data.size - offset < 28) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, data, offset, 12))
            cipher.doFinal(data, offset + 12, data.size - offset - 12)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
