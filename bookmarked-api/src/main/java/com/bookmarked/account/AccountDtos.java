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

    public record NameRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = com.bookmarked.auth.dto.AuthDtos.NAME_REGEX,
                    message = "Name must be 2-60 letters") String fullName) {
    }

    public record EmailChangeRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Email
            @jakarta.validation.constraints.Size(max = 254) String newEmail,
            @jakarta.validation.constraints.NotBlank(message = "Enter your password") String password) {
    }

    public record DeleteAccountRequest(
            @jakarta.validation.constraints.NotBlank(message = "Enter your password") String password,
            @jakarta.validation.constraints.NotBlank(message = "Type DELETE to confirm") String confirmation) {
    }

    public record PhoneRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = com.bookmarked.auth.dto.AuthDtos.PHONE_REGEX,
                    message = "Enter a valid 10-digit mobile number") String phone,
            @jakarta.validation.constraints.NotBlank(message = "Enter your password") String password) {
    }
}
