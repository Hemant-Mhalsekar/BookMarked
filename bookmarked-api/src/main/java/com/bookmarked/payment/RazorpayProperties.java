package com.bookmarked.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Razorpay credentials from the environment (.env locally, hosting dashboard in
 * production). Only the key id is ever sent to the browser.
 */
@ConfigurationProperties(prefix = "app.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl) {

    public boolean configured() {
        return StringUtils.hasText(keyId) && StringUtils.hasText(keySecret);
    }

    public boolean webhookConfigured() {
        return StringUtils.hasText(webhookSecret);
    }
}
