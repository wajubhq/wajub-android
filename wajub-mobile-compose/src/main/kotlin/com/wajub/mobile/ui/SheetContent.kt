package com.wajub.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.LoaderStyle
import com.wajub.mobile.checkout.PaymentMethod
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.checkout.formatAmount
import com.wajub.mobile.mobilemoney.BrandBadge
import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.delay

/**
 * The sheet's content: header, the controller's current screen, footer.
 * [PaymentSheet] puts it in a modal bottom sheet; tests draw it directly.
 */
@Composable
internal fun PaymentSheetContent(controller: CheckoutController) {
    val session by controller.sessionData.collectAsState()
    val look = rememberSheetLook(controller.options.appearance, session?.brand)
    SheetTheme(look) {
        val screen by controller.screen.collectAsState()
        val language by controller.language.collectAsState()
        val strings = SheetStrings.of(language)
        val c = look.colors
        Column(
            verticalArrangement = Arrangement.spacedBy(look.gapRow.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(c.background.color())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = (look.spacingUnit * 6).dp, vertical = (look.spacingUnit * 4).dp)
                .testTag("wajub-sheet"),
        ) {
            Header(session, screen, controller.amountDue(screen), strings, language, onClose = controller::dismiss)
            // The loader cross-fades to each screen in 200 ms (none without animations).
            val animations = LocalAnimations.current
            val fade: FiniteAnimationSpec<Float> = if (animations) tween(200) else snap()
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                contentKey = { it::class },
                label = "screen",
            ) { current ->
                Screen(controller, current, session, strings, language)
            }
            SecuredBy(strings)
        }
    }
}

@Composable
private fun Header(session: SessionData?, screen: SheetScreen, amount: Double, strings: SheetStrings, language: WajubLanguage, onClose: () -> Unit) {
    val look = LocalSheetLook.current
    val c = look.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val brand = session?.brand
            val logo = brand?.headerLogo
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    imageLoader = imageLoader(),
                    contentDescription = brand.businessName,
                    modifier = Modifier.heightIn(max = 40.dp).testTag("wajub-merchant-logo"),
                )
            } else {
                Text(
                    brand?.businessName ?: strings.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text.color(),
                    modifier = Modifier.testTag("wajub-merchant-name"),
                )
            }
            val txn = session?.transaction
            if (txn != null && screen !is SheetScreen.LoadError) {
                // What the payer pays on the method chosen.
                Text(
                    formatAmount(amount, txn.currency, language),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.text.color(),
                    modifier = Modifier.testTag("wajub-amount"),
                )
                txn.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary.color()) }
            }
        }
        if (session?.transaction?.sandbox == true) {
            Text(
                strings.testMode,
                style = MaterialTheme.typography.labelSmall,
                color = c.warning.color(),
                modifier = Modifier
                    .background(c.warningBackground.color(), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("wajub-test-mode"),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("wajub-close")) {
            Icon(Icons.Filled.Close, contentDescription = strings.close, tint = c.textSecondary.color())
        }
    }
}

