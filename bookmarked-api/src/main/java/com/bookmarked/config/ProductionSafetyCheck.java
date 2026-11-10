package com.bookmarked.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start the {@code prod} profile with development defaults: a known JWT
 * secret, cookies over plain HTTP, or a non-HTTPS frontend. A misconfigured deploy
 * fails loudly instead of running insecurely.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    public ProductionSafetyCheck(AppProperties props) {
        List<String> problems = problems(props);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", problems));
        }
        log.info("Production configuration checks passed");
    }

    static List<String> problems(AppProperties props) {
        List<String> problems = new ArrayList<>();
        String secret = props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || secret.contains("dev-only")) {
            problems.add("JWT_SECRET must be a random value of at least 32 characters (openssl rand -base64 48)");
        }
        if (!props.cookie().secure()) {
            problems.add("COOKIE_SECURE must be true");
        }
        if (props.frontendUrl() == null || !props.frontendUrl().startsWith("https://")) {
            problems.add("FRONTEND_URL must be the https:// address of the site");
        }
        return problems;
    }
}
