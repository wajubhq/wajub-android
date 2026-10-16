package com.wajub.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stripe.android.view.CardInputWidget
import com.wajub.mobile.checkout.CardFlavor
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.MobileMoneyForm
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.checkout.formatAmount
import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.WajubLanguage

@Composable
internal fun FormScreen(controller: CheckoutController, screen: SheetScreen.Form, session: SessionData?, strings: SheetStrings, language: WajubLanguage) {
    val look = LocalSheetLook.current
    val txn = session?.transaction
    // What the payer pays on the operator or method chosen.
    val payLabel = txn?.let { strings.payAmount.fill("amount" to formatAmount(controller.amountDue(screen), it.currency, language)) } ?: strings.pay
    Column(verticalArrangement = Arrangement.spacedBy(look.gapRow.dp), modifier = Modifier.testTag("wajub-form-${screen.method.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (screen.method.family == "card") strings.cardTitle else screen.method.name,
                style = MaterialTheme.typography.titleMedium,
                color = look.colors.text.color(),
                modifier = Modifier.weight(1f),
            )
            if (screen.canChangeMethod) LinkButton(strings.change, controller::changeMethod, enabled = !screen.busy, tag = "wajub-change-method")
        }
        screen.banner?.let { Banner(it) }
        when {
            screen.mobileMoney != null -> MobileMoneyFields(controller, screen.mobileMoney!!, screen.busy, strings, language, txn?.currency.orEmpty())
            screen.card != null -> CardFields(controller, screen, strings)
            screen.method.family == "wallet" -> Hint(strings.walletNotice.fill("name" to screen.method.name), tag = "wajub-wallet-notice")
        }
        when {
            screen.card?.flavor is CardFlavor.Stripe -> StripePay(controller, screen, payLabel)
            screen.method.family == "wallet" -> PrimaryButton(
                strings.payWith.fill("name" to screen.method.name), controller::payWallet, busy = screen.busy, tag = "wajub-pay",
            )
            screen.mobileMoney != null -> PrimaryButton(
                // No operator offered any more (exhausted, out of its limits): nothing to pay with.
                payLabel, controller::payMobileMoney, enabled = screen.mobileMoney!!.selected != null, busy = screen.busy, tag = "wajub-pay",
            )
            else -> PrimaryButton(payLabel, controller::payCard, busy = screen.busy, tag = "wajub-pay")
        }
        LinkButton(strings.cancelPayment, controller::cancelPayment, enabled = !screen.busy, tag = "wajub-cancel-payment")
    }
}

@Composable
private fun MobileMoneyFields(
    controller: CheckoutController,
    form: MobileMoneyForm,
    busy: Boolean,
    strings: SheetStrings,
    language: WajubLanguage,
    currency: String,
) {
    val look = LocalSheetLook.current
    val c = look.colors
    val data = MobileMoney.data()
    if (!form.countryLocked && form.countries.size > 1) {
        var open by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(strings.country)
            // A plain menu on a read-only field (stable APIs only: Material 3's exposed
            // dropdown is experimental and changes between releases).
            Box {
                val current = data.country(form.country)
                OutlinedTextField(
                    value = current?.let { "${it.name} (${it.dialCode})" } ?: form.country.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(look.radius.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.inputBackground.color(),
                        unfocusedContainerColor = c.inputBackground.color(),
                        unfocusedBorderColor = c.inputBorder.color(),
                        focusedBorderColor = c.focusRing.color(),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Covers the field: a tap opens the menu.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(enabled = !busy, role = Role.DropdownList) { open = true }
                        .testTag("wajub-country"),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (code in form.countries) {
                        val country = data.country(code)
                        DropdownMenuItem(
                            text = { Text(country?.let { "${it.name} (${it.dialCode})" } ?: code) },
                            onClick = {
                                open = false
                                controller.setCountry(code)
                            },
                            modifier = Modifier.testTag("wajub-country-$code"),
                        )
                    }
                }
            }
        }
    }
    if (form.choices.isEmpty()) {
        Banner(com.wajub.mobile.model.ErrorCatalog.message("operator_unavailable", language, null)!!)
        return
    }
    if (form.listVisible) {
        Column(verticalArrangement = Arrangement.spacedBy((look.spacingUnit * 2).dp)) {
            FieldLabel(strings.operator)
            val payment = controller.transaction?.payableAmount ?: 0.0
            for (choice in form.choices) {
                val amount = choice.amountTotal ?: payment
                // Too many codes refused there: not offered again, with why.
                val unavailable = form.unavailable[choice.id]
                val disabled = unavailable != null || controller.outOfLimits(choice)
                ChoiceRow(
                    selected = form.selected == choice.id,
                    enabled = !busy && !disabled,
                    onClick = { controller.pickOperator(choice.id) },
                    tag = "wajub-operator-${choice.id}",
                    leading = { Logo(choice.logo, choice.badge, size = 32.dp, tag = "wajub-operator-logo-${choice.id}", fallback = choice.logoPng) },
                    title = choice.label,
                    subtitle = unavailable ?: if (disabled) limitText(choice.minAmount, choice.maxAmount, amount, currency, strings, language) else null,
                    trailing = { RadioDot(form.selected == choice.id) },
                )
            }
            form.operatorError?.let { FieldError(it, tag = "wajub-operator-error") }
        }
    }
    val country = form.countryInfo
    SheetTextField(
        label = strings.phone,
        value = form.phone,
        onValueChange = controller::setPhone,
        error = form.phoneError,
        placeholder = country?.placeholderFormatted,
        keyboardType = KeyboardType.Phone,
        prefix = country?.let { { Text(it.dialCode, fontWeight = FontWeight(look.fontWeightMedium), color = c.text.color()) } },
        trailing = if (!form.listVisible) {
            form.badge?.let { choice ->
                {
                    IconButton(onClick = controller::openOperatorList, modifier = Modifier.testTag("wajub-operator-badge")) {
                        Logo(choice.logo, choice.badge, size = 28.dp, tag = "wajub-badge-logo", fallback = choice.logoPng)
                    }
                }
            }
        } else {
            null
        },
        visualTransformation = PairsTransformation,
        tag = "wajub-phone",
        enabled = !busy,
    )
    if (!form.listVisible) LinkButton(strings.otherOperator, controller::openOperatorList, tag = "wajub-open-operators")
    // An operator that takes the payer's own code says so before Pay.
    val announced = form.selectedChoice?.payerOtp
    val hint = when {
        announced == null -> strings.momoInstructions
        announced.ussdCode != null -> strings.payerOtpFormHint.fill("ussd_code" to announced.ussdCode!!)
        else -> strings.payerOtpFormHintGeneric
    }
    Hint(hint, tag = "wajub-momo-hint")
}

