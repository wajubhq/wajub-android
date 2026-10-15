package com.wajub.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.SvgDecoder
import com.wajub.mobile.checkout.AppearanceValues
import com.wajub.mobile.mobilemoney.BrandBadge

/** The image loader of logos (SVG included); tests swap it. */
internal val LocalWajubImageLoader = compositionLocalOf<ImageLoader?> { null }

@Composable
internal fun imageLoader(): ImageLoader {
    LocalWajubImageLoader.current?.let { return it }
    return SheetImages.loader(LocalContext.current.applicationContext)
}

// One image loader (and its memory cache) for every sheet.
private object SheetImages {
    @Volatile
    private var shared: ImageLoader? = null

    fun loader(context: android.content.Context): ImageLoader =
        shared ?: synchronized(this) {
            shared ?: ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build().also { shared = it }
        }
}

/** Whether the skeleton pulses — read by the tests (AP-07). */
internal val Pulsing = SemanticsPropertyKey<Boolean>("WajubPulsing")
internal var SemanticsPropertyReceiver.pulsing by Pulsing

/** The full-width primary button: Pay, Verify, Try again. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false, tag: String = "wajub-primary") {
    val look = LocalSheetLook.current
    val c = look.colors
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        shape = RoundedCornerShape(look.radius.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary.color(),
            contentColor = c.onPrimary.color(),
            disabledContainerColor = if (busy) c.primary.color().copy(alpha = 0.7f) else c.disabledBackground.color(),
            disabledContentColor = if (busy) c.onPrimary.color() else c.textDisabled.color(),
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.onPrimary.color(), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight(look.fontWeightBold))
    }
}

/** A quiet text button: Cancel payment, Change, Resend. */
@Composable
internal fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true, tag: String? = null, danger: Boolean = false) {
    val c = LocalSheetLook.current.colors
    TextButton(onClick = onClick, enabled = enabled, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Text(text, color = if (!enabled) c.textDisabled.color() else if (danger) c.danger.color() else c.link.color())
    }
}

/** A text field in the sheet's tokens; the label above it, or floating with `labels: floating`. */
@Composable
internal fun SheetTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    tag: String,
    enabled: Boolean = true,
) {
    val look = LocalSheetLook.current
    val c = look.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!look.floatingLabels) FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            enabled = enabled,
            singleLine = true,
            label = if (look.floatingLabels) ({ Text(label) }) else null,
            placeholder = placeholder?.let { { Text(it, color = c.textPlaceholder.color()) } },
            leadingIcon = prefix,
            trailingIcon = trailing,
            isError = error != null,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(look.radius.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text.color(), fontSize = maxOf(16f, look.fontSizeBase).sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.inputBackground.color(),
                unfocusedContainerColor = c.inputBackground.color(),
                errorContainerColor = c.inputBackground.color(),
                disabledContainerColor = c.disabledBackground.color(),
                focusedBorderColor = c.focusRing.color(),
                unfocusedBorderColor = c.inputBorder.color(),
                errorBorderColor = c.dangerBorder.color(),
                cursorColor = c.primary.color(),
            ),
        )
        if (error != null) FieldError(error, tag = "$tag-error")
    }
}

@Composable
internal fun FieldLabel(text: String) {
    val look = LocalSheetLook.current
    Text(text, style = MaterialTheme.typography.labelMedium, color = look.colors.textSecondary.color())
}

@Composable
internal fun FieldError(text: String, tag: String) {
    Text(text, color = LocalSheetLook.current.colors.danger.color(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
}

/** An error tied to no field, above the form. */
@Composable
internal fun Banner(text: String) {
    val look = LocalSheetLook.current
    val c = look.colors
    Text(
        text,
        color = c.danger.color(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(look.radius.dp))
            .background(c.dangerBackground.color())
            .border(1.dp, c.dangerBorder.color(), RoundedCornerShape(look.radius.dp))
            .padding(12.dp)
            .testTag("wajub-banner"),
    )
}

/** A selectable row: a payment method, an operator. */
@Composable
internal fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val look = LocalSheetLook.current
    val c = look.colors
    val shape = RoundedCornerShape(look.radius.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.selectedBackground.color() else c.inputBackground.color())
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary.color() else c.inputBorder.color()), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .heightIn(min = 40.dp)
            .testTag(tag),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight(look.fontWeightMedium), color = c.text.color())
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary.color())
        }
        trailing?.invoke()
    }
}

