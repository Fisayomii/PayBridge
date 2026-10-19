package com.academy.paybridge.compliance.client;

import com.academy.paybridge.compliance.service.OfacProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads the public OFAC files. The Sanctions List Service answers with a redirect to a short-lived
 * storage link, which the JDK client follows (same scheme).
 */
@Component
public class OfacListClient {

    private final RestClient restClient;

    public OfacListClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        this.restClient = builder.clone()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "PayBridge/1.0")
                .build();
    }

    public Files download(OfacProperties props) {
        return new Files(fetch(props.sdnUrl()), fetch(props.altUrl()));
    }

    private String fetch(String url) {
        byte[] body = restClient.get().uri(url).retrieve().body(byte[].class);
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Empty download from " + url);
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public record Files(String sdnCsv, String altCsv) {}
}