/** Groups the digits by two as the payer types (`67 12 34 56 7`). */
internal object PairsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val formatted = MobileMoney.formatAsYouType(raw)
        if (raw.any { it !in '0'..'9' } || formatted.replace(" ", "") != raw) return TransformedText(text, OffsetMapping.Identity)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + (offset - 1).coerceAtLeast(0) / 2
            override fun transformedToOriginal(offset: Int): Int = offset - offset / 3
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

@Composable
private fun CardFields(controller: CheckoutController, screen: SheetScreen.Form, strings: SheetStrings) {
    val card = screen.card ?: return
    when (card.flavor) {
        is CardFlavor.ClientSession -> Hint(strings.clientSessionNotice, tag = "wajub-card-notice")
        is CardFlavor.HostedRedirect -> Hint(strings.hostedNotice, tag = "wajub-card-notice")
        CardFlavor.SandboxForm -> Hint(strings.sandboxCards, tag = "wajub-card-notice")
        else -> Unit
    }
    for (field in card.fields) {
        SheetTextField(
            label = strings.label(field),
            value = card.values[field].orEmpty(),
            onValueChange = { controller.setCardField(field, it) },
            error = card.errors[field],
            keyboardType = when (field) {
                "email" -> KeyboardType.Email
                "phone" -> KeyboardType.Phone
                "card.number", "expiry_month", "expiry_year", "cvv" -> KeyboardType.Number
                else -> KeyboardType.Text
            },
            visualTransformation = if (field == "cvv") PasswordVisualTransformation() else VisualTransformation.None,
            tag = "wajub-field-$field",
            enabled = !screen.busy,
        )
    }
}

/**
 * Stripe's own card widget: the number, expiry and CVC never enter this
 * SDK's state; it hands back a `pm_…` for `/pay/process`.
 */
@Composable
private fun StripePay(controller: CheckoutController, screen: SheetScreen.Form, payLabel: String) {
    val flavor = screen.card?.flavor as? CardFlavor.Stripe ?: return
    val context = LocalContext.current
    var widget by remember { mutableStateOf<CardInputWidget?>(null) }
    var complete by remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        factory = { ctx ->
            CardInputWidget(ctx).apply {
                setCardValidCallback { isValid, _ -> complete = isValid }
                widget = this
            }
        },
    )
    PrimaryButton(
        payLabel,
        onClick = {
            val card = widget?.paymentMethodCard ?: return@PrimaryButton
            val holder = screen.card?.values?.get("card_holder")?.trim()?.ifBlank { null }
            // The attempt starts here: tokenizing is part of it.
            controller.payStripe {
                StripeTokenizer.createPaymentMethod(context, flavor.publishableKey, card, holder)
            }
        },
        enabled = complete,
        busy = screen.busy,
        tag = "wajub-pay",
    )
}

