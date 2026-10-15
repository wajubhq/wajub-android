package com.wajub.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wajub.mobile.client.PayClient;
import com.wajub.mobile.mapper.RawChannel;
import com.wajub.mobile.mapper.RawProcessResponse;
import com.wajub.mobile.mapper.RawProcessingContext;
import com.wajub.mobile.mapper.RawSessionResponse;
import com.wajub.mobile.mapper.RawTransaction;
import com.wajub.mobile.model.MobileMoneyInput;
import com.wajub.mobile.model.ProcessingContext;
import com.wajub.mobile.model.SessionChannel;
import com.wajub.mobile.model.SessionData;
import com.wajub.mobile.model.SessionTransaction;
import com.wajub.mobile.model.WajubError;
import com.wajub.mobile.model.WajubErrorType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    @Test
    void the1_2_1CopiesStillExistAndKeepTheNewFields() {
        ProcessingContext context = new ProcessingContext(null, true, "Confirm on your phone", null)
            .copy("cm.mtn", true, "Confirm on your phone", "https://pay.example/x");
        assertEquals("cm.mtn", context.getChannelSlug());

        SessionTransaction transaction = new SessionTransaction(
            "trx_1", "ORDER-1", null, 1000.0, null, "XAF", "pending", null, false, null, context);
        SessionTransaction paid = transaction.copy("trx_1", "ORDER-1", null, 1000.0, null, "XAF", "succeeded", null, false, null, context);
        assertEquals("succeeded", paid.getStatus());

        SessionChannel channel = new SessionChannel(
            "cm.mtn", "cm.mtn", "MTN", null, "mobile_money", null, Collections.singletonList("CM"), null, null, "XAF");
        SessionChannel renamed = channel.copy(
            "cm.mtn", "cm.mtn", "MTN MoMo", null, "mobile_money", null, Collections.singletonList("CM"), null, null, "XAF");
        assertEquals("MTN MoMo", renamed.getName());

        SessionData data = new SessionData(transaction, Collections.singletonList(channel), "en", Collections.emptyMap(), null, null);
        assertEquals("fr", data.copy(transaction, Collections.singletonList(channel), "fr", Collections.emptyMap(), null, null).getLocale());

        MobileMoneyInput input = new MobileMoneyInput("cm.mobile", "699887766", "CM").copy("cm.mobile", "677123456", "CM");
        assertEquals("677123456", input.getPhone());

        RawProcessingContext rawContext = new RawProcessingContext(null, false, null, null).copy("cm.mtn", true, null, null);
        RawTransaction raw = new RawTransaction("trx_1", "ORDER-1", null, 1000.0, null, "XAF", "processing", null, false, null, null)
            .copy("trx_1", "ORDER-1", null, 1000.0, null, "XAF", "pending", null, false, null, rawContext);
        assertEquals("pending", raw.getStatus());
        RawChannel rawChannel = new RawChannel("cm.mtn", "cm.mtn", "MTN", null, "mobile_money", null, Collections.emptyList(), null, null, "XAF")
            .copy("cm.mtn", "cm.mtn", "MTN", "MTN", "mobile_money", null, Collections.emptyList(), null, null, "XAF");
        assertEquals("MTN", rawChannel.getNameFr());
        RawSessionResponse rawSession = new RawSessionResponse(raw, Collections.singletonList(rawChannel), null, Collections.emptyMap(), null, null)
            .copy(raw, Collections.singletonList(rawChannel), "fr", Collections.emptyMap(), null, null);
        assertEquals("fr", rawSession.getLocale());
        RawProcessResponse response = new RawProcessResponse(202, "Accepted", "", null, raw, null, null, null)
            .copy(200, "OK", "", null, raw, null, null, null);
        assertEquals(200, response.getCode());
    }

    // Code compiled against 1.2.1 calls `copy(status = …)` through its `copy$default`.
    @Test
    void the1_2_1CopyDefaultsStillLink() throws Exception {
        ProcessingContext context = new ProcessingContext("cm.mtn", true, "Confirm", null);
        SessionTransaction transaction = new SessionTransaction(
            "trx_1", "ORDER-1", null, 1000.0, null, "XAF", "pending", null, false, null, context);
        Method copy = SessionTransaction.class.getMethod("copy$default", SessionTransaction.class,
            String.class, String.class, String.class, double.class, Double.class, String.class, String.class, String.class,
            boolean.class, String.class, ProcessingContext.class, int.class, Object.class);
        // Every argument but `status` (bit 6) takes its default: this object's value.
        int mask = 0x7FF & ~(1 << 6);
        SessionTransaction changed = (SessionTransaction) copy.invoke(null, transaction,
            null, null, null, 0.0, null, null, "succeeded", null, false, null, null, mask, null);
        assertEquals("succeeded", changed.getStatus());
        assertEquals("trx_1", changed.getId());
        assertEquals(1000.0, changed.getAmount());
        assertEquals(context, changed.getProcessingContext());

        for (Object[] signature : new Object[][] {
            {ProcessingContext.class, new Class<?>[] {String.class, boolean.class, String.class, String.class}},
            {SessionChannel.class, new Class<?>[] {String.class, String.class, String.class, String.class, String.class, String.class, List.class, Double.class, Double.class, String.class}},
            {SessionData.class, new Class<?>[] {SessionTransaction.class, List.class, String.class, Map.class, com.wajub.mobile.model.EchoConfig.class, String.class}},
            {MobileMoneyInput.class, new Class<?>[] {String.class, String.class, String.class}},
            {RawTransaction.class, new Class<?>[] {String.class, String.class, String.class, double.class, Double.class, String.class, String.class, String.class, boolean.class, String.class, RawProcessingContext.class}},
            {RawProcessingContext.class, new Class<?>[] {String.class, boolean.class, String.class, String.class}},
            {RawChannel.class, new Class<?>[] {String.class, String.class, String.class, String.class, String.class, String.class, List.class, Double.class, Double.class, String.class}},
            {RawSessionResponse.class, new Class<?>[] {RawTransaction.class, List.class, String.class, Map.class, com.wajub.mobile.mapper.RawEchoConfig.class, String.class}},
            {RawProcessResponse.class, new Class<?>[] {int.class, String.class, String.class, String.class, RawTransaction.class, String.class, String.class, com.wajub.mobile.mapper.RawClientSession.class}},
        }) {
            Class<?> type = (Class<?>) signature[0];
            Class<?>[] fields = (Class<?>[]) signature[1];
            Class<?>[] parameters = new Class<?>[fields.length + 3];
            parameters[0] = type;
            System.arraycopy(fields, 0, parameters, 1, fields.length);
            parameters[fields.length + 1] = int.class;
            parameters[fields.length + 2] = Object.class;
            type.getMethod("copy$default", parameters);
            type.getMethod("copy", fields);
        }
    }
}
