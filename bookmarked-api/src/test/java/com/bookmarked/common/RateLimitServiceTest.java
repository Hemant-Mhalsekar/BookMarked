package com.bookmarked.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RateLimitServiceTest {

    @Test
    void allows_up_to_the_limit_then_answers_429() {
        RateLimitService limiter = new RateLimitService();
        for (int i = 0; i < 10; i++) {
            limiter.check(RateLimitService.Rule.LOGIN, "1.2.3.4");
        }
        assertThatThrownBy(() -> limiter.check(RateLimitService.Rule.LOGIN, "1.2.3.4"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test
    void keys_are_independent() {
        RateLimitService limiter = new RateLimitService();
        for (int i = 0; i < 10; i++) {
            limiter.check(RateLimitService.Rule.LOGIN, "1.1.1.1");
        }
        assertThatNoException().isThrownBy(() -> limiter.check(RateLimitService.Rule.LOGIN, "2.2.2.2"));
    }
}
