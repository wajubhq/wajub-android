package com.wajub.mobile.model

data class SessionTransaction(
    val id: String,
    val reference: String,
    val trxref: String? = null,
    val amount: Double,
    val amountTotal: Double? = null,
    val currency: String,
    val status: String,
    val statusLabel: String? = null,
    val sandbox: Boolean = false,
    val description: String? = null,
    val processingContext: ProcessingContext? = null,
)

data class ProcessingContext(
    val channelSlug: String? = null,
    val isMobileMoney: Boolean = false,
    val payerInstruction: String? = null,
    val actionUrl: String? = null,
)

data class SessionChannel(
    val id: String,
    val slug: String,
    val name: String,
    val nameFr: String? = null,
    val type: String,
    val logo: String? = null,
    val countries: List<String> = emptyList(),
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val currency: String,
)

data class EchoConfig(
    val key: String,
    val wsHost: String,
    val wsPort: Int,
    val channelPrefix: String,
    val broadcaster: String,
)

data class SessionData(
    val transaction: SessionTransaction,
    val channels: List<SessionChannel>,
    val locale: String = "en",
    val branding: Map<String, String?> = emptyMap(),
    val echo: EchoConfig? = null,
    val callback: String? = null,
)

data class SdkChannelConfig(
    val available: Boolean,
    val provider: String? = null,
    val sdk: String? = null,
    val publishableKey: String? = null,
    val requiredFields: List<String> = emptyList(),
)

data class SdkConfig(
    val channels: Map<String, SdkChannelConfig>,
)

data class MobileMoneyInput(
    val channelSlug: String,
    val phone: String,
    val country: String,
)

sealed class PaymentResult {
    data class Complete(val transaction: SessionTransaction) : PaymentResult()

    data class Processing(
        val transaction: SessionTransaction,
        val instruction: String? = null,
    ) : PaymentResult()

    data class RequiresAction(
        val action: ActionKind,
        val actionUrl: String? = null,
        val transaction: SessionTransaction,
    ) : PaymentResult()

    data class Failed(
        val error: WajubError,
        val transaction: SessionTransaction? = null,
    ) : PaymentResult()
}

enum class ActionKind {
    Redirect,
    Confirm,
    Confirm3ds,
    PushApproval,
}

data class CancelResult(
    val redirectUrl: String? = null,
)
