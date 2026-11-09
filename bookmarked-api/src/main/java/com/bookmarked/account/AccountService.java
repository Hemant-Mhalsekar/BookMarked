package com.bookmarked.account;

import com.bookmarked.account.AccountDtos.LoginHistoryDto;
import com.bookmarked.account.AccountDtos.SessionDto;
import com.bookmarked.auth.dto.AuthDtos.UserDto;
import com.bookmarked.auth.history.LoginHistoryRepository;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.UserAgents;
import com.bookmarked.mail.MailService;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything under /api/account is scoped to the caller from the JWT;
 * no user id is ever read from the URL.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final LoginHistoryRepository loginHistory;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final Clock clock;

    public AccountService(UserRepository users, RefreshTokenRepository refreshTokens,
                          LoginHistoryRepository loginHistory, PasswordEncoder passwordEncoder,
                          MailService mail, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.loginHistory = loginHistory;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserDto me(AuthUser caller) {
        return UserDto.from(load(caller));
    }

    /** Changes the password and signs out every other device (this one stays logged in). */
    @Transactional
    public void changePassword(AuthUser caller, String currentPassword, String newPassword) {
        User user = load(caller);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your current password is incorrect.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("SAME_PASSWORD", "Choose a password different from your current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        Instant now = clock.instant();
        if (caller.sessionId() != null) {
            refreshTokens.revokeAllForUserExcept(user.getId(), caller.sessionId(),
                    RefreshToken.REASON_PASSWORD_CHANGED, now);
        } else {
            refreshTokens.revokeAllForUser(user.getId(), RefreshToken.REASON_PASSWORD_CHANGED, now);
        }
        mail.sendPasswordChanged(new MailService.Recipient(user.getEmail(), user.getFullName()));
    }

    @Transactional(readOnly = true)
    public List<SessionDto> sessions(AuthUser caller) {
        return refreshTokens.findActiveByUser(caller.id(), clock.instant()).stream()
                .map(t -> new SessionDto(t.getId(), UserAgents.describe(t.getDeviceInfo()), t.getIpAddress(),
                        t.getCreatedAt(), t.getLastUsedAt(), t.getExpiresAt(),
                        Objects.equals(t.getId(), caller.sessionId())))
                .toList();
    }

    @Transactional
    public void revokeSession(AuthUser caller, Long sessionId) {
        RefreshToken token = refreshTokens.findById(sessionId)
                .filter(t -> t.getUser().getId().equals(caller.id()))
                // 404 rather than 403 so ids of other users' sessions reveal nothing
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "Session not found."));
        token.revoke(RefreshToken.REASON_REVOKED_BY_USER, clock.instant());
    }

    /** "Log out all other devices". Returns how many sessions were ended. */
    @Transactional
    public int revokeOtherSessions(AuthUser caller) {
        Instant now = clock.instant();
        if (caller.sessionId() == null) {
            return refreshTokens.revokeAllForUser(caller.id(), RefreshToken.REASON_REVOKED_BY_USER, now);
        }
        return refreshTokens.revokeAllForUserExcept(caller.id(), caller.sessionId(),
                RefreshToken.REASON_REVOKED_BY_USER, now);
    }

    @Transactional(readOnly = true)
    public List<LoginHistoryDto> loginHistory(AuthUser caller) {
        return loginHistory.findTop10ByUserIdOrderByCreatedAtDesc(caller.id()).stream()
                .map(h -> new LoginHistoryDto(h.getId(), UserAgents.describe(h.getUserAgent()), h.getIpAddress(),
                        h.isSuccess(), h.getCreatedAt()))
                .toList();
    }

    /** Name only; email and phone have their own, stricter flows. */
    @Transactional
    public UserDto updateName(AuthUser caller, String fullName) {
        User user = load(caller);
        user.setFullName(fullName.strip().replaceAll("\\s+", " "));
        return UserDto.from(user);
    }

    /**
     * A new mobile number needs the current password (so a stolen session can't
     * quietly change how the account is identified), and must not belong to anyone else.
     */
    @Transactional
    public UserDto changePhone(AuthUser caller, String phone, String password) {
        User user = load(caller);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your password is incorrect.");
        }
        String clean = phone.strip();
        if (clean.equals(user.getPhone())) {
            return UserDto.from(user);
        }
        if (users.existsByPhone(clean)) {
            throw ApiException.conflict("PHONE_TAKEN", "That mobile number is already used by another account.");
        }
        user.setPhone(clean);
        try {
            users.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("PHONE_TAKEN", "That mobile number is already used by another account.");
        }
        return UserDto.from(user);
    }

    private User load(AuthUser caller) {
        return users.findById(caller.id())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Please log in to continue."));
    }
}
