package com.bookmarked.account;

import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    /** One logged-in device. {@code current} marks the device making this request. */
    public record SessionDto(Long id, String device, String ipAddress, Instant createdAt, Instant lastUsedAt,
                             Instant expiresAt, boolean current) {
    }

    public record LoginHistoryDto(Long id, String device, String ipAddress, boolean success, Instant at) {
    }
}
