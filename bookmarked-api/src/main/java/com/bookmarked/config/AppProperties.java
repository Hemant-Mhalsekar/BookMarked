package com.bookmarked.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Typed view of the {@code app.*} block in application.yml.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        String mailFrom,
        Jwt jwt,
        Cookie cookie,
        Security security,
        Seed seed,
        Business business
) {

    public record Jwt(String secret, Duration accessTtl, Duration staffAccessTtl, Duration refreshTtl, String issuer) {
    }

    public record Cookie(String name, String domain, boolean secure, String sameSite, String path) {
    }

    public record Security(int maxFailedLogins, Duration lockDuration, Duration refreshReuseGrace,
                           Duration emailVerificationTtl, Duration passwordResetTtl) {
    }

    public record Seed(String superAdminEmail, String superAdminPassword, String superAdminPhone,
                       String superAdminName) {
    }

    public record Business(int reservationMinutes, BigDecimal deliveryFee, BigDecimal freeDeliveryThreshold,
                           BigDecimal codMaxOrderValue, BigDecimal codFee, int returnWindowDays,
                           int ebookDownloadLimit, int lowStockThreshold) {
    }
}
