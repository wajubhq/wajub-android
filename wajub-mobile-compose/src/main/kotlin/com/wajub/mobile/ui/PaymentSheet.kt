package com.wajub.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stripe.android.view.CardInputWidget
import com.wajub.mobile.WajubSession
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import kotlinx.coroutines.launch

private enum class PaymentTab { MobileMoney, Card }

/**
 * Native Compose payment sheet — Mobile Money + card, no WebView.
 *
 * Card tab, by `sdk-config`: `clientSession` (Paystack / Flutterwave — the PSP's
 * hosted checkout in Custom Tabs handles PIN/OTP/AVS, then the payment is verified
 * server-side when the payer comes back — Adyen cards too, via Pay by Link),
 * `hosted_redirect` (PayPal / Mollie / Paddle / Kkiapay / FedaPay / PayDunya /
 * CinetPay page in Custom Tabs, after asking for whatever contact/billing
 * fields `sdk-config` still requires), or `stripe_elements` (Stripe's own card widget).
 */
private const val TAB_MOBILE_MONEY = 0
private const val TAB_CARD = 1
private const val TAB_WALLET = 2
private val TAB_LABELS = mapOf(TAB_MOBILE_MONEY to "Mobile Money", TAB_CARD to "Card", TAB_WALLET to "Wallet")