@Composable
internal fun StepScreen(controller: CheckoutController, step: SheetScreen.Step, strings: SheetStrings) {
    val look = LocalSheetLook.current
    val c = look.colors
    val title = when (step.kind) {
        StepKind.Otp -> strings.otpTitle
        StepKind.Pin -> strings.pinTitle
        StepKind.Phone -> strings.phoneTitle
        StepKind.Birthday -> strings.birthdayTitle
        StepKind.Address -> strings.addressTitle
        StepKind.ThreeDs -> strings.threeDsTitle
    }
    Column(verticalArrangement = Arrangement.spacedBy(look.gapRow.dp), modifier = Modifier.testTag("wajub-step-screen-${step.kind.path}")) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text.color())
        val hint = when (step.kind) {
            // The masked contact only; none named: the code prompt alone.
            StepKind.Otp -> step.sentTo?.let { strings.otpSentTo.fill("contact" to it) }
            StepKind.ThreeDs -> step.sentTo?.let { strings.threeDsSentTo.fill("email" to it) }
            // The API's own instruction, in English: secondary text.
            else -> step.hint
        }
        hint?.let { Hint(it, tag = "wajub-step-hint") }
        step.error?.let { Banner(it) }
        step.attemptsRemaining?.let { Hint(strings.attemptsLeft.fill("count" to it), tag = "wajub-attempts") }
        for (field in step.kind.fields) {
            val value = step.values[field].orEmpty()
            if (field == "birthday") {
                BirthdayField(value, step.fieldErrors[field], strings, enabled = !step.busy) { controller.setStepField(field, it) }
            } else {
                SheetTextField(
                    label = strings.label(field),
                    value = value,
                    onValueChange = { controller.setStepField(field, it) },
                    error = step.fieldErrors[field],
                    keyboardType = when (field) {
                        "code", "pin" -> KeyboardType.NumberPassword
                        "phone" -> KeyboardType.Phone
                        else -> KeyboardType.Text
                    },
                    visualTransformation = if (field == "pin") PasswordVisualTransformation() else VisualTransformation.None,
                    tag = "wajub-step-$field",
                    enabled = !step.busy,
                )
            }
        }
        PrimaryButton(if (step.busy) strings.verifying else strings.verify, controller::submitStep, busy = step.busy, tag = "wajub-verify")
        val now = rememberNow(ticking = true)
        // Every step has its resend endpoint (spec §10), offered after 60 s as on the web.
        val wait = ((step.resendAt - now + 999) / 1000).coerceAtLeast(0)
        LinkButton(
            if (wait > 0) strings.resendIn.fill("seconds" to wait) else strings.resend,
            controller::resendStep,
            enabled = wait == 0L && !step.busy,
            tag = "wajub-resend",
        )
        step.expiresAt?.let { expires ->
            val left = ((expires - now) / 1000).coerceAtLeast(0)
            Hint(strings.expiresIn.fill("time" to "%d:%02d".format(left / 60, left % 60)), tag = "wajub-expires")
        }
    }
}

@Composable
private fun BirthdayField(value: String, error: String?, strings: SheetStrings, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    // Typed as digits, shown and sent as YYYY-MM-DD (the controller inserts the
    // dashes), or picked in the platform's date picker: Material 3's is
    // experimental and changes between releases.
    SheetTextField(
        label = strings.label("birthday"),
        value = value,
        onValueChange = onChange,
        error = error,
        placeholder = strings.birthdayFormat,
        keyboardType = KeyboardType.Number,
        trailing = {
            IconButton(
                onClick = { pickDate(context, value, onChange) },
                enabled = enabled,
                modifier = Modifier.testTag("wajub-birthday-picker"),
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = strings.chooseDate)
            }
        },
        tag = "wajub-step-birthday",
        enabled = enabled,
    )
}

// The platform's date picker, opened on the typed date or 30 years ago.
private fun pickDate(context: android.content.Context, value: String, onChange: (String) -> Unit) {
    val parts = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(value)?.groupValues
    val today = java.util.Calendar.getInstance()
    val year = parts?.get(1)?.toInt() ?: (today.get(java.util.Calendar.YEAR) - 30)
    val month = parts?.get(2)?.toInt()?.minus(1) ?: today.get(java.util.Calendar.MONTH)
    val day = parts?.get(3)?.toInt() ?: today.get(java.util.Calendar.DAY_OF_MONTH)
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> onChange("%04d-%02d-%02d".format(java.util.Locale.ROOT, y, m + 1, d)) },
        year,
        month,
        day,
    ).apply { datePicker.maxDate = today.timeInMillis }.show()
}
