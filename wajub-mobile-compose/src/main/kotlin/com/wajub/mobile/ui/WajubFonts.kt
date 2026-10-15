package com.wajub.mobile.ui

import androidx.compose.ui.text.font.FontFamily
import java.util.concurrent.ConcurrentHashMap

/**
 * The fonts your app ships, by the name the appearance uses.
 *
 * `fontFamily` in [com.wajub.mobile.checkout.Appearance] (and the merchant's
 * font from the dashboard) is a name. The sheet uses the family registered
 * under that name, else a font the system has under it, else the platform's.
 * Fonts in `res/font` or assets reach the sheet only through here:
 *
 * ```kotlin
 * WajubFonts.register(
 *     "Poppins",
 *     FontFamily(Font(R.font.poppins_regular), Font(R.font.poppins_semibold, FontWeight.SemiBold)),
 * )
 * ```
 *
 * Names are compared without case. Register before showing the sheet
 * (in `Application.onCreate`, say).
 */
object WajubFonts {
    private val families = ConcurrentHashMap<String, FontFamily>()

    /** Makes [family] the sheet's font wherever the appearance names [name]. */
    fun register(name: String, family: FontFamily) {
        families[key(name)] = family
    }

    fun unregister(name: String) {
        families.remove(key(name))
    }

    internal fun find(name: String): FontFamily? = families[key(name)]

    private fun key(name: String) = name.trim().lowercase()
}
