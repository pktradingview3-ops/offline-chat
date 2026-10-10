package com.offlinechat.crypto

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PRD Section 8 — X25519 (ECDH) + HKDF-SHA256 + AES-256-GCM.
 *
 * Web app (Web Crypto API + tweetnacl.scalarMult) ke saath byte-for-byte interoperable hai,
 * kyunki X25519 aur HKDF dono standardized algorithms hain (RFC 7748 / RFC 5869) — implementation
 * library kuch bhi ho, output same hoga.
 *
 * Koi bhi plaintext, key, ya intermediate value is object ke bahar kabhi persist/log nahi hota.
 */
object CryptoBox {

    // web-app/src/crypto.ts me bhi ye EXACT same string honi chahiye — HKDF context binding.
    private val HKDF_INFO = "offline-chat-e2ee".toByteArray(Charsets.UTF_8)
    private val HKDF_SALT = ByteArray(0)

    data class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = generator.generateKeyPair()
        val privateKey = kp.private as X25519PrivateKeyParameters
        val publicKey = kp.public as X25519PublicKeyParameters
        return KeyPair(publicKey.encoded, privateKey.encoded)
    }

    fun publicKeyToBase64(pk: ByteArray): String = Base64.encodeToString(pk, Base64.NO_WRAP)

    fun base64ToPublicKey(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)

    private fun deriveAesKey(mySecretKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val privateParams = X25519PrivateKeyParameters(mySecretKey, 0)
        val publicParams = X25519PublicKeyParameters(theirPublicKey, 0)

        val agreement = X25519Agreement()
        agreement.init(privateParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicParams, sharedSecret, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, HKDF_SALT, HKDF_INFO))
        val aesKey = ByteArray(32)
        hkdf.generateBytes(aesKey, 0, 32)
        return aesKey
    }

    fun encryptMessage(plainText: String, theirPublicKey: ByteArray, mySecretKey: ByteArray): Pair<String, String> {
        val aesKey = deriveAesKey(mySecretKey, theirPublicKey)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) to Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decryptMessage(nonceB64: String, ciphertextB64: String, theirPublicKey: ByteArray, mySecretKey: ByteArray): String? {
        return try {
            val aesKey = deriveAesKey(mySecretKey, theirPublicKey)
            val iv = Base64.decode(nonceB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null // tampered, galat key, ya corrupt data — reject
        }
    }
}
