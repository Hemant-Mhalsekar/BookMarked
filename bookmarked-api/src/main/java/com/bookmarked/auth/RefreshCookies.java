package com.bookmarked.auth;

import com.bookmarked.config.AppProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Builds the httpOnly refresh-token cookie. JavaScript can never read it, it is
 * only sent to /api/auth (path-scoped), and SameSite=Strict keeps other sites
 * from triggering it.
 */
@Component
public class RefreshCookies {

    private final AppProperties.Cookie config;
    private final Duration ttl;

    public RefreshCookies(AppProperties props) {
        this.config = props.cookie();
        this.ttl = props.jwt().refreshTtl();
    }

    public String name() {
        return config.name();
    }

    public String issue(String rawToken) {
        return base(rawToken).maxAge(ttl).build().toString();
    }

    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(config.name(), value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(config.path());
        if (StringUtils.hasText(config.domain())) {
            b.domain(config.domain());
        }
        return b;
    }
}
