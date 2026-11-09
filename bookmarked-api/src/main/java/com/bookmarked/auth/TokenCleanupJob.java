package com.bookmarked.auth;

import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.auth.token.VerificationTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Nightly housekeeping: drop long-expired refresh and email tokens. */
@Component
public class TokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokens;
    private final VerificationTokenRepository verificationTokens;
    private final Clock clock;

    public TokenCleanupJob(RefreshTokenRepository refreshTokens, VerificationTokenRepository verificationTokens,
                           Clock clock) {
        this.refreshTokens = refreshTokens;
        this.verificationTokens = verificationTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void purgeExpiredTokens() {
        Instant now = clock.instant();
        int refresh = refreshTokens.deleteExpiredBefore(now.minus(Duration.ofDays(30)));
        int verification = verificationTokens.deleteExpiredBefore(now.minus(Duration.ofDays(7)));
        log.info("Token cleanup: removed {} refresh and {} verification tokens", refresh, verification);
    }
}
