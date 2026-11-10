package com.bookmarked.auth;

import com.bookmarked.auth.dto.AuthDtos.AuthResponse;
import com.bookmarked.auth.dto.AuthDtos.UserDto;
import com.bookmarked.auth.security.JwtService;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.ClientInfo;
import com.bookmarked.common.Tokens;
import com.bookmarked.config.AppProperties;
import com.bookmarked.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Sessions = refresh tokens. Handles creation at login, rotation on refresh,
 * and theft detection when an already-rotated token is replayed.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwtService;
    private final Clock clock;
    private final Duration refreshTtl;
    private final Duration reuseGrace;
    private final com.bookmarked.auth.security.SessionGuard sessionGuard;

    public SessionService(RefreshTokenRepository refreshTokens, JwtService jwtService, Clock clock,
                          AppProperties props, com.bookmarked.auth.security.SessionGuard sessionGuard) {
        this.sessionGuard = sessionGuard;
        this.refreshTokens = refreshTokens;
        this.jwtService = jwtService;
        this.clock = clock;
        this.refreshTtl = props.jwt().refreshTtl();
        this.reuseGrace = props.security().refreshReuseGrace();
    }

    /** What a successful login or refresh hands back: the JSON body plus the raw cookie value. */
    public record IssuedSession(AuthResponse response, String rawRefreshToken) {
    }

    /** Starts a new session (one device) for a user who has just authenticated. */
    @Transactional
    public IssuedSession start(User user, ClientInfo client) {
        String raw = Tokens.newToken();
        RefreshToken session = refreshTokens.save(new RefreshToken(
                user, Tokens.sha256(raw), client.userAgent(), client.ipAddress(),
                clock.instant().plus(refreshTtl)));
        return issue(user, session, raw);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token.
     * <ul>
     *   <li>Replayed within the grace window after rotation (two tabs refreshing at
     *       once) → 401 REFRESH_ROTATED; the client retries with the newer cookie.</li>
     *   <li>Replayed after the grace window → treated as theft: every session of
     *       that user is revoked.</li>
     * </ul>
     */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedSession rotate(String rawToken, ClientInfo client) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("NO_SESSION", "No active session.");
        }
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByTokenHash(Tokens.sha256(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("SESSION_INVALID", "Your session is no longer valid."));

        if (current.isRevoked()) {
            boolean rotated = RefreshToken.REASON_ROTATED.equals(current.getRevokeReason());
            if (rotated && current.getRevokedAt() != null && current.getRevokedAt().plus(reuseGrace).isAfter(now)) {
                throw ApiException.unauthorized("REFRESH_ROTATED", "Session was just refreshed. Retry.");
            }
            if (rotated) {
                Long userId = current.getUser().getId();
                int revoked = refreshTokens.revokeAllForUser(userId, RefreshToken.REASON_REUSE_DETECTED, now);
                log.warn("Refresh token reuse detected for user {} from {}; revoked {} sessions",
                        userId, client.ipAddress(), revoked);
            }
            throw ApiException.unauthorized("SESSION_REVOKED", "Your session has ended. Please log in again.");
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("SESSION_EXPIRED", "Your session has expired. Please log in again.");
        }

        User user = current.getUser();
        if (!user.isEnabled() || user.isDeleted()) {
            current.revoke(RefreshToken.REASON_ADMIN, now);
            throw ApiException.unauthorized("SESSION_REVOKED", "Your session has ended. Please log in again.");
        }

        current.revoke(RefreshToken.REASON_ROTATED, now);
        String raw = Tokens.newToken();
        RefreshToken next = refreshTokens.save(new RefreshToken(
                user, Tokens.sha256(raw), client.userAgent(), client.ipAddress(), now.plus(refreshTtl)));
        return issue(user, next, raw);
    }

    /** Ends the session behind this cookie. Unknown or already-ended tokens are ignored. */
    @Transactional
    public void end(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(Tokens.sha256(rawToken))
                .ifPresent(t -> {
                    t.revoke(RefreshToken.REASON_LOGOUT, clock.instant());
                    sessionGuard.forgetAll();
                });
    }

    private IssuedSession issue(User user, RefreshToken session, String raw) {
        JwtService.IssuedToken access = jwtService.issue(user, session.getId());
        return new IssuedSession(new AuthResponse(access.token(), access.expiresInSeconds(), UserDto.from(user)), raw);
    }
}
