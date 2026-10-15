package com.wajub.mobile.checkout

import com.wajub.mobile.model.Branding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The sheet's look. Values that do not parse are ignored, never an error.
 */
data class Appearance(
    /** `light`, `dark`, `auto`, `system`, or a preset: `stripe`, `night`, `flat`, `none`. */
    val theme: String? = null,
    /** `light`, `dark` or `auto`. */
    val colorScheme: String? = null,
    /** Take the app's own Material theme for what the host does not set. */
    val inherit: Boolean? = null,
    val variables: AppearanceVariables? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val backgroundColor: String? = null,
    val fontFamily: String? = null,
    val borderRadius: String? = null,
    val buttonTextColor: String? = null,
    val inputBackgroundColor: String? = null,
    val inputBorderColor: String? = null,
    val textMutedColor: String? = null,
    val successColor: String? = null,
    val errorColor: String? = null,
    /** `none`, `low` or `high`. */
    val shadow: String? = null,
    /** `above` or `floating`. */
    val labels: String? = null,
    val disableAnimations: Boolean? = null,
) {
    companion object {
        /**
         * The same object as a map (decoded JSON, remote config…): unknown keys
         * and values of the wrong type are dropped.
         */
        fun fromMap(map: Map<String, Any?>): Appearance =
            Appearance(
                theme = map.text("theme"),
                colorScheme = map.text("colorScheme"),
                inherit = map["inherit"] as? Boolean,
                variables = (map["variables"] as? Map<*, *>)?.let { raw ->
                    val v = raw.entries.filter { it.key is String }.associate { it.key as String to it.value }
                    AppearanceVariables(
                        colorPrimary = v.text("colorPrimary"),
                        colorBackground = v.text("colorBackground"),
                        colorText = v.text("colorText"),
                        colorTextSecondary = v.text("colorTextSecondary"),
                        colorTextPlaceholder = v.text("colorTextPlaceholder"),
                        colorDanger = v.text("colorDanger"),
                        colorSuccess = v.text("colorSuccess"),
                        fontFamily = v.text("fontFamily"),
                        fontSizeBase = v.text("fontSizeBase"),
                        fontWeightNormal = v.text("fontWeightNormal"),
                        fontWeightMedium = v.text("fontWeightMedium"),
                        fontWeightBold = v.text("fontWeightBold"),
                        spacingUnit = v.text("spacingUnit"),
                        borderRadius = v.text("borderRadius"),
                        spacingGridRow = v.text("spacingGridRow"),
                        spacingGridColumn = v.text("spacingGridColumn"),
                    )
                },
                primaryColor = map.text("primaryColor"),
                secondaryColor = map.text("secondaryColor"),
                backgroundColor = map.text("backgroundColor"),
                fontFamily = map.text("fontFamily"),
                borderRadius = map.text("borderRadius"),
                buttonTextColor = map.text("buttonTextColor"),
                inputBackgroundColor = map.text("inputBackgroundColor"),
                inputBorderColor = map.text("inputBorderColor"),
                textMutedColor = map.text("textMutedColor"),
                successColor = map.text("successColor"),
                errorColor = map.text("errorColor"),
                shadow = map.text("shadow"),
                labels = map.text("labels"),
                disableAnimations = map["disableAnimations"] as? Boolean,
            )

        // A string, or a number written as text ("8" for 8).
        private fun Map<String, Any?>.text(key: String): String? =
            when (val v = this[key]) {
                is String -> v
                is Number -> v.toString()
                else -> null
            }
    }
}

/** The appearance variables. */
data class AppearanceVariables(
    val colorPrimary: String? = null,
    val colorBackground: String? = null,
    val colorText: String? = null,
    val colorTextSecondary: String? = null,
    val colorTextPlaceholder: String? = null,
    val colorDanger: String? = null,
    val colorSuccess: String? = null,
    val fontFamily: String? = null,
    val fontSizeBase: String? = null,
    val fontWeightNormal: String? = null,
    val fontWeightMedium: String? = null,
    val fontWeightBold: String? = null,
    val spacingUnit: String? = null,
    val borderRadius: String? = null,
    val spacingGridRow: String? = null,
    val spacingGridColumn: String? = null,
)

