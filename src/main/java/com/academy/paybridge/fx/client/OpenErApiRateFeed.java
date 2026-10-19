package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.service.FxProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * ExchangeRate-API's open endpoint ({@code https://open.er-api.com/v6/latest/USD}): no key, one call returns
 * every rate against USD. It is a reference rate, not a tradeable one: good enough to price a demo, and
 * the deviation guard in FxService still stands between it and live quotes.
 */
@Component
public class OpenErApiRateFeed implements RateFeed {

    private final RestClient restClient;

    public OpenErApiRateFeed(RestClient.Builder builder, FxProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = builder.clone()
                .baseUrl(props.feed() == null ? "https://open.er-api.com" : props.feed().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public FeedRate fetchUsdNgn() {
        LatestRates body = restClient.get().uri("/v6/latest/USD").retrieve().body(LatestRates.class);
        if (body == null || !"success".equals(body.result()) || body.rates() == null) {
            throw new IllegalStateException("Rate feed returned no data");
        }
        BigDecimal ngn = body.rates().get("NGN");
        if (ngn == null || ngn.signum() <= 0) {
            throw new IllegalStateException("Rate feed has no USD/NGN rate");
        }
        return new FeedRate(ngn, Instant.ofEpochSecond(body.lastUpdateUnix()));
    }

    @Override
    public String name() {
        return "OPEN_ER_API";
    }

    record LatestRates(
            String result,
            @JsonProperty("time_last_update_unix") long lastUpdateUnix,
            Map<String, BigDecimal> rates
    ) {}
}