private val HOSTED_CARD_FIELD_LABELS = mapOf(
    "first_name" to "First name",
    "last_name" to "Last name",
    "email" to "Email",
    "phone" to "Phone number",
    "address" to "Address",
    "city" to "City",
    "country" to "Country (2-letter code, e.g. CI)",
    "state" to "State / region",
    "zip_code" to "Postal code",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentSheet(
    wajubSession: WajubSession,
    onDismiss: () -> Unit,
    onResult: (PaymentResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<SessionData?>(null) }
    var sdkConfig by remember { mutableStateOf<SdkConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<WajubError?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var selectedChannel by remember { mutableStateOf<SessionChannel?>(null) }
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("CM") }
    var holder by remember { mutableStateOf("") }
    // Raw PAN/expiry/CVC are never held in app-owned Compose state — Stripe's own
    // CardInputWidget owns that data and produces tokenization params directly.
    var cardInputWidget by remember { mutableStateOf<CardInputWidget?>(null) }
    var cardComplete by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var billing by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Client session opened in Custom Tabs, completed once the payer is back.
    var awaitingClientSessionId by remember { mutableStateOf<String?>(null) }
    var awaitingTransaction by remember { mutableStateOf<SessionTransaction?>(null) }
    var leftForPsp by remember { mutableStateOf(false) }

    LaunchedEffect(wajubSession) {
        try {
            val loaded = wajubSession.loadSession()
            val cfg = wajubSession.getSdkConfig()
            val momo = loaded.channels.filter {
                it.type.equals("mobile_money", true) || it.type.equals("mobile", true)
            }
            session = loaded
            sdkConfig = cfg
            selectedChannel = momo.firstOrNull()
            country = momo.firstOrNull()?.countries?.firstOrNull() ?: "CM"
            val hasCardChannel = loaded.channels.any { it.type.equals("card", true) }
            val hasWallet = loaded.channels.any { PaymentMapper.isRedirectWalletChannel(it, cfg.channels[it.slug]) }
            tab = when {
                momo.isNotEmpty() -> TAB_MOBILE_MONEY
                hasCardChannel -> TAB_CARD
                hasWallet -> TAB_WALLET
                else -> TAB_MOBILE_MONEY
            }
            loading = false
        } catch (e: WajubError) {
            loading = false
            error = e
        } catch (e: Exception) {
            loading = false
            error = WajubError.network(e.message ?: "Error")
        }
    }

    fun finish(result: PaymentResult) {
        PaymentActionHandler.handle(context, result)
        onResult(result)
    }

    // An observer added while the activity is already resumed immediately receives
    // ON_RESUME — only complete after an ON_PAUSE/ON_STOP (the Custom Tab opening)
    // has been seen, i.e. when the payer genuinely comes back from the PSP.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
                    if (awaitingClientSessionId != null) leftForPsp = true
                Lifecycle.Event.ON_RESUME -> {
                    val id = awaitingClientSessionId
                    val transaction = awaitingTransaction
                    if (id != null && transaction != null && leftForPsp) {
                        awaitingClientSessionId = null
                        awaitingTransaction = null
                        leftForPsp = false
                        scope.launch {
                            // The payer already went through the PSP's page: end the
                            // sheet rather than offer a new attempt. Only a 402 is a
                            // verified decline; anything else (network, expired
                            // session) may still be settled by the PSP webhook →
                            // Processing, the app keeps watching the status.
                            val result = try {
                                wajubSession.completeClientSession(id)
                            } catch (e: WajubError) {
                                if (e.declineCode != null) {
                                    PaymentResult.Failed(e, transaction)
                                } else {
                                    PaymentResult.Processing(transaction)
                                }
                            } catch (e: Exception) {
                                PaymentResult.Processing(transaction)
                            }
                            submitting = false
                            finish(result)
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Pay with Wajub", style = MaterialTheme.typography.titleLarge)

            val momoChannels = session?.channels?.filter {
                it.type.equals("mobile_money", true) || it.type.equals("mobile", true)
            }.orEmpty()
            val hasCard = session?.channels?.any { it.type.equals("card", true) } == true
            val walletChannels = session?.channels?.filter {
                PaymentMapper.isRedirectWalletChannel(it, sdkConfig?.channels?.get(it.slug))
            }.orEmpty()
            val tabs = buildList {
                if (momoChannels.isNotEmpty()) add(TAB_MOBILE_MONEY)
                if (hasCard) add(TAB_CARD)
                if (walletChannels.isNotEmpty()) add(TAB_WALLET)
            }

            if (tabs.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = tab == value,
                            onClick = { tab = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                        ) { Text(TAB_LABELS.getValue(value)) }
                    }
                }
            }

            when {
                loading -> Text("Loading…")
                error != null -> Text(error!!.message, color = MaterialTheme.colorScheme.error)
                tab == TAB_WALLET -> {
                    Text("You will approve the payment in the wallet app.")
                    walletChannels.forEach { ch ->
                        Button(
                            onClick = {
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        val result = wajubSession.payWallet(ch.slug)
                                        submitting = false
                                        finish(result)
                                    } catch (e: WajubError) {
                                        submitting = false
                                        error = e
                                    } catch (e: Exception) {
                                        submitting = false
                                        error = WajubError.network(e.message ?: "Payment error")
                                    }
                                }
                            },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (submitting) "Processing…" else "Pay with ${ch.name}")
                        }
                    }
                }
                tab == TAB_MOBILE_MONEY -> {
                    if (momoChannels.isEmpty()) {
                        Text("No Mobile Money channels available.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(momoChannels) { ch ->
                                FilterChip(
                                    selected = selectedChannel?.slug == ch.slug,
                                    onClick = { selectedChannel = ch },
                                    label = { Text(ch.name) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone number") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                        Button(
                            onClick = {
                                val ch = selectedChannel ?: return@Button
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        val result = wajubSession.payMobileMoney(
                                            MobileMoneyInput(ch.slug, phone.trim(), country),
                                        )
                                        submitting = false
                                        finish(result)
                                    } catch (e: WajubError) {
                                        submitting = false
                                        error = e
                                    }
                                }
                            },
                            enabled = !submitting && phone.isNotBlank() && selectedChannel != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (submitting) "Processing…" else "Pay now")
                        }
                    }
                }
                !hasCard -> Text("No card channel available.")
                else -> {
                    val cardSlug = wajubSession.cardChannelSlug() ?: "card"
                    val cardCfg = sdkConfig?.channels?.get(cardSlug)
                    if (cardCfg?.clientSession == true) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        val result = wajubSession.startClientSession(
                                            channel = cardSlug,
                                            email = email.trim().ifBlank { null },
                                        )
                                        val clientSession = (result as? PaymentResult.RequiresAction)?.clientSession
                                        val hostedUrl = clientSession?.hostedUrl
                                        if (clientSession == null || hostedUrl == null) {
                                            submitting = false
                                            finish(result)
                                            return@launch
                                        }
                                        awaitingClientSessionId = clientSession.id
                                        awaitingTransaction = (result as PaymentResult.RequiresAction).transaction
                                        leftForPsp = false
                                        if (!HostedRedirectLauncher.open(context, hostedUrl)) {
                                            awaitingClientSessionId = null
                                            awaitingTransaction = null
                                            submitting = false
                                            error = WajubError(
                                                WajubErrorType.PaymentError,
                                                "Could not open the payment page",
                                                "redirect_failed",
                                            )
                                        }
                                    } catch (e: WajubError) {
                                        submitting = false
                                        error = e
                                    } catch (e: Exception) {
                                        submitting = false
                                        error = WajubError.network(e.message ?: "Card error")
                                    }
                                }
                            },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (submitting) "Processing…" else "Pay by card")
                        }
                    } else if (cardCfg?.sdk == "hosted_redirect") {
                        val redirectFields = cardCfg.requiredFields.filter { it in PaymentMapper.hostedCardFields }
                        val redirectFieldsValid = redirectFields.all { PaymentMapper.hostedCardFieldError(it, billing[it]) == null }
                        redirectFields.forEach { field ->
                            OutlinedTextField(
                                value = billing[field].orEmpty(),
                                onValueChange = { value ->
                                    billing = billing + (field to if (field == "country") value.take(2) else value)
                                },
                                label = { Text(HOSTED_CARD_FIELD_LABELS[field] ?: field) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = when (field) {
                                        "email" -> KeyboardType.Email
                                        "phone" -> KeyboardType.Phone
                                        else -> KeyboardType.Text
                                    },
                                ),
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        val result = wajubSession.payCardHostedRedirect(cardSlug, billing)
                                        submitting = false
                                        finish(result)
                                    } catch (e: WajubError) {
                                        submitting = false
                                        error = e
                                    } catch (e: Exception) {
                                        submitting = false
                                        error = WajubError.network(e.message ?: "Card error")
                                    }
                                }
                            },
                            enabled = !submitting && redirectFieldsValid,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (submitting) "Processing…" else "Pay by card")
                        }
                    } else {
                        OutlinedTextField(
                            value = holder,
                            onValueChange = { holder = it },
                            label = { Text("Cardholder name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        // Stripe's own secure widget: card number, expiry and CVC are entered and
                        // validated inside CardInputWidget's protected fields, and it produces
                        // PaymentMethodCreateParams.Card directly — raw PAN/CVC strings never flow
                        // into this Composable's own state.
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                CardInputWidget(ctx).apply {
                                    setCardValidCallback { isValid, _ -> cardComplete = isValid }
                                    cardInputWidget = this
                                }
                            },
                        )
                        Button(
                            onClick = {
                                if (cardCfg?.sdk != "stripe_elements" || cardCfg.publishableKey.isNullOrBlank()) {
                                    error = WajubError(
                                        com.wajub.mobile.model.WajubErrorType.PaymentError,
                                        "Card payments unavailable",
                                        "provider_unavailable",
                                    )
                                    return@Button
                                }
                                val card = cardInputWidget?.paymentMethodCard
                                if (card == null) {
                                    error = WajubError(
                                        com.wajub.mobile.model.WajubErrorType.PaymentError,
                                        "Enter a valid card",
                                        "invalid_card",
                                    )
                                    return@Button
                                }
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        val pmId = StripeTokenizer.createPaymentMethod(
                                            context = context,
                                            publishableKey = cardCfg.publishableKey!!,
                                            card = card,
                                            cardholderName = holder.trim().ifBlank { null },
                                        )
                                        val result = wajubSession.payCard(cardSlug, pmId, holder.trim().ifBlank { null })
                                        submitting = false
                                        finish(result)
                                    } catch (e: WajubError) {
                                        submitting = false
                                        error = e
                                    } catch (e: Exception) {
                                        submitting = false
                                        error = WajubError.network(e.message ?: "Card error")
                                    }
                                }
                            },
                            enabled = !submitting && cardComplete,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (submitting) "Processing…" else "Pay with card")
                        }
                    }
                }
            }
        }
    }
}
