package com.bookmarked.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Razorpay REST API over HTTPS with Basic auth (key id + key secret).
 * Uses Spring's RestClient rather than the Razorpay SDK: two endpoints don't
 * justify another dependency, and the calls are easy to read.
 */
@Component
public class RazorpayHttpGateway implements RazorpayGateway {

    private final RazorpayProperties props;
    private final RestClient client;

    public RazorpayHttpGateway(RazorpayProperties props, RestClient.Builder builder) {
        this.props = props;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(15));
        this.client = builder
                .baseUrl(props.baseUrl() == null ? "https://api.razorpay.com" : props.baseUrl())
                .requestFactory(timeouts)
                .defaultHeaders(h -> {
                    if (props.configured()) {
                        h.setBasicAuth(props.keyId(), props.keySecret());
                    }
                })
                .build();
    }

    @Override
    public String createOrder(long amountPaise, String receipt) {
        requireKeys();
        try {
            JsonNode res = client.post().uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt))
                    .retrieve()
                    .body(JsonNode.class);
            return res.get("id").asText();
        } catch (RestClientException | NullPointerException e) {
            throw new GatewayException("Could not create Razorpay order: " + describe(e), e);
        }
    }

    @Override
    public String refund(String paymentId, long amountPaise) {
        requireKeys();
        try {
            JsonNode res = client.post().uri("/v1/payments/{id}/refund", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise))
                    .retrieve()
                    .body(JsonNode.class);
            return res.get("id").asText();
        } catch (RestClientException | NullPointerException e) {
            throw new GatewayException("Could not refund payment " + paymentId + ": " + describe(e), e);
        }
    }

    /** One readable line for the log, e.g. "Razorpay rejected the API keys (401)…". Never includes the keys. */
    static String describe(Exception e) {
        if (e instanceof HttpClientErrorException.Unauthorized) {
            return "Razorpay rejected the API keys (401). Check RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are a matching Test Mode pair";
        }
        if (e instanceof HttpStatusCodeException http) {
            String body = http.getResponseBodyAsString();
            return "Razorpay answered " + http.getStatusCode().value()
                    + (body.isBlank() ? "" : " " + (body.length() > 300 ? body.substring(0, 300) : body));
        }
        if (e instanceof ResourceAccessException) {
            return "couldn't reach Razorpay (" + e.getMessage() + ")";
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
    }

    private void requireKeys() {
        if (!props.configured()) {
            throw new GatewayException("Razorpay keys are not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET)", null);
        }
    }
}
