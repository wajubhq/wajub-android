package com.wajub.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wajub.mobile.client.PayClient;
import com.wajub.mobile.mapper.RawProcessResponse;
import com.wajub.mobile.mapper.RawTransaction;
import com.wajub.mobile.model.MobileMoneyInput;
import com.wajub.mobile.model.ProcessingContext;
import com.wajub.mobile.model.SessionChannel;
import com.wajub.mobile.model.SessionData;
import com.wajub.mobile.model.SessionTransaction;
import com.wajub.mobile.model.WajubError;
import com.wajub.mobile.model.WajubErrorType;
import java.util.Collections;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/** The 1.2.1 API as Java called it: these calls must keep compiling and linking. */
class JavaCompatibilityTest {
    @Test
    void the1_2_1ConstructorsStillExist() {
        new PayClient();
        new PayClient(new OkHttpClient());
        WajubSession session = WajubMobile.INSTANCE.createSession("tok");
        assertEquals("https://api.wajub.com", WajubMobileKt.API_URL);

        MobileMoneyInput input = new MobileMoneyInput("cm.mtn", "699887766", "CM");
        assertNull(input.getOperator());

        WajubError error = new WajubError(
            WajubErrorType.PaymentError, "Declined", "insufficient_funds", "insufficient_funds", true, null, null, null,
            Collections.emptyMap(), 402);
        assertEquals("insufficient_funds", error.getCode());

        ProcessingContext context = new ProcessingContext(null, true, "Confirm on your phone", null);
        SessionTransaction transaction = new SessionTransaction(
            "trx_1", "ORDER-1", null, 1000.0, null, "XAF", "pending", null, false, null, context);
        SessionChannel channel = new SessionChannel(
            "cm.mtn", "cm.mtn", "MTN", null, "mobile_money", null, Collections.singletonList("CM"), null, null, "XAF");
        SessionData data = new SessionData(transaction, Collections.singletonList(channel), "en", Collections.emptyMap(), null, null);
        assertEquals("trx_1", data.getTransaction().getId());

        RawTransaction raw = new RawTransaction("trx_1", "ORDER-1", null, 1000.0, null, "XAF", "processing", null, false, null, null);
        RawProcessResponse response = new RawProcessResponse(202, "Accepted", "", null, raw, null, null, null);
        assertEquals(202, response.getCode());
        assertEquals(false, session.toString().contains("tok"), "a session never prints its token");
    }
}
