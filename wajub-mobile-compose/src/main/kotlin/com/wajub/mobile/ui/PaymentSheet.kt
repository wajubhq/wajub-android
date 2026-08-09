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
import com.stripe.android.view.CardInputWidget
import com.wajub.mobile.WajubSession
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.WajubError
import kotlinx.coroutines.launch

private enum class PaymentTab { MobileMoney, Card }

/**
 * Native Compose payment sheet — Mobile Money + Stripe card, no WebView.
 */
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
            tab = if (momo.isEmpty() && loaded.channels.any { it.type.equals("card", true) }) 1 else 0
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

            if (momoChannels.isNotEmpty() && hasCard) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Mobile Money") }
                    SegmentedButton(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Card") }
                }
            }

            when {
                loading -> Text("Loading…")
                error != null -> Text(error!!.message, color = MaterialTheme.colorScheme.error)
                tab == 0 -> {
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
