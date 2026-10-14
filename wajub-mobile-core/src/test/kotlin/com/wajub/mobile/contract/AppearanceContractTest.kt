package com.wajub.mobile.contract

import com.wajub.mobile.checkout.Appearance
import com.wajub.mobile.checkout.AppearanceResolver
import com.wajub.mobile.checkout.AppearanceVariables
import com.wajub.mobile.checkout.InheritedTheme
import com.wajub.mobile.checkout.ThemeTokens
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.model.Branding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearanceContractTest {
    private fun branding(name: String): Branding = RawSessionResponse.fromJson(Fixtures.body(name)).toModel().brand

    private fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    @Test
    fun `AP-01 parses the appearance object`() {
        val appearance = Appearance.fromMap(
            mapOf(
                "theme" to "stripe",
                "colorScheme" to "dark",
                "inherit" to false,
                "variables" to mapOf(
                    "colorPrimary" to "#7C3AED",
                    "colorBackground" to "rgb(15, 23, 42)",
                    "colorText" to "#f8fafc",
                    "fontSizeBase" to "1rem",
                    "fontWeightBold" to "bold",
                    "spacingUnit" to 3,
                    "borderRadius" to "40px",
                    "spacingGridRow" to "-8px",
                    "colorDanger" to "crimson",
                    "unknownVariable" to "#000",
                ),
                "primaryColor" to "#123",
                "buttonTextColor" to "#ffffffcc",
                "shadow" to "high",
                "labels" to "floating",
                "disableAnimations" to true,
                "rules" to mapOf(".Input" to mapOf("color" to "red")),
                "notAKey" to 42,
                "inputBorderColor" to listOf("#fff"),
            ),
        )
        assertEquals("stripe", appearance.theme)
        assertEquals("dark", appearance.colorScheme)
        assertEquals("#7C3AED", appearance.variables?.colorPrimary)
        assertEquals("3", appearance.variables?.spacingUnit)
        assertNull(appearance.inputBorderColor, "a value of the wrong type is dropped")

        val resolved = AppearanceResolver.resolve(appearance, null, systemDark = false)
        assertTrue(resolved.dark)
        assertEquals("#7C3AED", hex(resolved.colors.primary), "variables win")
        assertEquals("#0F172A", hex(resolved.colors.background))
        assertEquals(0xCCFFFFFF.toInt(), resolved.colors.onPrimary, "#rrggbbaa")
        assertEquals(16f, resolved.fontSizeBase)
        assertEquals(700, resolved.fontWeightBold)
        assertEquals(3f, resolved.spacingUnit)
        assertEquals(22f, resolved.radius, "capped at 22")
        assertEquals(12f, resolved.gapRow, "a negative length is ignored: 4 × spacingUnit")
        assertEquals(ThemeTokens.DARK.danger, resolved.colors.danger, "named colours are ignored")
        assertEquals(8f, resolved.elevation)
        assertTrue(resolved.floatingLabels)
        assertTrue(resolved.disableAnimations)
    }

    @Test
    fun `AP-02 resolves token precedence`() {
        val merchant = branding("session-cm-aggregate")
        fun primary(appearance: Appearance?, inherited: InheritedTheme? = null) =
            hex(AppearanceResolver.resolve(appearance, merchant, systemDark = false, inherited).colors.primary)

        assertEquals("#2563EB", hex(AppearanceResolver.resolve(null, null, false).colors.primary), "default palette")
        assertEquals("#0F766E", primary(null), "merchant branding")
        val inherited = InheritedTheme(primary = 0xFF9333EA.toInt())
        assertEquals("#9333EA", primary(Appearance(inherit = true), inherited), "inherited over branding")
        assertEquals("#0570DE", primary(Appearance(theme = "stripe", inherit = true), inherited), "preset over inherited")
        assertEquals("#111111", primary(Appearance(theme = "stripe", primaryColor = "#111111")), "legacy key over preset")
        assertEquals(
            "#222222",
            primary(Appearance(theme = "stripe", primaryColor = "#111111", variables = AppearanceVariables(colorPrimary = "#222222"))),
            "variables over legacy keys",
        )
        // Same order for a non-colour token.
        assertEquals(12f, AppearanceResolver.resolve(null, merchant, false).radius)
        assertEquals(4f, AppearanceResolver.resolve(Appearance(theme = "stripe"), merchant, false).radius)
        assertEquals(6f, AppearanceResolver.resolve(Appearance(theme = "stripe", borderRadius = "6px"), merchant, false).radius)
    }

    @Test
    fun `AP-03 resolves the colour scheme`() {
        val light = branding("session-cm-aggregate")
        assertEquals("light", light.appearanceMode)
        assertFalse(AppearanceResolver.isDark(null, light, systemDark = true), "the merchant's light mode beats the OS")
        assertTrue(AppearanceResolver.isDark(Appearance(colorScheme = "dark"), light, systemDark = false))
        assertTrue(AppearanceResolver.isDark(Appearance(theme = "dark"), light, systemDark = false))
        assertTrue(AppearanceResolver.isDark(Appearance(theme = "night"), light, systemDark = false), "night is dark")
        assertTrue(AppearanceResolver.isDark(Appearance(colorScheme = "auto"), light, systemDark = true), "auto follows the OS")
        assertFalse(AppearanceResolver.isDark(Appearance(theme = "system"), light, systemDark = false))
        assertTrue(AppearanceResolver.isDark(Appearance(inherit = true), light, systemDark = false, InheritedTheme(dark = true)), "inherited over branding")
        val none = branding("session-ci-xof-wallets")
        assertTrue(AppearanceResolver.isDark(null, none, systemDark = true))
        assertFalse(AppearanceResolver.isDark(null, none, systemDark = false))
        assertTrue(AppearanceResolver.isDark(null, none.copy(appearanceMode = "system"), systemDark = true), "system is no preference")
    }

    @Test
    fun `AP-04 uses the default palettes`() {
        val plain = branding("session-ci-xof-wallets")
        val expected = mapOf(
            false to listOf(
                "#2563EB", "#1D4ED8", "#FFFFFF", "#FFFFFF", "#FFFFFF", "#F5F5F5", "#EFF6FF", "#171717", "#525252", "#737373",
                "#A3A3A3", "#2563EB", "#FFFFFF", "#E5E5E5", "#D4D4D4", "#3B82F6", "#B91C1C", "#EF4444", "#FEF2F2", "#15803D",
                "#F0FDF4", "#B45309", "#FFFBEB", "#F5F5F5",
            ),
            true to listOf(
                "#3B82F6", "#60A5FA", "#0A0A0A", "#0A0A0A", "#171717", "#262626", "#172554", "#FAFAFA", "#A3A3A3", "#8A8A8A",
                "#525252", "#60A5FA", "#0A0A0A", "#404040", "#525252", "#60A5FA", "#F87171", "#F87171", "#450A0A", "#4ADE80",
                "#052E16", "#FBBF24", "#451A03", "#262626",
            ),
        )
        for ((dark, palette) in expected) {
            val c = AppearanceResolver.resolve(null, plain, systemDark = dark).colors
            val actual = listOf(
                c.primary, c.primaryPressed, c.onPrimary, c.background, c.surfaceRaised, c.surfaceMuted, c.selectedBackground,
                c.text, c.textSecondary, c.textPlaceholder, c.textDisabled, c.link, c.inputBackground, c.inputBorder,
                c.borderStrong, c.focusRing, c.danger, c.dangerBorder, c.dangerBackground, c.success, c.successBackground,
                c.warning, c.warningBackground, c.disabledBackground,
            ).map(::hex)
            assertEquals(palette, actual, if (dark) "dark" else "light")
        }
        val shape = AppearanceResolver.resolve(null, null, false)
        assertEquals(8f, shape.radius)
        assertEquals(16f, shape.fontSizeBase)
        assertEquals(listOf(400, 500, 600), listOf(shape.fontWeightNormal, shape.fontWeightMedium, shape.fontWeightBold))
        assertEquals(4f, shape.spacingUnit)
    }

    @Test
    fun `AP-05 applies merchant branding`() {
        val merchant = branding("session-cm-aggregate")
        val light = AppearanceResolver.resolve(null, merchant, systemDark = false)
        val c = light.colors
        assertEquals("#0F766E", hex(c.primary))
        assertEquals("#FFFFFF", hex(c.onPrimary))
        assertEquals("#FFFFFF", hex(c.background))
        assertEquals("#F0FDFA", hex(c.surfaceMuted))
        assertEquals("#6B7280", hex(c.textSecondary))
        assertEquals("#16A34A", hex(c.success))
        assertEquals("#DC2626", hex(c.danger))
        assertEquals("#D1D5DB", hex(c.inputBorder))
        assertEquals(12f, light.radius)
        assertEquals("Poppins", light.fontFamily)
        assertEquals(600, light.fontWeightBold)
        assertEquals(400, light.fontWeightNormal)
        assertEquals(1f, light.elevation)
        assertFalse(light.showWajubBadge, "white_label: true hides Secured by Wajub")
        assertEquals(0.4f, light.scrimAlpha)
        assertTrue(AppearanceResolver.resolve(null, branding("session-ci-xof-wallets"), false).showWajubBadge)

        // Surface colours designed for light are not used in dark.
        val dark = AppearanceResolver.resolve(Appearance(colorScheme = "dark"), merchant, systemDark = false).colors
        assertEquals(ThemeTokens.DARK.background, dark.background)
        assertEquals(ThemeTokens.DARK.surfaceMuted, dark.surfaceMuted)
        assertEquals("#0F766E", hex(dark.primary))

        // A primary that does not stand out (under 3:1) falls back to the default.
        val pale = AppearanceResolver.resolve(null, merchant.copy(primaryColor = "#FFFF00"), systemDark = false)
        assertEquals(ThemeTokens.LIGHT.primary, pale.colors.primary)
        // border_radius tokens.
        assertEquals(16f, AppearanceResolver.resolve(null, merchant.copy(borderRadius = "xl"), false).radius)
        assertEquals(0f, AppearanceResolver.resolve(null, merchant.copy(borderRadius = "none"), false).radius)
        // onPrimary, unset, follows the primary's contrast.
        val yellow = AppearanceResolver.resolve(Appearance(primaryColor = "#FACC15"), null, false)
        assertEquals("#0A0A0A", hex(yellow.colors.onPrimary))
    }

    @Test
    fun `AP-06 applies presets`() {
        val stripe = AppearanceResolver.preset("stripe")!!
        assertEquals("#0570de", stripe.primaryColor)
        assertEquals("4px", stripe.borderRadius)
        assertEquals("-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif", stripe.fontFamily)
        assertEquals("low", stripe.shadow)
        val night = AppearanceResolver.preset("night")!!
        assertEquals(
            Appearance(
                colorScheme = "dark", primaryColor = "#7c3aed", backgroundColor = "#0f172a", buttonTextColor = "#f8fafc",
                inputBackgroundColor = "#1e293b", inputBorderColor = "#334155", textMutedColor = "#94a3b8",
                borderRadius = "8px", shadow = "none",
            ),
            night,
        )
        assertEquals(Appearance(shadow = "none", borderRadius = "0"), AppearanceResolver.preset("flat"))
        assertEquals(Appearance(), AppearanceResolver.preset("none"))

        val resolved = AppearanceResolver.resolve(Appearance(theme = "night"), null, systemDark = false)
        assertTrue(resolved.dark)
        assertEquals("#0F172A", hex(resolved.colors.background))
        assertEquals("#1E293B", hex(resolved.colors.inputBackground))
        assertEquals(0f, resolved.elevation)
        assertEquals(0f, AppearanceResolver.resolve(Appearance(theme = "flat"), null, false).radius)
    }
}
