package com.bookmarked.auth;

import com.bookmarked.auth.dto.AuthDtos.AuthResponse;
import com.bookmarked.auth.dto.AuthDtos.EmailRequest;
import com.bookmarked.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.bookmarked.auth.dto.AuthDtos.LoginRequest;
import com.bookmarked.auth.dto.AuthDtos.MessageResponse;
import com.bookmarked.auth.dto.AuthDtos.RegisterRequest;
import com.bookmarked.auth.dto.AuthDtos.RegisterResponse;
import com.bookmarked.auth.dto.AuthDtos.ResetPasswordRequest;
import com.bookmarked.auth.dto.AuthDtos.TokenRequest;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.ClientInfo;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.common.RateLimitService.Rule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login, sessions and password recovery")
public class AuthController {

    /**
     * Cookie-based endpoints must carry this header. Browsers only let a page add
     * custom headers to same-origin requests (or after a CORS preflight our
     * server rejects for other origins), so a forged cross-site request can't.
     */
    static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService authService;
    private final SessionService sessionService;
    private final RefreshCookies cookies;
    private final RateLimitService rateLimit;

    public AuthController(AuthService authService, SessionService sessionService, RefreshCookies cookies,
                          RateLimitService rateLimit) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.cookies = cookies;
        this.rateLimit = rateLimit;
    }

    @Operation(summary = "Create a customer account and email a verification link")
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        rateLimit.check(Rule.REGISTER, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @Operation(summary = "Confirm an email address with the emailed token")
    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest req) {
        authService.verifyEmail(req.token());
        return new MessageResponse("Email verified. You're all set!");
    }

    @Operation(summary = "Send a fresh verification link (same answer whether or not the account exists)")
    @PostMapping("/resend-verification")
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest req, HttpServletRequest http) {
        rateLimit.check(Rule.EMAIL_ACTION, http.getRemoteAddr());
        authService.resendVerification(req.email());
        return new MessageResponse("If that account needs verifying, we've sent a new link.");
    }

    @Operation(summary = "Log in with email or mobile number; sets the refresh cookie")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        rateLimit.check(Rule.LOGIN, http.getRemoteAddr());
        SessionService.IssuedSession session = authService.login(req.identifier(), req.password(), ClientInfo.from(http));
        return withCookie(session);
    }

    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the cookie)")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = "${app.cookie.name}", required = false) String token,
                                                HttpServletRequest http, HttpServletResponse response) {
        requireCsrfHeader(http);
        rateLimit.check(Rule.REFRESH, http.getRemoteAddr());
        try {
            return withCookie(sessionService.rotate(token, ClientInfo.from(http)));
        } catch (ApiException e) {
            // A benign two-tab race keeps the (newer) cookie; anything else clears it.
            if (!"REFRESH_ROTATED".equals(e.getCode())) {
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
            }
            throw e;
        }
    }

    @Operation(summary = "End this device's session and clear the cookie")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "${app.cookie.name}", required = false) String token,
                                       HttpServletRequest http) {
        requireCsrfHeader(http);
        sessionService.end(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @Operation(summary = "Email a password reset link (same answer whether or not the account exists)")
    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
        rateLimit.check(Rule.EMAIL_ACTION, http.getRemoteAddr());
        authService.forgotPassword(req.identifier());
        return new MessageResponse("If an account exists for that email or number, we've sent a reset link.");
    }

    @Operation(summary = "Set a new password with the emailed token; signs out every device")
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return new MessageResponse("Password updated. Please log in with your new password.");
    }

    private ResponseEntity<AuthResponse> withCookie(SessionService.IssuedSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.rawRefreshToken()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(session.response());
    }

    private static void requireCsrfHeader(HttpServletRequest http) {
        if (http.getHeader(CSRF_HEADER) == null) {
            throw ApiException.forbidden("CSRF_CHECK_FAILED", "Missing required request header.");
        }
    }
}
