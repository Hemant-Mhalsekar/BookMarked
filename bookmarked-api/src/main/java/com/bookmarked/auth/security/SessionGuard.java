package com.bookmarked.auth.security;

import com.bookmarked.auth.token.RefreshToken;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * Makes logouts, force-logouts, role changes and blocks take effect at once instead
 * of when the 10–15 minute access token expires. Each token carries its session id;
 * the session is accepted while it isn't revoked and the account is enabled.
 * <p>
 * A session replaced by a normal refresh ("ROTATED") stays acceptable until its access
 * token expires, so a second tab with the older token keeps working. Answers are
 * cached for 20 seconds and dropped immediately when the app itself revokes something.
 */
@Component
public class SessionGuard {

    private final JdbcTemplate jdbc;
    private final Cache<Long, Boolean> sessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(20))
            .maximumSize(100_000)
            .build();

    public SessionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean allows(AuthUser user) {
        if (user.sessionId() == null) {
            return true;
        }
        return Boolean.TRUE.equals(sessions.get(user.sessionId(), this::lookup));
    }

    private Boolean lookup(Long sessionId) {
        List<Boolean> rows = jdbc.query("""
                select (rt.revoked = false or rt.revoke_reason = ?) and u.enabled = true and u.deleted_at is null
                from refresh_tokens rt join users u on u.id = rt.user_id where rt.id = ?
                """, (rs, i) -> rs.getBoolean(1), RefreshToken.REASON_ROTATED, sessionId);
        return !rows.isEmpty() && rows.get(0);
    }

    /** Call after revoking sessions or disabling accounts (inside a transaction: runs after it commits). */
    public void forgetAll() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sessions.invalidateAll();
                }
            });
        } else {
            sessions.invalidateAll();
        }
    }
}
