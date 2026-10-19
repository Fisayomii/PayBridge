package com.academy.paybridge.customer.client;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Dojah basic BVN lookup: {@code GET /api/v1/kyc/bvn/full?bvn=...} with headers {@code AppId} and
 * {@code Authorization} (the secret key, no "Bearer"). The sandbox (https://sandbox.dojah.io) answers with
 * predictable data for the test BVN 22222222222 and never charges.
 */
@Component
@ConditionalOnProperty(name = "paybridge.kyc.provider", havingValue = "dojah")
public class DojahIdentityVerifier implements IdentityVerifier {

    private static final Logger log = LogManager.getLogger(DojahIdentityVerifier.class);

    private final RestClient restClient;

    public DojahIdentityVerifier(RestClient.Builder builder,
                                 @Value("${paybridge.kyc.dojah.base-url}") String baseUrl,
                                 @Value("${paybridge.kyc.dojah.app-id}") String appId,
                                 @Value("${paybridge.kyc.dojah.secret-key}") String secretKey) {
        if (appId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("paybridge.kyc.provider=dojah needs DOJAH_APP_ID and DOJAH_SECRET_KEY");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("AppId", appId)
                .defaultHeader("Authorization", secretKey)
                // Dojah sits behind Cloudflare, which rejects some default client signatures (error 1010).
                .defaultHeader("User-Agent", "PayBridge/1.0")
                .build();
    }

    @Override
    public BvnRecord lookupBvn(String bvn) {
        BvnResponse response;
        try {
            response = restClient.get().uri("/api/v1/kyc/bvn/full?bvn={bvn}", bvn).retrieve().body(BvnResponse.class);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 401 || code == 403) {
                log.error("Dojah rejected our credentials ({}): check DOJAH_APP_ID / DOJAH_SECRET_KEY", code);
                throw new GatewayUnavailableException("Identity provider authentication failed", ex);
            }
            if (ex.getStatusCode().is4xxClientError() && code != 429) {
                throw new GatewayRejectedException("BVN could not be verified");
            }
            throw new GatewayUnavailableException("Identity provider error " + code, ex);
        } catch (ResourceAccessException ex) {
            throw new GatewayUnavailableException("Identity provider unreachable", ex);
        }
        if (response == null || response.entity() == null || response.entity().firstName() == null) {
            throw new GatewayRejectedException("BVN could not be verified");
        }
        Entity e = response.entity();
        return new BvnRecord(e.firstName(), e.middleName(), e.lastName(), e.dateOfBirth());
    }

    @Override
    public String providerName() {
        return "DOJAH";
    }

    record BvnResponse(Entity entity) {}

    record Entity(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("middle_name") String middleName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("date_of_birth") String dateOfBirth
    ) {}
}
