package com.academy.paybridge.shared.paystack;

import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaystackWebhookControllerTest {

    private static final String SECRET = "sk_test_webhook_secret";
    private static final String TRANSFER_BODY = """
            {"event":"transfer.failed","data":{"reference":"pb-trf-abc","transfer_code":"TRF_1","reason":"Bank down"}}""";

    private final RecordingHandler transfers = new RecordingHandler("transfer.");
    private final RecordingHandler charges = new RecordingHandler("charge.success");
    private final PaystackWebhookController controller = new PaystackWebhookController(
            List.of(transfers, charges), JsonMapper.builder().build(), SECRET);

    @Test
    void validSignatureIsDispatchedToTheOwningModule() throws Exception {
        var response = controller.receive(sign(TRANSFER_BODY, SECRET), bytes(TRANSFER_BODY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transfers.received).containsExactly("transfer.failed:pb-trf-abc");
        assertThat(charges.received).isEmpty();
    }

    @Test
    void chargeEventsGoToFunding() throws Exception {
        String body = """
                {"event":"charge.success","data":{"reference":"pb-fnd-xyz","amount":2000000}}""";
        controller.receive(sign(body, SECRET), bytes(body));
        assertThat(charges.received).containsExactly("charge.success:pb-fnd-xyz");
    }

    @Test
    void forgedSignatureIsRejectedWithoutTouchingMoney() throws Exception {
        var response = controller.receive(sign(TRANSFER_BODY, "sk_test_attacker"), bytes(TRANSFER_BODY));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(transfers.received).isEmpty();
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        String signature = sign(TRANSFER_BODY, SECRET);
        var response = controller.receive(signature, bytes(TRANSFER_BODY.replace("transfer.failed", "transfer.reversed")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(transfers.received).isEmpty();
    }

    @Test
    void missingSignatureIsRejected() {
        assertThat(controller.receive(null, bytes(TRANSFER_BODY)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownReferenceIsAcknowledgedSoPaystackStopsRetrying() throws Exception {
        transfers.failWith = PayBridgeException.notFound("Transfer not found");
        assertThat(controller.receive(sign(TRANSFER_BODY, SECRET), bytes(TRANSFER_BODY)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void processingFailurePropagatesSoPaystackRetries() throws Exception {
        transfers.failWith = new IllegalStateException("database down");
        assertThatThrownBy(() -> controller.receive(sign(TRANSFER_BODY, SECRET), bytes(TRANSFER_BODY)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(bytes(body)));
    }

    private static final class RecordingHandler implements PaystackWebhookHandler {
        final String prefix;
        final List<String> received = new ArrayList<>();
        RuntimeException failWith;

        RecordingHandler(String prefix) { this.prefix = prefix; }

        @Override public boolean handles(String event) { return event.startsWith(prefix); }

        @Override public void handle(String event, JsonNode data) {
            if (failWith != null) throw failWith;
            received.add(event + ":" + data.path("reference").asString());
        }
    }
}
