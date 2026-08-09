package com.wajub.mobile.mapper

import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentMapperPhase2Test {
    @Test
    fun mapsRedirectAction() {
        val txn = RawTransaction(
            id = "trx",
            reference = "trx",
            amount = 1000.0,
            currency = "XAF",
            status = "processing",
        )
        val raw = RawProcessResponse(
            code = 202,
            status = "Accepted",
            message = "Redirect",
            action = "redirect",
            transaction = txn,
            confirmUrl = "https://psp.example/confirm",
        )
        val result = PaymentMapper.mapProcessResponse(raw, "card")
        assertTrue(result is PaymentResult.RequiresAction)
        val action = result as PaymentResult.RequiresAction
        assertEquals(com.wajub.mobile.model.ActionKind.Redirect, action.action)
        assertEquals("https://psp.example/confirm", action.actionUrl)
    }

    @Test
    fun mapsTerminalStatusToComplete() {
        val txn = SessionTransaction(
            id = "trx",
            reference = "trx",
            amount = 1000.0,
            currency = "XAF",
            status = "success",
        )
        val result = PaymentMapper.mapStatusToResult("success", txn)
        assertTrue(result is PaymentResult.Complete)
    }
}
