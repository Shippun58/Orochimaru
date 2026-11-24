package ani.sanin.plugins.nuvio

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val nuvioSecureRandom = SecureRandom()

internal fun nuvioGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    return ByteArray(length).also { nuvioSecureRandom.nextBytes(it) }
}

internal fun nuvioDigest(algorithm: String, data: ByteArray): ByteArray =
    MessageDigest.getInstance(nuvioDigestAlgorithm(algorithm)).digest(data)

internal fun nuvioPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0)
    require(keySizeBits > 0 && keySizeBits % 8 == 0)
    val prf = nuvioHmacAlgorithm(algorithm)
    val mac = Mac.getInstance(prf).apply { init(SecretKeySpec(password, prf)) }
    val hLen = mac.macLength
    val dkLen = keySizeBits / 8
    val dk = ByteArray(dkLen)
    val blocks = (dkLen + hLen - 1) / hLen
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    val indexBytes = ByteArray(4)
    for (i in 1..blocks) {
        mac.reset()
        mac.update(salt)
        indexBytes[0] = (i ushr 24).toByte()
        indexBytes[1] = (i ushr 16).toByte()
        indexBytes[2] = (i ushr 8).toByte()
        indexBytes[3] = i.toByte()
        mac.update(indexBytes)
        val first = mac.doFinal()
        first.copyInto(t)
        first.copyInto(u)
        for (j in 2..iterations) {
            mac.reset()
            val next = mac.doFinal(u)
            next.copyInto(u)
            for (k in 0 until hLen) t[k] = (t[k].toInt() xor next[k].toInt()).toByte()
        }
        val offset = (i - 1) * hLen
        t.copyInto(dk, destinationOffset = offset, startIndex = 0, endIndex = minOf(hLen, dkLen - offset))
    }
    return dk
}

internal fun nuvioAesEncrypt(mode: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    nuvioAes(Cipher.ENCRYPT_MODE, mode, key, iv, data)

internal fun nuvioAesDecrypt(mode: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    nuvioAes(Cipher.DECRYPT_MODE, mode, key, iv, data)

internal fun nuvioSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    val (keyAlgorithm, signatureAlgorithm) = nuvioSignatureAlgorithms(algorithm)
    val key = KeyFactory.getInstance(keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(privateKey))
    return Signature.getInstance(signatureAlgorithm).run {
        initSign(key)
        update(data)
        sign()
    }
}

internal fun nuvioVerify(
    algorithm: String,
    publicKey: ByteArray,
    signature: ByteArray,
    data: ByteArray,
): Boolean {
    val (keyAlgorithm, signatureAlgorithm) = nuvioSignatureAlgorithms(algorithm)
    val key = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(publicKey))
    return Signature.getInstance(signatureAlgorithm).run {
        initVerify(key)
        update(data)
        verify(signature)
    }
}

internal fun nuvioHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val normalized = nuvioHmacAlgorithm(algorithm)
    return Mac.getInstance(normalized).run {
        init(SecretKeySpec(key, normalized))
        doFinal(data)
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun nuvioBase64Encode(value: String): String = Base64.encode(value.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun nuvioBase64Decode(value: String): String {
    var normalized = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        .replace("-", "+").replace("_", "/")
    val pad = (4 - normalized.length % 4) % 4
    if (pad > 0) normalized += "=".repeat(pad)
    return Base64.decode(normalized).decodeToString()
}

internal fun nuvioUtf8ToHex(value: String): String = value.encodeToByteArray().toHex()

internal fun nuvioHexToByteArray(hex: String): ByteArray {
    val value = hex.trim().lowercase().replace(" ", "").removePrefix("0x")
    if (value.isEmpty()) return ByteArray(0)
    val even = if (value.length % 2 == 0) value else "0$value"
    return ByteArray(even.length / 2) { even.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun nuvioHexToUtf8(hex: String): String = nuvioHexToByteArray(hex).decodeToString()

internal fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

private fun nuvioAes(mode: Int, transformation: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val normalized = transformation.normalizedAlgorithm()
    require(key.size == 16 || key.size == 24 || key.size == 32)
    val cipher = Cipher.getInstance(
        when {
            normalized.contains("GCM") -> "AES/GCM/NoPadding"
            normalized.contains("ECB") -> "AES/ECB/${if (normalized.contains("NOPADDING")) "NoPadding" else "PKCS5Padding"}"
            else -> "AES/CBC/${if (normalized.contains("NOPADDING")) "NoPadding" else "PKCS5Padding"}"
        }
    )
    val keySpec = SecretKeySpec(key, "AES")
    when {
        normalized.contains("ECB") -> cipher.init(mode, keySpec)
        normalized.contains("GCM") -> cipher.init(mode, keySpec, GCMParameterSpec(128, iv))
        else -> cipher.init(mode, keySpec, IvParameterSpec(iv))
    }
    return cipher.doFinal(data)
}

private fun nuvioDigestAlgorithm(algorithm: String): String = when (algorithm.normalizedAlgorithm()) {
    "MD5" -> "MD5"
    "SHA1" -> "SHA-1"
    "SHA256" -> "SHA-256"
    "SHA384" -> "SHA-384"
    "SHA512" -> "SHA-512"
    else -> error("Unsupported digest algorithm: $algorithm")
}

private fun nuvioHmacAlgorithm(algorithm: String): String = when (
    algorithm.normalizedAlgorithm().removePrefix("HMAC")
) {
    "MD5" -> "HmacMD5"
    "SHA1" -> "HmacSHA1"
    "SHA256" -> "HmacSHA256"
    "SHA384" -> "HmacSHA384"
    "SHA512" -> "HmacSHA512"
    else -> error("Unsupported HMAC algorithm: $algorithm")
}

private fun nuvioSignatureAlgorithms(algorithm: String): Pair<String, String> = when (
    algorithm.uppercase()
) {
    "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
    "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
    else -> "RSA" to "SHA256withRSA"
}

private fun String.normalizedAlgorithm(): String = uppercase()
    .replace("-", "")
    .replace("_", "")
    .replace("/", "")
    .replace(" ", "")
