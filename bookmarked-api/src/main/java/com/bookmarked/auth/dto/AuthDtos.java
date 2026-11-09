package com.bookmarked.auth.dto;

import com.bookmarked.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request and response bodies for /api/auth and /api/account.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ------------------------------------------------------------------
    // Shared validation rules (spec section 4, "Validation rules")
    // ------------------------------------------------------------------
    public static final String NAME_REGEX = "^[\\p{L} .'-]{2,60}$";
    public static final String PHONE_REGEX = "^[6-9]\\d{9}$";
    /** 8-64 chars with upper, lower, digit and special character. */
    public static final String PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,64}$";
    public static final String PASSWORD_MESSAGE =
            "Password must be 8-64 characters with upper and lower case letters, a digit and a special character";

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = NAME_REGEX, message = "Name must be 2-60 letters") String fullName,
            @NotBlank @Email(message = "Enter a valid email") @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = PHONE_REGEX, message = "Enter a valid 10-digit mobile number") String phone,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your email or mobile number") @Size(max = 254) String identifier,
            @NotBlank(message = "Enter your password") @Size(max = 128) String password) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ForgotPasswordRequest(@NotBlank @Size(max = 254) String identifier) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------
    public record UserDto(Long id, String fullName, String email, String phone, boolean emailVerified,
                          String accountType, String role, List<String> permissions) {

        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.isEmailVerified(),
                    u.getAccountType().name(), u.getRole().getName(), u.getRole().permissionNames());
        }
    }

    public record AuthResponse(String accessToken, long expiresIn, UserDto user) {
    }

    public record RegisterResponse(Long id, boolean emailVerified, String message) {
    }

    public record MessageResponse(String message) {
    }
}
