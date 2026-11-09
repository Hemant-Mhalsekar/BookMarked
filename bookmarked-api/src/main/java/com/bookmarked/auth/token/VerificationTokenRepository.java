package com.bookmarked.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    Optional<VerificationToken> findByTokenHashAndType(String tokenHash, TokenType type);

    /** Invalidate older unused tokens of the same kind, so only the newest link works. */
    @Modifying
    @Query("""
            update VerificationToken v set v.usedAt = :now
            where v.user.id = :userId and v.type = :type and v.usedAt is null
            """)
    int invalidateOpen(@Param("userId") Long userId, @Param("type") TokenType type, @Param("now") Instant now);

    @Modifying
    @Query("delete from VerificationToken v where v.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
