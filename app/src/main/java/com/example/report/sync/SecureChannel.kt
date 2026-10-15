package com.example.report.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-connection authenticated encryption keyed by the pairing code.
 *
 * ## Key derivation
 * ```
 * base  = SHA-256( UTF-8( "REPORTSYNC2|" + NetUtil.normCode(code) ) )        // 32 bytes
 * kC2H  = HMAC-SHA256( key = base, "c2h" || nonceC || nonceH )               // AES-256 key
 * kH2C  = HMAC-SHA256( key = base, "h2c" || nonceC || nonceH )
 * ```
 * `nonceC`/`nonceH` are the 16-byte nonces exchanged as `N2:` frames (client's first).
 * Hub sends with kH2C / receives with kC2H; client the opposite.
 * There is no separate MAC handshake: a wrong code simply makes `open()` fail on the hub,
 * which then answers `X2:Невірний код`.
 *
 * ## Frame format
 * ```
 * "E2:" + base64( ctr[8, big-endian] || AES-GCM-ciphertext || tag[16] )
 * IV  = 00 00 00 00 || ctr[8, big-endian]          // 12 bytes, no AAD
 * ```
 * Each direction has its own counter starting at 1. The receiver accepts only strictly
 * increasing counters (replay/reorder protection); any failure makes [open] return `null`.
 */
class SecureChannel(code: String, nonceC: ByteArray, nonceH: ByteArray, isHub: Boolean) {

    private val sendKey: SecretKeySpec
    private val recvKey: SecretKeySpec
    private var sendCtr = 0L
    private var recvCtr = 0L

    init {
        val base = MessageDigest.getInstance("SHA-256")
            .digest(("REPORTSYNC2|" + NetUtil.normCode(code)).toByteArray(Charsets.UTF_8))
        val c2h = derive(base, "c2h", nonceC, nonceH)
        val h2c = derive(base, "h2c", nonceC, nonceH)
        sendKey = if (isHub) h2c else c2h
        recvKey = if (isHub) c2h else h2c
    }

    @Synchronized
    fun seal(plain: String): String {
        val ctr = ++sendCtr
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, iv(ctr)))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(ct.size + 8).putLong(ctr).put(ct).array()
        return "E2:" + Base64.getEncoder().encodeToString(out)
    }

    @Synchronized
    fun open(frame: String): String? {
        if (!frame.startsWith("E2:")) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(frame.substring(3))
            val ctr = ByteBuffer.wrap(raw, 0, 8).long
            require(ctr > recvCtr)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, iv(ctr)))
            val pt = cipher.doFinal(raw, 8, raw.size - 8)
            recvCtr = ctr
            String(pt, Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        val rnd = SecureRandom()

        // TODO(decomp): newNonce()/iv() were inlined by R8 (only `Companion.getClass()` markers
        //  remain at the call sites); names are reconstructed.
        fun newNonce(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

        private fun iv(ctr: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()

        private fun derive(base: ByteArray, label: String, a: ByteArray, b: ByteArray): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(base, "HmacSHA256"))
            mac.update(label.toByteArray(Charsets.UTF_8))
            mac.update(a)
            mac.update(b)
            return SecretKeySpec(mac.doFinal(), "AES")
        }

        /** Parses an `N2:` frame; returns the nonce only if it is exactly 16 bytes. */
        fun parseNonce(frame: String?): ByteArray? {
            if (frame == null || !frame.startsWith("N2:")) return null
            val bytes = runCatching { Base64.getDecoder().decode(frame.substring(3)) }.getOrNull()
            return bytes?.takeIf { it.size == 16 }
        }
    }
}
