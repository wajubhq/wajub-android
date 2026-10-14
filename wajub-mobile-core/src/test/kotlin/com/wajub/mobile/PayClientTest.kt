package com.wajub.mobile

import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.model.MobileMoneyInput
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayClientTest {
    @Test
    fun mapsMobileMoneyProcessToPushApproval() {
        val fixture = JSONObject(
            """
            {
              "code": 202,
              "status": "Accepted",
              "transaction": {
                "id": "trx.test",
                "reference": "trx.test",
                "amount": 1000,
                "currency": "XAF",
                "status": "processing",
                "processing_context": {
                  "is_mobile_money": true,
                  "payer_instruction": "Confirm on your phone"
                }
              }
            }
            """.trimIndent(),
        )
        val raw = RawProcessResponse.fromJson(fixture)
        val result = PaymentMapper.mapProcessResponse(raw, "mobile_money")
        assertTrue(result is com.wajub.mobile.model.PaymentResult.RequiresAction)
        val action = result as com.wajub.mobile.model.PaymentResult.RequiresAction
        assertEquals(com.wajub.mobile.model.ActionKind.PushApproval, action.action)
    }

    @Test
    fun buildsMobileMoneyPayloadWithTheNumberInE164() {
        val data = PaymentMapper.buildMobileMoneyRequest(
            MobileMoneyInput("cm.mtn", "699887766", "cm"),
        )
        assertEquals("+237699887766", data["phone"])
        assertEquals("CM", data["country"])
        assertEquals(null, data["operator"])
        val aggregate = PaymentMapper.buildMobileMoneyRequest(MobileMoneyInput("cm.mobile", "699887766", "CM", operator = "orange"))
        assertEquals("orange", aggregate["operator"])
    }
}
