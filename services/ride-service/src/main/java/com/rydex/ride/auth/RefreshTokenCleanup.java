package com.rydex.ride.auth;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes refresh tokens a day after they expired. Revoked ones are kept until then, because
 * reuse detection needs to recognise a rotated token presented again.
 */
@Component
class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);
    private static final Duration GRACE = Duration.ofDays(1);

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    RefreshTokenCleanup(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void purgeExpired() {
        int deleted = tokens.deleteExpiredBefore(clock.instant().minus(GRACE));
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }
}
