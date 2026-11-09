package com.bookmarked.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * In-memory token-bucket rate limiting (Bucket4j). One bucket per (rule, key),
 * e.g. ("login", ip). Buckets idle for an hour are evicted.
 * <p>
 * For several API instances this would move to Redis; one instance is enough here.
 */
@Service
public class RateLimitService {

    public enum Rule {
        LOGIN(10, Duration.ofMinutes(1)),
        REGISTER(5, Duration.ofMinutes(10)),
        EMAIL_ACTION(3, Duration.ofMinutes(15)),
        REFRESH(30, Duration.ofMinutes(1)),
        PASSWORD_CHANGE(5, Duration.ofMinutes(15));

        final int capacity;
        final Duration period;

        Rule(int capacity, Duration period) {
            this.capacity = capacity;
            this.period = period;
        }
    }

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    /** Throws 429 when the caller has used up the rule's allowance. */
    public void check(Rule rule, String key) {
        Bucket bucket = buckets.get(rule.name() + ":" + key, k -> newBucket(rule));
        if (!bucket.tryConsume(1)) {
            throw ApiException.tooManyRequests();
        }
    }

    private Bucket newBucket(Rule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rule.capacity)
                        .refillIntervally(rule.capacity, rule.period)
                        .build())
                .build();
    }
}
