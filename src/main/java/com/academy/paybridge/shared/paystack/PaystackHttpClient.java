package com.academy.paybridge.shared.paystack;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Shared Paystack HTTP plumbing (auth, timeouts, error classification) for the transfer and funding clients.
 * Every failure becomes either {@link GatewayRejectedException} (Paystack definitively said no) or
 * {@link GatewayUnavailableException} (outcome unknown), never a silent success.
 */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "paystack", matchIfMissing = true)
public class PaystackHttpClient {

    private static final Logger log = LogManager.getLogger(PaystackHttpClient.class);

    private final RestClient restClient;

    public PaystackHttpClient(
            RestClient.Builder restClientBuilder,
            @Value("${paybridge.paystack.base-url}") String baseUrl,
            @Value("${paybridge.paystack.secret-key}") String secretKey,
            @Value("${paybridge.paystack.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${paybridge.paystack.read-timeout:PT20S}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public <T> PaystackResponse<T> get(String operation, ParameterizedTypeReference<PaystackResponse<T>> type,
                                       String uri, Object... uriVariables) {
        return call(operation, () -> restClient.get().uri(uri, uriVariables).retrieve().body(type));
    }

    public <T> PaystackResponse<T> post(String operation, ParameterizedTypeReference<PaystackResponse<T>> type,
                                        String uri, Object body) {
        return call(operation, () -> restClient.post().uri(uri).body(body).retrieve().body(type));
    }

    private <T> PaystackResponse<T> call(String operation, java.util.function.Supplier<PaystackResponse<T>> request) {
        try {
            PaystackResponse<T> response = request.get();
            if (response == null) {
                throw new GatewayUnavailableException("Empty response from Paystack (" + operation + ")", null);
            }
            return response;
        } catch (RestClientResponseException ex) {
            String providerMessage = extractMessage(ex);
            if (ex.getStatusCode().is4xxClientError()
                    && ex.getStatusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value()) {
                log.warn("Paystack {} rejected ({}): {}", operation, ex.getStatusCode().value(), providerMessage);
                throw new GatewayRejectedException(providerMessage);
            }
            log.error("Paystack {} failed ({}): {}", operation, ex.getStatusCode().value(), providerMessage);
            throw new GatewayUnavailableException("Paystack " + operation + " failed: " + providerMessage, ex);
        } catch (ResourceAccessException ex) {
            log.error("Paystack {} timed out or could not connect: {}", operation, ex.getMessage());
            throw new GatewayUnavailableException("Paystack is unreachable (" + operation + ")", ex);
        } catch (RestClientException ex) {
            log.error("Paystack {} returned an unreadable response: {}", operation, ex.getMessage());
            throw new GatewayUnavailableException("Unreadable response from Paystack (" + operation + ")", ex);
        }
    }

    private static String extractMessage(RestClientResponseException ex) {
        try {
            PaystackResponse<?> body = ex.getResponseBodyAs(PaystackResponse.class);
            if (body != null && body.message() != null) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw status text
        }
        return ex.getStatusText();
    }
}
