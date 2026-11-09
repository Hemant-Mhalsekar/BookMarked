package com.bookmarked.auth.security;

import com.bookmarked.config.AppProperties;
import com.bookmarked.user.AccountType;
import com.bookmarked.user.Role;
import com.bookmarked.user.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private static AppProperties props(String secret) {
        return new AppProperties("http://localhost:5173", "test@bookmarked.local",
                new AppProperties.Jwt(secret, Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofDays(7), "bookmarked"),
                null, null, null, null);
    }

    private static User user(AccountType type) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 42L);
        u.setAccountType(type);
        u.setRole(new Role(type == AccountType.STAFF ? "SUPER_ADMIN" : "CUSTOMER"));
        return u;
    }

    @Test
    void issues_and_parses_a_token() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        JwtService.IssuedToken issued = jwt.issue(user(AccountType.CUSTOMER), 7L);

        AuthUser parsed = jwt.parse(issued.token());
        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.role()).isEqualTo("CUSTOMER");
        assertThat(parsed.sessionId()).isEqualTo(7L);
        assertThat(parsed.isStaff()).isFalse();
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void staff_tokens_are_shorter_and_carry_the_staff_authority() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        JwtService.IssuedToken issued = jwt.issue(user(AccountType.STAFF), 1L);
        assertThat(issued.expiresInSeconds()).isEqualTo(600);
        assertThat(jwt.parse(issued.token()).authorities())
                .extracting(Object::toString)
                .contains(AuthUser.STAFF_AUTHORITY, "ROLE_SUPER_ADMIN");
    }

    @Test
    void rejects_a_token_signed_with_another_key() {
        String foreign = new JwtService(props("another-secret-another-secret-0123456789abc"), Clock.systemUTC())
                .issue(user(AccountType.CUSTOMER), 1L).token();
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        assertThatThrownBy(() -> jwt.parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_an_expired_token() {
        Instant issuedAt = Instant.parse("2026-01-01T10:00:00Z");
        String token = new JwtService(props(SECRET), Clock.fixed(issuedAt, ZoneOffset.UTC))
                .issue(user(AccountType.CUSTOMER), 1L).token();
        JwtService later = new JwtService(props(SECRET), Clock.fixed(issuedAt.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void refuses_a_short_secret() {
        assertThatThrownBy(() -> new JwtService(props("too-short"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
