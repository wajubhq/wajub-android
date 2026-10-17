package com.wajub.mobile.checkout

import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mobilemoney.MobileMoneyRules
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.WajubLanguage

/** Where the payer enters the card. */
sealed class CardFlavor {
    /** On the payment provider's own page, after the contact fields it needs. */
    data class ClientSession(val requiredFields: List<String>) : CardFlavor()

    /** In Stripe's card form. */
    data class Stripe(val publishableKey: String) : CardFlavor()

    /** On the payment provider's card page, after the contact and billing fields it needs. */
    data class HostedRedirect(val requiredFields: List<String>) : CardFlavor()

    /** Sandbox only: the published test cards. */
    data object SandboxForm : CardFlavor()

    data object Hidden : CardFlavor()

    companion object {
        /** Where the card of [entry] is entered; [Hidden] when the sheet cannot take it. */
        fun decide(entry: SdkChannelConfig?, sandbox: Boolean): CardFlavor {
            if (entry == null || !entry.available) return Hidden
            val contactFields = entry.requiredFields.filter { it in PaymentMapper.hostedCardFields }
            return when {
                entry.clientSession -> ClientSession(contactFields)
                entry.sdk == "stripe_elements" -> entry.publishableKey?.let { Stripe(it) } ?: Hidden
                entry.sdk == "hosted_redirect" -> HostedRedirect(contactFields)
                entry.sdk == "form" && sandbox -> SandboxForm
                else -> Hidden
            }
        }
    }
}

/** One method of the sheet's list. */
data class PaymentMethod(
    /** `mobile_money`, `card`, or a wallet's slug. */
    val id: String,
    /** `mobile_money`, `card` or `wallet`. */
    val family: String,
    /** In the sheet's language. */
    val name: String,
    val logo: String?,
    /** The channels it pays through (every Mobile Money channel for `mobile_money`). */
    val channels: List<SessionChannel>,
    val cardFlavor: CardFlavor? = null,
    /** Shown disabled when the amount is outside these limits. */
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    /** The logo as a PNG, when there is one. */
    val logoPng: String? = null,
) {
    val slug: String get() = channels.first().slug

    /** What the payer pays with this method when it has its own price (a fee that depends on it); Mobile Money's follows the operator. */
    val amountTotal: Double?
        get() = if (family == "mobile_money") null else channels.singleOrNull()?.amountTotal

    fun outOfLimits(amount: Double): Boolean =
        (minAmount != null && amount < minAmount) || (maxAmount != null && amount > maxAmount)
}

/** Which payment methods the sheet offers. */
internal object PaymentMethods {
    /**
     * The methods, in API order: one Mobile Money method for every Mobile
     * Money channel, the card when its flavour is supported, each one-tap
     * wallet. Other channel types are not shown.
     */
    fun build(
        channels: List<SessionChannel>,
        sdkConfig: SdkConfig?,
        sandbox: Boolean,
        language: WajubLanguage,
    ): List<PaymentMethod> {
        val methods = mutableListOf<PaymentMethod>()
        val momo = channels.filter { MobileMoneyRules.isMobileMoney(it) && mobileMoneyAvailable(it, sdkConfig) }
        for (channel in channels) {
            when {
                MobileMoneyRules.isMobileMoney(channel) -> {
                    if (momo.isNotEmpty() && methods.none { it.family == "mobile_money" }) {
                        methods += PaymentMethod(
                            id = "mobile_money",
                            family = "mobile_money",
                            name = "Mobile Money",
                            logo = momo.first().logo,
                            channels = momo,
                            logoPng = momo.first().logoPng,
                        )
                    }
                }
                channel.type.equals("card", true) -> {
                    val flavor = CardFlavor.decide(entryFor(channel, sdkConfig), sandbox)
                    if (flavor != CardFlavor.Hidden) {
                        methods += PaymentMethod(
                            id = "card",
                            family = "card",
                            name = MobileMoneyRules.channelName(channel, language),
                            logo = channel.logo,
                            channels = listOf(channel),
                            cardFlavor = flavor,
                            minAmount = channel.minAmount,
                            maxAmount = channel.maxAmount,
                            logoPng = channel.logoPng,
                        )
                    }
                }
                PaymentMapper.isRedirectWalletChannel(channel, entryFor(channel, sdkConfig)) ->
                    methods += PaymentMethod(
                        id = channel.slug,
                        family = "wallet",
                        name = MobileMoneyRules.channelName(channel, language),
                        logo = channel.logo,
                        channels = listOf(channel),
                        minAmount = channel.minAmount,
                        maxAmount = channel.maxAmount,
                        logoPng = channel.logoPng,
                    )
            }
        }
        return methods
    }

    /**
     * sdk-config is keyed by the session's slugs: an aggregate (`cm.mobile`) has
     * its own entry, and [operator]'s member entry is its prediction; an answer
     * without the aggregate's entry falls back to its members' (`cm.mtn`,
     * `cm.orange`). A Mobile Money channel without an entry is a `form` asking
     * the phone.
     */
    fun entryFor(channel: SessionChannel, sdkConfig: SdkConfig?, operator: String? = null): SdkChannelConfig? {
        val entries = sdkConfig?.channels ?: emptyMap()
        val own = entries[channel.slug]
        val entry = if (channel.operators.isNotEmpty()) {
            val members = channel.operators
            operator?.let { picked -> members.firstOrNull { it.operator == picked }?.let { entries[it.slug] } }
                ?: own
                ?: members.firstNotNullOfOrNull { entries[it.slug] }
        } else {
            own
        }
        if (entry == null && MobileMoneyRules.isMobileMoney(channel)) {
            return SdkChannelConfig(available = true, sdk = "form", requiredFields = listOf("phone"))
        }
        return entry
    }

    // sdk-config may hide a Mobile Money channel (`available: false`); without
    // sdk-config (its call failed) Mobile Money still works.
    private fun mobileMoneyAvailable(channel: SessionChannel, sdkConfig: SdkConfig?): Boolean =
        sdkConfig == null || entryFor(channel, sdkConfig)?.available != false
}