/** A radio dot in the primary colour. */
@Composable
internal fun RadioDot(selected: Boolean) {
    val c = LocalSheetLook.current.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) c.primary.color() else c.background.color())
            .border(1.dp, if (selected) c.primary.color() else c.borderStrong.color(), CircleShape),
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.onPrimary.color()))
    }
}

/**
 * An operator's or a method's logo: the `https` image when it loads, else
 * the brand badge of the Mobile Money tables (spec MM-07).
 */
@Composable
internal fun Logo(url: String?, badge: BrandBadge, size: Dp = 36.dp, tag: String, fallback: String? = null) {
    // The logo (an SVG most of the time), else its PNG when it does not load, else the badge.
    var model by remember(url, fallback) { mutableStateOf(url ?: fallback) }
    var loaded by remember(url, fallback) { mutableStateOf(false) }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).testTag(tag)) {
        if (!loaded) BrandBadgeView(badge, size, tag = "$tag-badge")
        model?.let { current ->
            AsyncImage(
                model = current,
                imageLoader = imageLoader(),
                contentDescription = badge.label,
                onState = { state ->
                    loaded = state is AsyncImagePainter.State.Success
                    if (state is AsyncImagePainter.State.Error && current == url && fallback != null && fallback != url) {
                        model = fallback
                    }
                },
                modifier = Modifier.size(size).alpha(if (loaded) 1f else 0f).testTag("$tag-image"),
            )
        }
    }
}

@Composable
internal fun BrandBadgeView(badge: BrandBadge, size: Dp, tag: String) {
    val background = AppearanceValues.color(badge.background) ?: 0xFF525252.toInt()
    val color = AppearanceValues.color(badge.color) ?: 0xFFFFFFFF.toInt()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background.color())
            .semantics { contentDescription = badge.label }
            .testTag(tag),
    ) {
        Text(
            badge.mark,
            color = color.color(),
            fontSize = (size.value / 4.2f).sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Secured by Wajub", unless the merchant is white-labelled. */
@Composable
internal fun SecuredBy(strings: SheetStrings) {
    val look = LocalSheetLook.current
    if (!look.showWajubBadge) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("wajub-secured-by"),
    ) {
        androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = null, tint = look.colors.textPlaceholder.color(), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(strings.securedBy, style = MaterialTheme.typography.labelSmall, color = look.colors.textPlaceholder.color())
    }
}

/** The loader: pulsing placeholder blocks, or a spinner. */
@Composable
internal fun Skeleton() {
    val look = LocalSheetLook.current
    val c = look.colors
    val animate = LocalAnimations.current
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    val shape = RoundedCornerShape(look.radius.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().alpha(alpha).semantics { pulsing = animate }.testTag("wajub-skeleton"),
    ) {
        Box(Modifier.width(140.dp).height(28.dp).clip(shape).background(c.surfaceMuted.color()))
        Box(Modifier.widthIn(max = 220.dp).fillMaxWidth().height(16.dp).clip(shape).background(c.surfaceMuted.color()))
        repeat(2) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(shape).background(c.inputBackground.color())
                    .border(1.dp, c.inputBorder.color(), shape),
            )
        }
        Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(c.surfaceMuted.color()))
    }
}

@Composable
internal fun Spinner(size: Dp = 40.dp) {
    CircularProgressIndicator(
        color = LocalSheetLook.current.colors.primary.color(),
        modifier = Modifier.size(size).testTag("wajub-spinner"),
    )
}
