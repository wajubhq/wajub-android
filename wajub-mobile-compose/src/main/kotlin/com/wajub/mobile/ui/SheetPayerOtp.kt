package com.wajub.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.PayerOtpPart
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.delay

/**
 * The code the payer generates in the operator's menu: the code to dial (a
 * link to the dialer, and Copy), the exact amount, the steps, the code field.
 */
@Composable
internal fun PayerOtpScreen(controller: CheckoutController, screen: SheetScreen.PayerOtp, strings: SheetStrings, language: WajubLanguage) {
    val look = LocalSheetLook.current
    val c = look.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val instructions = screen.instructions
    val dial: (String) -> Unit = { openDialer(context, it) }
    Column(verticalArrangement = Arrangement.spacedBy(look.gapRow.dp), modifier = Modifier.testTag("wajub-payer-otp")) {
        Text(strings.payerOtpTitle, style = MaterialTheme.typography.titleMedium, color = c.text.color())
        Hint(strings.payerOtpDescription)
        instructions.ussdCode?.let { code ->
            var copied by remember(code) { mutableStateOf(false) }
            // "Copied" for 2 s, then "Copy" again.
            LaunchedEffect(copied) {
                if (copied) {
                    delay(COPIED_FOR_MS)
                    copied = false
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(strings.payerOtpDial)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        code,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight(look.fontWeightBold),
                        color = c.link.color(),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { dial(code) }
                            .testTag("wajub-payer-otp-dial"),
                    )
                    LinkButton(
                        if (copied) strings.copied else strings.copy,
                        {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        },
                        tag = "wajub-payer-otp-copy",
                    )
                }
            }
        }
        // Exactly as the operator's menu takes it: never reformatted. None given: no amount at all.
        val amount = instructions.amount.trim().takeIf { it.isNotEmpty() }?.let { "$it ${instructions.currency}".trim() }
        if (amount != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(strings.payerOtpAmount)
                Text(
                    amount,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight(look.fontWeightBold),
                    color = c.text.color(),
                    modifier = Modifier.testTag("wajub-payer-otp-amount"),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("wajub-payer-otp-steps")) {
            FieldLabel(strings.payerOtpStepsTitle)
            val steps = instructions.stepsIn(language).ifEmpty {
                // No steps from the operator: the generic ones, with the exact amount when there is one.
                strings.payerOtpGenericSteps.map { genericStep(it, amount) }
            }
            steps.forEachIndexed { index, parts -> StepLine(index + 1, parts, dial) }
        }
        screen.sandboxCode?.let { Hint(strings.payerOtpSandbox.fill("code" to it), tag = "wajub-payer-otp-sandbox") }
        screen.error?.let { Banner(it) }
        screen.attemptsRemaining?.let { Hint(strings.tries(it), tag = "wajub-payer-otp-attempts") }
        CodeField(
            label = strings.payerOtpCodeLabel,
            value = screen.code,
            boxes = instructions.otpLength?.takeIf { it in 1..MAX_BOXES },
            onChange = controller::setPayerOtpCode,
            enabled = !screen.busy,
        )
        // Sent on this press only: never when the last digit is typed.
        PrimaryButton(strings.payerOtpSubmit, controller::submitPayerOtp, busy = screen.busy, tag = "wajub-payer-otp-submit")
        LinkButton(strings.payerOtpChangeMethod, controller::leavePayerOtp, enabled = !screen.busy, tag = "wajub-payer-otp-change")
    }
}

private const val MAX_BOXES = 8
private const val COPIED_FOR_MS = 2_000L

// A generic step: its `{amount}` in bold; without an amount, the sentence ends before it.
internal fun genericStep(text: String, amount: String?): List<PayerOtpPart> {
    if (amount == null) return listOf(PayerOtpPart(text.replace(Regex("""\s*:\s*\{amount\}"""), "").replace("{amount}", ""), PayerOtpPart.Kind.Text))
    val at = text.indexOf("{amount}")
    if (at < 0) return listOf(PayerOtpPart(text, PayerOtpPart.Kind.Text))
    return listOf(
        PayerOtpPart(text.substring(0, at), PayerOtpPart.Kind.Text),
        PayerOtpPart(amount, PayerOtpPart.Kind.Amount),
        PayerOtpPart(text.substring(at + "{amount}".length), PayerOtpPart.Kind.Text),
    )
}

@Composable
private fun StepLine(number: Int, parts: List<PayerOtpPart>, dial: (String) -> Unit) {
    val look = LocalSheetLook.current
    val c = look.colors
    val text = buildAnnotatedString {
        append("$number. ")
        for (part in parts) {
            when (part.kind) {
                PayerOtpPart.Kind.Text -> append(part.text)
                PayerOtpPart.Kind.Amount -> withStyle(SpanStyle(fontWeight = FontWeight(look.fontWeightBold))) { append(part.text) }
                PayerOtpPart.Kind.UssdCode -> withLink(LinkAnnotation.Clickable(part.text) { dial(part.text) }) {
                    withStyle(SpanStyle(color = c.link.color(), fontWeight = FontWeight(look.fontWeightBold))) { append(part.text) }
                }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text.color())
}

// Digits only; one box per digit when the code's length is known.
@Composable
private fun CodeField(label: String, value: String, boxes: Int?, onChange: (String) -> Unit, enabled: Boolean) {
    if (boxes == null) {
        SheetTextField(
            label = label,
            value = value,
            onValueChange = onChange,
            keyboardType = KeyboardType.NumberPassword,
            tag = "wajub-payer-otp-code",
            enabled = enabled,
        )
        return
    }
    val look = LocalSheetLook.current
    val c = look.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(color = c.text.color()),
            modifier = Modifier.fillMaxWidth().testTag("wajub-payer-otp-code"),
            decorationBox = { _ ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(boxes) { index ->
                        val focused = index == value.length.coerceAtMost(boxes - 1)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp, 48.dp)
                                .border(
                                    1.dp,
                                    (if (focused && enabled) c.focusRing else c.inputBorder).color(),
                                    RoundedCornerShape(look.radius.dp),
                                ),
                        ) {
                            Text(
                                value.getOrNull(index)?.toString().orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                color = c.text.color(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            },
        )
    }
}

/** `tel:` with `#` written `%23`, so the dialer opens on the whole code. */
internal fun dialUri(code: String): Uri = Uri.parse("tel:" + code.filterNot { it.isWhitespace() }.replace("#", "%23"))

private fun openDialer(context: Context, code: String) {
    val intent = Intent(Intent.ACTION_DIAL, dialUri(code))
    if (!context.isActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private tailrec fun Context.isActivity(): Boolean =
    when (this) {
        is Activity -> true
        is ContextWrapper -> baseContext.isActivity()
        else -> false
    }