/** The sheet's colours, ARGB. */
data class ThemeTokens(
    val primary: Int,
    val primaryPressed: Int,
    val onPrimary: Int,
    val background: Int,
    val surfaceRaised: Int,
    val surfaceMuted: Int,
    val selectedBackground: Int,
    val text: Int,
    val textSecondary: Int,
    val textPlaceholder: Int,
    val textDisabled: Int,
    val link: Int,
    val inputBackground: Int,
    val inputBorder: Int,
    val borderStrong: Int,
    val focusRing: Int,
    val danger: Int,
    val dangerBorder: Int,
    val dangerBackground: Int,
    val success: Int,
    val successBackground: Int,
    val warning: Int,
    val warningBackground: Int,
    val disabledBackground: Int,
) {
    companion object {
        /** The default light palette. */
        val LIGHT = ThemeTokens(
            primary = rgb(0x2563EB), primaryPressed = rgb(0x1D4ED8), onPrimary = rgb(0xFFFFFF),
            background = rgb(0xFFFFFF), surfaceRaised = rgb(0xFFFFFF), surfaceMuted = rgb(0xF5F5F5),
            selectedBackground = rgb(0xEFF6FF), text = rgb(0x171717), textSecondary = rgb(0x525252),
            textPlaceholder = rgb(0x737373), textDisabled = rgb(0xA3A3A3), link = rgb(0x2563EB),
            inputBackground = rgb(0xFFFFFF), inputBorder = rgb(0xE5E5E5), borderStrong = rgb(0xD4D4D4),
            focusRing = rgb(0x3B82F6), danger = rgb(0xB91C1C), dangerBorder = rgb(0xEF4444),
            dangerBackground = rgb(0xFEF2F2), success = rgb(0x15803D), successBackground = rgb(0xF0FDF4),
            warning = rgb(0xB45309), warningBackground = rgb(0xFFFBEB), disabledBackground = rgb(0xF5F5F5),
        )

        /** The default dark palette. */
        val DARK = ThemeTokens(
            primary = rgb(0x3B82F6), primaryPressed = rgb(0x60A5FA), onPrimary = rgb(0x0A0A0A),
            background = rgb(0x0A0A0A), surfaceRaised = rgb(0x171717), surfaceMuted = rgb(0x262626),
            selectedBackground = rgb(0x172554), text = rgb(0xFAFAFA), textSecondary = rgb(0xA3A3A3),
            textPlaceholder = rgb(0x8A8A8A), textDisabled = rgb(0x525252), link = rgb(0x60A5FA),
            inputBackground = rgb(0x0A0A0A), inputBorder = rgb(0x404040), borderStrong = rgb(0x525252),
            focusRing = rgb(0x60A5FA), danger = rgb(0xF87171), dangerBorder = rgb(0xF87171),
            dangerBackground = rgb(0x450A0A), success = rgb(0x4ADE80), successBackground = rgb(0x052E16),
            warning = rgb(0xFBBF24), warningBackground = rgb(0x451A03), disabledBackground = rgb(0x262626),
        )

        private fun rgb(value: Int): Int = (0xFF shl 24) or value
    }
}

/** The host app's own theme, read when `inherit` is on (the Compose module reads `MaterialTheme`). */
data class InheritedTheme(
    val dark: Boolean? = null,
    val primary: Int? = null,
    val background: Int? = null,
    val text: Int? = null,
    val textSecondary: Int? = null,
    val inputBorder: Int? = null,
    val fontFamily: String? = null,
    val radius: Float? = null,
)

/** Every token of the sheet, resolved for one colour scheme. Lengths are dp. */
data class ResolvedAppearance(
    val dark: Boolean,
    val colors: ThemeTokens,
    val radius: Float,
    /** The family to use when the app ships it, else the platform's. */
    val fontFamily: String?,
    val fontSizeBase: Float,
    val fontWeightNormal: Int,
    val fontWeightMedium: Int,
    val fontWeightBold: Int,
    val spacingUnit: Float,
    val gapRow: Float,
    val gapColumn: Float,
    /** Sheet elevation: 0, 1 or 8. */
    val elevation: Float,
    val floatingLabels: Boolean,
    val disableAnimations: Boolean,
    /** "Secured by Wajub" (hidden by a white-label merchant). */
    val showWajubBadge: Boolean,
    /** Dividers, when the merchant set a colour. */
    val separator: Int?,
    /** Opacity of the scrim behind the sheet, when the merchant set one. */
    val scrimAlpha: Float?,
)

/**
 * Resolves the sheet's look: for each token, your variables, then your
 * top-level keys, the preset, your app's theme (with `inherit`), the
 * merchant's branding, and the default palette of the scheme.
 */
object AppearanceResolver {
    const val MAX_RADIUS: Float = 22f
    private const val DEFAULT_RADIUS = 8f

