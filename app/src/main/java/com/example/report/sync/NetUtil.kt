package com.example.report.sync

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale

/**
 * Pairing-code and network helpers.
 *
 * TODO(decomp): only [rnd] and [normCode] survived as real members; [genCode], [prettyCode]
 *  and [localIp] were inlined by R8 into ReportViewModel / NeedsKt / SyncManager (the
 *  `NetUtil.rnd` reads left at those sites are the object-init markers). Their bodies are
 *  taken verbatim from the inlined code; names are reconstructed.
 */
object NetUtil {
    val rnd = SecureRandom()

    /** Alphabet without look-alikes (no I, L, O, 0, 1). */
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Upper-cases and strips everything that is not a letter or digit. */
    fun normCode(code: String): String =
        code.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** 16-char random hub pairing code. */
    fun genCode(): String = buildString {
        repeat(16) { append(CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)]) }
    }

    /** `ABCDEFGH…` → `ABCD-EFGH-…`. */
    fun prettyCode(code: String): String = normCode(code).chunked(4).joinToString("-")

    /** First up, non-loopback, site-local IPv4 address, or `"—"`. */
    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress ?: "—"
    } catch (e: Exception) {
        "—"
    }
}
