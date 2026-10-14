package com.wajub.mobile.checkout

import com.wajub.mobile.model.AltchaChallenge
import java.security.MessageDigest

/** A solved Altcha challenge, sent as `data.altcha` / `altcha`. */
data class AltchaSolution(
    val algorithm: String,
    val challenge: String,
    val number: Long,
    val salt: String,
    val signature: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "algorithm" to algorithm,
        "challenge" to challenge,
        "number" to number,
        "salt" to salt,
        "signature" to signature,
    )
}

/** The proof of work some sessions ask for before paying (spec §15). */
object Altcha {
    /** The `n` in `[0, maxnumber]` whose `SHA-256(salt + n)` is the challenge, or null. */
    fun solve(challenge: AltchaChallenge): AltchaSolution? {
        val digest = MessageDigest.getInstance("SHA-256")
        val target = challenge.challenge.lowercase()
        for (n in 0..challenge.maxNumber) {
            val hash = digest.digest((challenge.salt + n).toByteArray(Charsets.UTF_8))
            if (hex(hash) == target) {
                return AltchaSolution(challenge.algorithm, challenge.challenge, n, challenge.salt, challenge.signature)
            }
        }
        return null
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
