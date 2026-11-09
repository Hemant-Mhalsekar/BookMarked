package com.bookmarked.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("""
            select t from RefreshToken t
            where t.user.id = :userId and t.revoked = false and t.expiresAt > :now
            order by t.lastUsedAt desc
            """)
    List<RefreshToken> findActiveByUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update RefreshToken t
            set t.revoked = true, t.revokedAt = :now, t.revokeReason = :reason
            where t.user.id = :userId and t.revoked = false
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Query("""
            update RefreshToken t
            set t.revoked = true, t.revokedAt = :now, t.revokeReason = :reason
            where t.user.id = :userId and t.revoked = false and t.id <> :keepId
            """)
    int revokeAllForUserExcept(@Param("userId") Long userId, @Param("keepId") Long keepId,
                               @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
