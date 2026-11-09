package com.bookmarked.auth.security;

import com.bookmarked.config.AppProperties;
import com.bookmarked.user.AccountType;
import com.bookmarked.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues and verifies short-lived HS256 access tokens.
 * Claims: sub (user id), role, perms, acct (CUSTOMER/STAFF), sid (session id).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final AppProperties.Jwt config;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        this.config = props.jwt();
        this.clock = clock;
        byte[] secret = config.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (256 bits)");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public record IssuedToken(String token, long expiresInSeconds) {
    }

    public IssuedToken issue(User user, Long sessionId) {
        Duration ttl = user.isStaff() ? config.staffAccessTtl() : config.accessTtl();
        Instant now = clock.instant();
        String token = Jwts.builder()
                .issuer(config.issuer())
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().getName())
                .claim("perms", user.getRole().permissionNames())
                .claim("acct", user.getAccountType().name())
                .claim("sid", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
        return new IssuedToken(token, ttl.toSeconds());
    }

    /**
     * @throws JwtException when the token is invalid, tampered with or expired
     */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(config.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        List<?> rawPerms = claims.get("perms", List.class);
        List<String> perms = rawPerms == null ? List.of() : rawPerms.stream().map(String::valueOf).toList();
        Number sid = claims.get("sid", Number.class);

        return new AuthUser(
                Long.valueOf(claims.getSubject()),
                claims.get("role", String.class),
                AccountType.valueOf(claims.get("acct", String.class)),
                sid == null ? null : sid.longValue(),
                perms);
    }
}
