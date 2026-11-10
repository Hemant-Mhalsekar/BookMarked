package com.bookmarked.auth;

import com.bookmarked.auth.dto.AuthDtos.RegisterRequest;
import com.bookmarked.auth.dto.AuthDtos.RegisterResponse;
import com.bookmarked.auth.history.LoginHistory;
import com.bookmarked.auth.history.LoginHistoryRepository;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.auth.token.TokenType;
import com.bookmarked.auth.token.VerificationToken;
import com.bookmarked.auth.token.VerificationTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.ClientInfo;
import com.bookmarked.common.Tokens;
import com.bookmarked.common.UserAgents;
import com.bookmarked.config.AppProperties;
import com.bookmarked.mail.MailService;
import com.bookmarked.mail.MailService.Recipient;
import com.bookmarked.user.AccountType;
import com.bookmarked.user.Role;
import com.bookmarked.user.RoleRepository;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registration, email verification, login with email or phone, lockout,
 * forgot / reset password.
 */
@Service
public class AuthService {

    private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");

    private final UserRepository users;
    private final RoleRepository roles;
    private final VerificationTokenRepository verificationTokens;
    private final RefreshTokenRepository refreshTokens;
    private final LoginHistoryRepository loginHistory;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final AppProperties.Security security;
    private final Clock clock;

    /** Compared against when the account does not exist, so response time doesn't reveal that. */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, VerificationTokenRepository verificationTokens,
                       RefreshTokenRepository refreshTokens, LoginHistoryRepository loginHistory,
                       SessionService sessions, PasswordEncoder passwordEncoder, MailService mail,
                       AppProperties props, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.verificationTokens = verificationTokens;
        this.refreshTokens = refreshTokens;
        this.loginHistory = loginHistory;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.security = props.security();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    // ------------------------------------------------------------------
    // Registration & email verification
    // ------------------------------------------------------------------

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        if (users.existsByPhone(req.phone())) {
            throw ApiException.conflict("PHONE_TAKEN", "An account with this mobile number already exists.");
        }
        Role customer = roles.findByName(Role.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role missing; check Flyway seed"));

        User user = new User();
        user.setFullName(req.fullName().trim());
        user.setEmail(email);
        user.setPhone(req.phone());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setAccountType(AccountType.CUSTOMER);
        user.setRole(customer);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups racing for the same email/phone
            throw ApiException.conflict("ACCOUNT_EXISTS", "An account with these details already exists.");
        }

        sendVerification(user);
        return new RegisterResponse(user.getId(), false,
                "Account created. We've sent a verification link to " + email + ".");
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        Instant now = clock.instant();
        VerificationToken token = verificationTokens
                .findByTokenHashAndType(Tokens.sha256(rawToken), TokenType.EMAIL_VERIFY)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID",
                        "This verification link is invalid or has expired. Request a new one."));
        token.markUsed(now);
        token.getUser().setEmailVerified(true);
    }

    /** Always succeeds from the caller's view, so it can't be used to discover accounts. */
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(normalizeEmail(email))
                .filter(u -> !u.isEmailVerified() && !u.isDeleted() && u.isEnabled())
                .ifPresent(this::sendVerification);
    }

    private void sendVerification(User user) {
        Instant now = clock.instant();
        verificationTokens.invalidateOpen(user.getId(), TokenType.EMAIL_VERIFY, now);
        String raw = Tokens.newToken();
        verificationTokens.save(new VerificationToken(user, Tokens.sha256(raw), TokenType.EMAIL_VERIFY,
                now.plus(security.emailVerificationTtl())));
        mail.sendEmailVerification(recipient(user), raw);
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    /**
     * Failed attempts must be saved even though we answer with an error,
     * hence noRollbackFor.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SessionService.IssuedSession login(String identifier, String password, ClientInfo client) {
        Instant now = clock.instant();
        Optional<User> found = findByIdentifier(identifier).filter(u -> !u.isDeleted());

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            record(null, client, false);
            throw invalidCredentials();
        }
        User user = found.get();

        if (user.isLocked(now)) {
            record(user.getId(), client, false);
            long minutes = Math.max(1, java.time.Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Too many failed attempts. Try again in " + minutes
                            + " minute(s) or reset your password.");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            if (attempts >= security.maxFailedLogins()) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(now.plus(security.lockDuration()));
                mail.sendAccountLocked(recipient(user), security.lockDuration().toMinutes());
            } else {
                user.setFailedLoginAttempts(attempts);
            }
            record(user.getId(), client, false);
            throw invalidCredentials();
        }

        if (!user.isEnabled()) {
            record(user.getId(), client, false);
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account has been disabled. Contact support.");
        }

        boolean firstEverLogin = !loginHistory.existsByUserIdAndSuccessTrue(user.getId());
        boolean knownDevice = client.userAgent() != null
                && loginHistory.existsByUserIdAndSuccessTrueAndUserAgent(user.getId(), client.userAgent());

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        record(user.getId(), client, true);

        if (!firstEverLogin && !knownDevice) {
            mail.sendNewLoginAlert(recipient(user), UserAgents.describe(client.userAgent()), client.ipAddress(), now);
        }
        return sessions.start(user, client);
    }

    Optional<User> findByIdentifier(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        if (PHONE.matcher(id).matches()) {
            return users.findByPhone(id);
        }
        return users.findByEmail(normalizeEmail(id));
    }

    private void record(Long userId, ClientInfo client, boolean success) {
        loginHistory.save(new LoginHistory(userId, client.ipAddress(), client.userAgent(), success));
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email/mobile number or password.");
    }

    // ------------------------------------------------------------------
    // Forgot / reset password
    // ------------------------------------------------------------------

    /** Same answer whether or not the account exists. */
    @Transactional
    public void forgotPassword(String identifier) {
        findByIdentifier(identifier)
                .filter(u -> !u.isDeleted() && u.isEnabled())
                .ifPresent(user -> {
                    Instant now = clock.instant();
                    verificationTokens.invalidateOpen(user.getId(), TokenType.PASSWORD_RESET, now);
                    String raw = Tokens.newToken();
                    verificationTokens.save(new VerificationToken(user, Tokens.sha256(raw),
                            TokenType.PASSWORD_RESET, now.plus(security.passwordResetTtl())));
                    mail.sendPasswordReset(recipient(user), raw);
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = clock.instant();
        VerificationToken token = verificationTokens
                .findByTokenHashAndType(Tokens.sha256(rawToken), TokenType.PASSWORD_RESET)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID",
                        "This reset link is invalid or has expired. Request a new one."));
        User user = token.getUser();
        token.markUsed(now);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        // The reset link proves control of the inbox, so it also verifies the email.
        user.setEmailVerified(true);
        refreshTokens.revokeAllForUser(user.getId(), RefreshToken.REASON_PASSWORD_CHANGED, now);
        mail.sendPasswordChanged(recipient(user));
    }

    /** A new staff member sets their first password from the invite email. */
    @Transactional
    public void acceptInvite(String rawToken, String newPassword) {
        Instant now = clock.instant();
        VerificationToken token = verificationTokens
                .findByTokenHashAndType(Tokens.sha256(rawToken), TokenType.STAFF_INVITE)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID",
                        "This invite link is invalid or has expired. Ask an admin to send a new one."));
        User user = token.getUser();
        if (user.isDeleted() || !user.isEnabled()) {
            throw ApiException.badRequest("TOKEN_INVALID", "This invite is no longer valid.");
        }
        token.markUsed(now);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }

    // ------------------------------------------------------------------

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static Recipient recipient(User user) {
        return new Recipient(user.getEmail(), user.getFullName());
    }
}