@Composable
private fun Screen(controller: CheckoutController, screen: SheetScreen, session: SessionData?, strings: SheetStrings, language: WajubLanguage) {
    when (screen) {
        SheetScreen.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
            if (controller.options.loader == LoaderStyle.Spinner) Spinner() else Skeleton()
        }
        is SheetScreen.LoadError -> Notice(
            icon = Icons.Filled.Warning,
            tint = LocalSheetLook.current.colors.danger,
            title = strings.loadErrorTitle,
            message = screen.error.message,
            tag = "wajub-load-error",
        ) {
            if (screen.error.retryable) PrimaryButton(strings.tryAgain, controller::retryLoad, tag = "wajub-retry")
            LinkButton(strings.close, controller::close, tag = "wajub-close-button")
        }
        is SheetScreen.Methods -> MethodList(controller, screen.methods, session, strings, language)
        is SheetScreen.Form -> FormScreen(controller, screen, session, strings, language)
        is SheetScreen.Approval -> Waiting(
            title = if (screen.codeReceived) strings.payerOtpApproval else strings.approvalTitle,
            body = strings.approvalBody,
            tag = "wajub-approval",
        ) {
            screen.phone?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = LocalSheetLook.current.colors.text.color()) }
            Instruction(screen.instruction, strings, language)
            Text(strings.doNotClose, style = MaterialTheme.typography.bodySmall, color = LocalSheetLook.current.colors.textPlaceholder.color())
        }
        is SheetScreen.Ussd -> Waiting(strings.ussdTitle, strings.ussdBody, tag = "wajub-ussd") {
            screen.instruction?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, color = LocalSheetLook.current.colors.text.color(), textAlign = TextAlign.Center)
            }
        }
        is SheetScreen.Step -> StepScreen(controller, screen, strings)
        is SheetScreen.PayerOtp -> PayerOtpScreen(controller, screen, strings, language)
        is SheetScreen.Pending -> Waiting(
            title = strings.pendingTitle,
            body = when {
                screen.threeDs -> strings.threeDsInBrowser
                screen.capped -> strings.notified
                else -> strings.pendingBody
            },
            tag = "wajub-pending",
            spinner = !screen.capped,
        ) {
            if (screen.url != null && !screen.capped) LinkButton(strings.continuePayment, controller::continuePayment, tag = "wajub-continue")
            if (screen.capped) PrimaryButton(strings.close, controller::close, tag = "wajub-close-button")
        }
        is SheetScreen.Success -> SuccessScreen(controller, screen, session, strings, language)
        is SheetScreen.Failed -> {
            val now = rememberNow(screen.retryAt != null)
            val wait = screen.retryAt?.let { ((it - now + 999) / 1000).coerceAtLeast(0) } ?: 0
            val rateLimited = screen.error.code == "rate_limited"
            Notice(
                icon = Icons.Filled.Warning,
                tint = LocalSheetLook.current.colors.danger,
                title = if (rateLimited) strings.rateLimitedTitle else strings.failureTitle,
                message = screen.error.message,
                tag = "wajub-failed",
            ) {
                if (screen.error.retryable) {
                    PrimaryButton(
                        if (wait > 0) strings.retryIn.fill("seconds" to wait) else strings.tryAgain,
                        controller::tryAgain,
                        enabled = wait == 0L,
                        tag = "wajub-try-again",
                    )
                }
                LinkButton(strings.close, controller::close, tag = "wajub-close-button")
            }
        }
        is SheetScreen.Expired -> Notice(Icons.Filled.Info, LocalSheetLook.current.colors.warning, strings.expiredTitle, screen.error.message, "wajub-expired") {
            LinkButton(strings.close, controller::close, tag = "wajub-close-button")
        }
        is SheetScreen.Cancelled -> Notice(
            Icons.Filled.Info,
            LocalSheetLook.current.colors.textSecondary,
            strings.cancelledTitle,
            screen.error?.message,
            "wajub-cancelled",
        ) {
            if (screen.error != null) LinkButton(strings.close, controller::close, tag = "wajub-close-button")
        }
        is SheetScreen.Ended -> Notice(Icons.Filled.Info, LocalSheetLook.current.colors.textSecondary, strings.endedTitle, screen.error.message, "wajub-ended") {
            LinkButton(strings.close, controller::close, tag = "wajub-close-button")
        }
    }
}

/** The clock, ticking every second while [ticking]. */
@Composable
internal fun rememberNow(ticking: Boolean): Long {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now.longValue
}

