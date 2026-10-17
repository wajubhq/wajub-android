package com.wajub.mobile.checkout

import com.wajub.mobile.model.AltchaChallenge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/** The answer to a payment's security check. */
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

/** The security check some payments ask for before paying. */
object Altcha {
    /**
     * The `n` in `[0, maxnumber]` whose `SHA-256(salt + n)` is the challenge,
     * or null. CPU-bound: run it off the main thread; it stops when cancelled.
     */
    suspend fun solve(challenge: AltchaChallenge): AltchaSolution? {
        val digest = MessageDigest.getInstance("SHA-256")
        val target = challenge.challenge.lowercase()
        for (n in 0..challenge.maxNumber) {
            if (n % 1_000 == 0L) currentCoroutineContext().ensureActive()
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
