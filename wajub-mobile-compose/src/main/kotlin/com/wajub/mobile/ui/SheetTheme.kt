package com.wajub.mobile.ui

import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wajub.mobile.checkout.Appearance
import com.wajub.mobile.checkout.AppearanceResolver
import com.wajub.mobile.checkout.InheritedTheme
import com.wajub.mobile.checkout.ResolvedAppearance
import com.wajub.mobile.model.Branding

/** The resolved look of the sheet, for its components. */
internal val LocalSheetLook = staticCompositionLocalOf { AppearanceResolver.resolve(null, null, systemDark = false) }

internal fun Int.color(): Color = Color(this)

/**
 * Resolves the sheet's look for the current composition: the host's
 * [appearance], the merchant's [branding], the OS scheme (live), and — with
 * `inherit` — the app's own Material theme.
 */
@Composable
internal fun rememberSheetLook(appearance: Appearance?, branding: Branding?): ResolvedAppearance {
    val systemDark = isSystemInDarkTheme()
    val inherited = if (appearance?.inherit == true) inheritedTheme() else null
    return remember(appearance, branding, systemDark, inherited) {
        AppearanceResolver.resolve(appearance, branding, systemDark, inherited)
    }
}

/** The app's Material theme, read for `inherit` (appearance.md §3); the core keeps what contrasts enough. */
@Composable
internal fun inheritedTheme(): InheritedTheme {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val small = MaterialTheme.shapes.small
    val radius = (small as? RoundedCornerShape)?.topStart?.toPx(Size(100f, 44f), density)?.let { it / density.density }
    // The app's body font: Material's own (sans serif) says nothing.
    val font = MaterialTheme.typography.bodyLarge.fontFamily
        ?.takeIf { it != FontFamily.Default && it != FontFamily.SansSerif }
        ?.let { family -> inheritedFontName(family).also { WajubFonts.register(it, family) } }
    return InheritedTheme(
        dark = colors.surface.luminance() < 0.5f,
        primary = colors.primary.toArgb(),
        background = colors.surface.toArgb(),
        text = colors.onSurface.toArgb(),
        textSecondary = colors.onSurfaceVariant.toArgb(),
        inputBorder = colors.outline.toArgb(),
        fontFamily = font,
        radius = radius,
    )
}

/** Material 3 dressed in the sheet's tokens, plus [LocalSheetLook] for the rest. */
@Composable
internal fun SheetTheme(look: ResolvedAppearance, content: @Composable () -> Unit) {
    val c = look.colors
    val base = if (look.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.primary.color(),
        onPrimary = c.onPrimary.color(),
        background = c.background.color(),
        onBackground = c.text.color(),
        surface = c.background.color(),
        onSurface = c.text.color(),
        surfaceVariant = c.surfaceMuted.color(),
        onSurfaceVariant = c.textSecondary.color(),
        surfaceContainerLow = c.background.color(),
        surfaceContainer = c.background.color(),
        surfaceContainerHigh = c.surfaceRaised.color(),
        outline = c.inputBorder.color(),
        outlineVariant = c.borderStrong.color(),
        error = c.danger.color(),
    )
    val context = LocalContext.current
    val family = remember(look.fontFamily) { fontFamily(look.fontFamily) }
    val base16 = look.fontSizeBase
    fun style(size: Float, weight: Int) = TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = FontWeight(weight))
    val typography = Typography(
        headlineSmall = style(base16 * 1.5f, look.fontWeightBold),
        titleLarge = style(base16 * 1.25f, look.fontWeightBold),
        titleMedium = style(base16 * 1.0625f, look.fontWeightBold),
        titleSmall = style(base16 * 0.875f, look.fontWeightMedium),
        bodyLarge = style(base16, look.fontWeightNormal),
        bodyMedium = style(base16 * 0.875f, look.fontWeightNormal),
        bodySmall = style(base16 * 0.8125f, look.fontWeightNormal),
        labelLarge = style(base16, look.fontWeightBold),
        labelMedium = style(base16 * 0.875f, look.fontWeightMedium),
        labelSmall = style(base16 * 0.75f, look.fontWeightMedium),
    )
    val shape = RoundedCornerShape(look.radius.dp)
    val shapes = Shapes(extraSmall = shape, small = shape, medium = shape, large = RoundedCornerShape((look.radius * 1.5f).dp))
    val animations = !look.disableAnimations && !reducedMotion(context)
    CompositionLocalProvider(LocalSheetLook provides look, LocalAnimations provides animations) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

/** False when `disableAnimations` is set or the OS asks for reduced motion. */
internal val LocalAnimations = staticCompositionLocalOf { true }

private fun reducedMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

// The inherited family travels through the resolver by this name.
private fun inheritedFontName(family: FontFamily) = "wajub-inherited-font-${family.hashCode()}"

// The family the app registered (WajubFonts) or inherited, else the system's of
// that name, else the platform's.
internal fun fontFamily(name: String?): FontFamily {
    if (name.isNullOrBlank()) return FontFamily.Default
    WajubFonts.find(name)?.let { return it }
    val typeface = Typeface.create(name, Typeface.NORMAL)
    return if (typeface == null || typeface == Typeface.DEFAULT || typeface == Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)) {
        FontFamily.Default
    } else {
        FontFamily(typeface)
    }
}