@Composable
private fun MethodList(controller: CheckoutController, methods: List<PaymentMethod>, session: SessionData?, strings: SheetStrings, language: WajubLanguage) {
    val look = LocalSheetLook.current
    val payment = controller.transaction?.payableAmount ?: session?.transaction?.payableAmount ?: 0.0
    val currency = session?.transaction?.currency.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy((look.spacingUnit * 2).dp)) {
        Text(strings.selectMethod, style = MaterialTheme.typography.titleSmall, color = look.colors.textSecondary.color())
        for (method in methods) {
            // The limits take what the payer pays with this method.
            val amount = method.amountTotal ?: payment
            val disabled = method.outOfLimits(amount)
            ChoiceRow(
                selected = false,
                enabled = !disabled,
                onClick = { controller.selectMethod(method.id) },
                tag = "wajub-method-${method.id}",
                leading = { Logo(method.logo, methodBadge(method), tag = "wajub-method-logo-${method.id}", fallback = method.logoPng) },
                title = if (method.family == "card" && method.name.isBlank()) strings.cardTitle else method.name,
                subtitle = if (disabled) limitText(method.minAmount, method.maxAmount, amount, currency, strings, language) else null,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = look.colors.textPlaceholder.color()) },
            )
        }
        LinkButton(strings.cancelPayment, controller::cancelPayment, tag = "wajub-cancel-payment")
    }
}

internal fun methodBadge(method: PaymentMethod): BrandBadge {
    val id = method.slug.substringAfter('.')
    return MobileMoney.data().badge(id, method.name)
}

internal fun limitText(min: Double?, max: Double?, amount: Double, currency: String, strings: SheetStrings, language: WajubLanguage): String? =
    when {
        min != null && amount < min -> strings.availableFrom.fill("amount" to formatAmount(min, currency, language))
        max != null && amount > max -> strings.availableUpTo.fill("amount" to formatAmount(max, currency, language))
        else -> null
    }

/** `payer_instruction` is English: a French sheet shows it under "Instructions". */
@Composable
private fun Instruction(text: String?, strings: SheetStrings, language: WajubLanguage) {
    if (text.isNullOrBlank()) return
    val look = LocalSheetLook.current
    val c = look.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surfaceMuted.color(), RoundedCornerShape(look.radius.dp))
            .padding(12.dp)
            .testTag("wajub-instruction"),
    ) {
        if (language == WajubLanguage.Fr) FieldLabel(strings.instructions)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text.color())
    }
}

@Composable
private fun Waiting(title: String, body: String, tag: String, spinner: Boolean = true, extra: @Composable () -> Unit) {
    val c = LocalSheetLook.current.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(tag),
    ) {
        if (spinner) Spinner()
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text.color(), textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary.color(), textAlign = TextAlign.Center)
        extra()
    }
}

@Composable
internal fun Notice(
    icon: ImageVector,
    tint: Int,
    title: String,
    message: String?,
    tag: String,
    actions: @Composable () -> Unit,
) {
    val c = LocalSheetLook.current.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, tint = tint.color(), modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text.color(), textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary.color(), textAlign = TextAlign.Center, modifier = Modifier.testTag("$tag-message"))
        }
        Spacer(Modifier.height(4.dp))
        actions()
    }
}

@Composable
private fun SuccessScreen(controller: CheckoutController, screen: SheetScreen.Success, session: SessionData?, strings: SheetStrings, language: WajubLanguage) {
    val look = LocalSheetLook.current
    val c = look.colors
    val payment = screen.payment
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("wajub-success"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).background(c.successBackground.color(), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.success.color(), modifier = Modifier.size(40.dp))
        }
        Text(strings.successTitle, style = MaterialTheme.typography.titleLarge, color = c.text.color())
        Text(formatAmount(payment.amount, payment.currency, language), style = MaterialTheme.typography.headlineSmall, color = c.text.color())
        session?.brand?.businessName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary.color()) }
        payment.reference?.let {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.inputBorder.color(), RoundedCornerShape(look.radius.dp))
                    .padding(12.dp),
            ) {
                Text(strings.reference, style = MaterialTheme.typography.bodySmall, color = c.textSecondary.color())
                Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight(look.fontWeightMedium), color = c.text.color())
            }
        }
        payment.receiptUrl?.let { url ->
            LinkButton(strings.viewReceipt, { controller.urlOpener?.invoke(url) }, tag = "wajub-receipt")
        }
        PrimaryButton(strings.done, controller::close, tag = "wajub-done")
    }
}

/** A short line under a title: an icon-less hint in the secondary colour. */
@Composable
internal fun Hint(text: String, tag: String? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalSheetLook.current.colors.textSecondary.color(),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}
