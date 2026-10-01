package com.wajub.mobile.ui

import android.content.Context
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.PaymentResult

/** Opens redirect / 3DS / confirm / client-session hosted URLs in Custom Tabs — never WebView. */
object PaymentActionHandler {
    fun handle(context: Context, result: PaymentResult): PaymentResult {
        if (result !is PaymentResult.RequiresAction) {
            return result
        }
        when (result.action) {
            ActionKind.Redirect,
            ActionKind.Confirm,
            ActionKind.Confirm3ds,
            ActionKind.ClientSession,
            -> result.actionUrl?.let { HostedRedirectLauncher.open(context, it) }
            else -> Unit
        }
        return result
    }
}
