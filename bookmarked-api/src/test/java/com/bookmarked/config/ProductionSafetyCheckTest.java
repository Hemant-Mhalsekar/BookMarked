package com.bookmarked.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static AppProperties props(String secret, boolean secureCookie, String frontend) {
        return new AppProperties(frontend, "BookMarked <no-reply@example.com>",
                new AppProperties.Jwt(secret, Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofDays(7), "bookmarked"),
                new AppProperties.Cookie("bm_refresh", "", secureCookie, "Strict", "/api/auth"),
                null, null, null);
    }

    @Test
    void accepts_a_real_configuration() {
        assertThat(ProductionSafetyCheck.problems(props("q8Vh3kP0x9mZr2Lw7Ny4Tb6Jc1Fd5Gs8Ue0Ai3Ok", true,
                "https://bookmarked.vercel.app"))).isEmpty();
    }

    @Test
    void refuses_development_defaults() {
        assertThat(ProductionSafetyCheck.problems(props("dev-only-secret-change-me-dev-only-secret-change-me-0123456789",
                false, "http://localhost:5173"))).hasSize(3);
        assertThat(ProductionSafetyCheck.problems(props("short", true, "https://x.app"))).hasSize(1);
        assertThatThrownBy(() -> new ProductionSafetyCheck(props("short", false, "https://x.app")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COOKIE_SECURE");
    }
}