    private val PRESETS: Map<String, Appearance> = mapOf(
        "stripe" to Appearance(
            primaryColor = "#0570de",
            borderRadius = "4px",
            fontFamily = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
            shadow = "low",
        ),
        "night" to Appearance(
            colorScheme = "dark",
            primaryColor = "#7c3aed",
            backgroundColor = "#0f172a",
            buttonTextColor = "#f8fafc",
            inputBackgroundColor = "#1e293b",
            inputBorderColor = "#334155",
            textMutedColor = "#94a3b8",
            borderRadius = "8px",
            shadow = "none",
        ),
        "flat" to Appearance(shadow = "none", borderRadius = "0"),
        "none" to Appearance(),
    )

    /** The preset [name] (`stripe`, `night`, `flat`, `none`), or null. */
    fun preset(name: String?): Appearance? = name?.lowercase()?.let { PRESETS[it] }

    /**
     * Dark or not: `colorScheme` (or the scheme `theme` names, or the preset's),
     * then the inherited app theme, then the merchant's `appearance_mode`, then
     * the OS ([systemDark]).
     */
    fun isDark(appearance: Appearance?, branding: Branding?, systemDark: Boolean, inherited: InheritedTheme? = null): Boolean {
        val schemes = setOf("light", "dark", "auto", "system")
        val explicit = appearance?.colorScheme?.lowercase()?.takeIf { it in schemes }
            ?: appearance?.theme?.lowercase()?.takeIf { it in schemes }
            ?: preset(appearance?.theme)?.colorScheme
        when (explicit) {
            "light" -> return false
            "dark" -> return true
            "auto", "system" -> return systemDark
        }
        if (appearance?.inherit == true) inherited?.dark?.let { return it }
        return when (branding?.appearanceMode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
    }

    fun resolve(
        appearance: Appearance?,
        branding: Branding?,
        systemDark: Boolean,
        inherited: InheritedTheme? = null,
    ): ResolvedAppearance {
        val dark = isDark(appearance, branding, systemDark, inherited)
        val defaults = if (dark) ThemeTokens.DARK else ThemeTokens.LIGHT
        val layers = buildList {
            appearance?.variables?.let { add(variablesLayer(it)) }
            appearance?.let { add(keysLayer(it)) }
            preset(appearance?.theme)?.let { add(keysLayer(it)) }
            if (appearance?.inherit == true && inherited != null) add(inheritedLayer(inherited, defaults))
        }
        val merchant = branding?.let { brandingLayer(it, dark) } ?: Layer()

        fun <T> pick(get: (Layer) -> T?): T? = layers.firstNotNullOfOrNull(get)

        val background = pick { it.background } ?: merchant.background ?: defaults.background
        // A merchant primary that does not stand out on the background falls back to the default.
        val primary = pick { it.primary }
            ?: merchant.primary?.takeIf { contrast(it, background) >= 3.0 }
            ?: defaults.primary
        val primaryChanged = primary != defaults.primary
        val onPrimary = pick { it.onPrimary } ?: merchant.onPrimary
            ?: if (primaryChanged) bestOn(primary) else defaults.onPrimary
        val backgroundSet = pick { it.background } != null || merchant.background != null
        val inputBackground = pick { it.inputBackground } ?: merchant.inputBackground
            ?: if (backgroundSet) background else defaults.inputBackground
        val colors = defaults.copy(
            primary = primary,
            primaryPressed = if (primaryChanged) mix(primary, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.15) else defaults.primaryPressed,
            onPrimary = onPrimary,
            background = background,
            surfaceRaised = if (backgroundSet) background else defaults.surfaceRaised,
            surfaceMuted = pick { it.surfaceMuted } ?: merchant.surfaceMuted ?: defaults.surfaceMuted,
            selectedBackground = if (primaryChanged) mix(background, primary, 0.10) else defaults.selectedBackground,
            text = pick { it.text } ?: defaults.text,
            textSecondary = pick { it.textSecondary } ?: merchant.textSecondary ?: defaults.textSecondary,
            textPlaceholder = pick { it.textPlaceholder } ?: defaults.textPlaceholder,
            link = if (primaryChanged) primary else defaults.link,
            inputBackground = inputBackground,
            inputBorder = pick { it.inputBorder } ?: merchant.inputBorder ?: defaults.inputBorder,
            focusRing = if (primaryChanged) primary else defaults.focusRing,
            danger = pick { it.danger } ?: merchant.danger ?: defaults.danger,
            success = pick { it.success } ?: merchant.success ?: defaults.success,
        )
        val spacing = pick { it.spacingUnit } ?: merchant.spacingUnit ?: 4f
        return ResolvedAppearance(
            dark = dark,
            colors = colors,
            radius = min(pick { it.radius } ?: merchant.radius ?: DEFAULT_RADIUS, MAX_RADIUS),
            fontFamily = pick { it.fontFamily } ?: merchant.fontFamily,
            fontSizeBase = pick { it.fontSizeBase } ?: 16f,
            fontWeightNormal = pick { it.fontWeightNormal } ?: merchant.fontWeightNormal ?: 400,
            fontWeightMedium = pick { it.fontWeightMedium } ?: 500,
            fontWeightBold = pick { it.fontWeightBold } ?: merchant.fontWeightBold ?: 600,
            spacingUnit = spacing,
            gapRow = pick { it.gapRow } ?: spacing * 4,
            gapColumn = pick { it.gapColumn } ?: spacing * 3,
            elevation = pick { it.elevation } ?: merchant.elevation ?: 1f,
            floatingLabels = appearance?.labels?.lowercase() == "floating",
            disableAnimations = appearance?.disableAnimations == true,
            showWajubBadge = branding?.whiteLabel != true,
            separator = branding?.separatorColor?.let { AppearanceValues.color(it) },
            scrimAlpha = branding?.overlayIntensity?.takeIf { it in 0..100 }?.let { it / 100f },
        )
    }

    // One source of tokens; null = not set by it.
    private data class Layer(
        val primary: Int? = null,
        val onPrimary: Int? = null,
        val background: Int? = null,
        val surfaceMuted: Int? = null,
        val text: Int? = null,
        val textSecondary: Int? = null,
        val textPlaceholder: Int? = null,
        val danger: Int? = null,
        val success: Int? = null,
        val inputBackground: Int? = null,
        val inputBorder: Int? = null,
        val fontFamily: String? = null,
        val fontSizeBase: Float? = null,
        val fontWeightNormal: Int? = null,
        val fontWeightMedium: Int? = null,
        val fontWeightBold: Int? = null,
        val spacingUnit: Float? = null,
        val radius: Float? = null,
        val gapRow: Float? = null,
        val gapColumn: Float? = null,
        val elevation: Float? = null,
    )

    private fun variablesLayer(v: AppearanceVariables) = Layer(
        primary = AppearanceValues.color(v.colorPrimary),
        background = AppearanceValues.color(v.colorBackground),
        text = AppearanceValues.color(v.colorText),
        textSecondary = AppearanceValues.color(v.colorTextSecondary),
        textPlaceholder = AppearanceValues.color(v.colorTextPlaceholder),
        danger = AppearanceValues.color(v.colorDanger),
        success = AppearanceValues.color(v.colorSuccess),
        fontFamily = AppearanceValues.fontFamily(v.fontFamily),
        fontSizeBase = AppearanceValues.length(v.fontSizeBase),
        fontWeightNormal = AppearanceValues.weight(v.fontWeightNormal),
        fontWeightMedium = AppearanceValues.weight(v.fontWeightMedium),
        fontWeightBold = AppearanceValues.weight(v.fontWeightBold),
        spacingUnit = AppearanceValues.length(v.spacingUnit),
        radius = AppearanceValues.length(v.borderRadius),
        gapRow = AppearanceValues.length(v.spacingGridRow),
        gapColumn = AppearanceValues.length(v.spacingGridColumn),
    )

    private fun keysLayer(a: Appearance) = Layer(
        primary = AppearanceValues.color(a.primaryColor),
        onPrimary = AppearanceValues.color(a.buttonTextColor),
        background = AppearanceValues.color(a.backgroundColor),
        surfaceMuted = AppearanceValues.color(a.secondaryColor),
        textSecondary = AppearanceValues.color(a.textMutedColor),
        danger = AppearanceValues.color(a.errorColor),
        success = AppearanceValues.color(a.successColor),
        inputBackground = AppearanceValues.color(a.inputBackgroundColor),
        inputBorder = AppearanceValues.color(a.inputBorderColor),
        fontFamily = AppearanceValues.fontFamily(a.fontFamily),
        radius = AppearanceValues.length(a.borderRadius),
        elevation = AppearanceValues.shadow(a.shadow),
    )

    // Kept only when usable: primary ≥ 3:1 against the background, texts ≥ 4.5:1.
    private fun inheritedLayer(t: InheritedTheme, defaults: ThemeTokens): Layer {
        val background = t.background ?: defaults.background
        return Layer(
            primary = t.primary?.takeIf { contrast(it, background) >= 3.0 },
            background = t.background,
            text = t.text?.takeIf { contrast(it, background) >= 4.5 },
            textSecondary = t.textSecondary?.takeIf { contrast(it, background) >= 4.5 },
            inputBorder = t.inputBorder,
            fontFamily = t.fontFamily,
            radius = t.radius?.let { min(it, MAX_RADIUS) },
        )
    }

    private fun brandingLayer(b: Branding, dark: Boolean): Layer {
        // Surface colours are designed for one scheme: light, unless the
        // merchant's own mode is dark.
        val forThisScheme = if (b.appearanceMode == "dark") dark else !dark
        fun scheme(value: String?) = if (forThisScheme) AppearanceValues.color(value) else null
        return Layer(
            primary = AppearanceValues.color(b.primaryColor),
            onPrimary = AppearanceValues.color(b.buttonTextColor),
            background = scheme(b.backgroundColor),
            surfaceMuted = scheme(b.secondaryColor),
            textSecondary = scheme(b.textMutedColor),
            inputBackground = scheme(b.inputBackgroundColor),
            inputBorder = scheme(b.inputBorderColor),
            danger = AppearanceValues.color(b.errorColor),
            success = AppearanceValues.color(b.successColor),
            fontFamily = AppearanceValues.fontFamily(b.font),
            fontWeightNormal = AppearanceValues.weight(b.fontWeightBody),
            fontWeightBold = AppearanceValues.weight(b.fontWeightHeading),
            radius = AppearanceValues.radiusToken(b.borderRadius),
            elevation = AppearanceValues.shadow(b.shadow),
            spacingUnit = if (b.density == "compact") 3f else null,
        )
    }

    /** WCAG contrast ratio of two ARGB colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    // #FFFFFF or #0A0A0A, whichever stands out more on [color].
    private fun bestOn(color: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF0A0A0A.toInt()
        return if (contrast(white, color) >= contrast(black, color)) white else black
    }

    private fun mix(base: Int, over: Int, amount: Double): Int {
        fun channel(shift: Int): Int {
            val a = (base shr shift) and 0xFF
            val b = (over shr shift) and 0xFF
            return (a + (b - a) * amount).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** Parsers for appearance values: anything else is ignored. */
object AppearanceValues {
    private val rgbFunction = Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*([0-9]*\.?[0-9]+)\s*)?\)$""")
    private val number = Regex("""^-?[0-9]*\.?[0-9]+$""")

    /** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(r, g, b)`, `rgba(r, g, b, a)` as ARGB. */
    fun color(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    argb(255, r, g, b)
                }
                6 -> (0xFF shl 24) or hex.toInt(16)
                8 -> {
                    val rgb = hex.substring(0, 6).toInt(16)
                    val a = hex.substring(6).toInt(16)
                    (a shl 24) or rgb
                }
                else -> null
            }
        }
        val match = rgbFunction.matchEntire(v) ?: return null
        val (r, g, b) = match.destructured.toList().take(3).map { it.toInt() }
        if (listOf(r, g, b).any { it > 255 }) return null
        val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble()?.takeIf { it in 0.0..1.0 } ?: 1.0
        return argb((alpha * 255).roundToInt(), r, g, b)
    }

    /** `8px`, `8` (pixels → dp), `0.5rem` (× 16); negative or non-finite values are ignored. */
    fun length(value: String?): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        val (text, factor) = when {
            v.endsWith("px") -> v.removeSuffix("px") to 1f
            v.endsWith("rem") -> v.removeSuffix("rem") to 16f
            else -> v to 1f
        }
        if (!number.matches(text.trim())) return null
        val n = text.trim().toFloatOrNull()?.times(factor) ?: return null
        return n.takeIf { it.isFinite() && it >= 0f }
    }

    /** `400`…`700`, `normal` (400), `bold` (700), or a number. */
    fun weight(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        return when (v) {
            "normal" -> 400
            "bold" -> 700
            else -> v.toDoubleOrNull()?.takeIf { it in 1.0..1000.0 }?.toInt()
        }
    }

    /** A CSS font stack's first family, unquoted. */
    fun fontFamily(value: String?): String? =
        value?.split(',')?.map { it.trim().trim('"', '\'') }?.firstOrNull { it.isNotEmpty() }

    /** `none` 0, `low` 1 dp, `high` 8 dp. */
    fun shadow(value: String?): Float? =
        when (value?.trim()?.lowercase()) {
            "none" -> 0f
            "low" -> 1f
            "high" -> 8f
            else -> null
        }

    /** The branding radius: a length, or a token `none` 0, `sm` 4, `md` 8, `lg` 12, `xl` 16. */
    fun radiusToken(value: String?): Float? =
        when (value?.trim()?.lowercase()) {
            "none" -> 0f
            "sm" -> 4f
            "md" -> 8f
            "lg" -> 12f
            "xl" -> 16f
            else -> length(value)
        }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}
