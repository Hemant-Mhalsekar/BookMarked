package com.bookmarked.account;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.auth.security.SessionGuard;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.auth.token.TokenType;
import com.bookmarked.auth.token.VerificationToken;
import com.bookmarked.auth.token.VerificationTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.CustomerOnly;
import com.bookmarked.common.Tokens;
import com.bookmarked.mail.MailService;
import com.bookmarked.mail.MailService.Recipient;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * The two account changes that touch identity: a new email address and deleting
 * the account.
 * <p>
 * <b>Email change.</b> Needs the password. The new address must click a link
 * before anything changes, and the old address is told both when the change is
 * asked for and when it happens, so a hijacked session can't silently move the
 * account. Every session ends afterwards, since the email is the login.
 * <p>
 * <b>Deletion.</b> Needs the password and the word DELETE, and waits until no
 * order, return or refund is still in progress. Orders and invoices must be kept
 * for tax records, so the user row stays but everything that identifies the
 * person is replaced: name "Former customer", an unusable email and phone, and a
 * random password. Addresses, Your Stack, Bookmarks, recently viewed, reading
 * progress and notifications are deleted; reviews stay, shown as "Former customer".
 */
@Service
public class AccountLifecycleService {

    static final Duration EMAIL_CHANGE_TTL = Duration.ofHours(24);
    public static final String FORMER_CUSTOMER = "Former customer";

    public record PendingEmail(String newEmail, Instant expiresAt) {
    }

    private final UserRepository users;
    private final VerificationTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final SessionGuard sessionGuard;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountLifecycleService(UserRepository users, VerificationTokenRepository tokens,
                                   RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                                   MailService mail, SessionGuard sessionGuard, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.sessionGuard = sessionGuard;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Email change
    // ------------------------------------------------------------------

    @Transactional
    public PendingEmail requestEmailChange(AuthUser caller, String newEmail, String password) {
        User user = load(caller);
        checkPassword(user, password);
        String email = newEmail.strip().toLowerCase(Locale.ROOT);
        if (email.equals(user.getEmail())) {
            throw ApiException.badRequest("SAME_EMAIL", "That's already your email.");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "That email is already used by another account.");
        }
        Instant now = clock.instant();
        tokens.invalidateOpen(user.getId(), TokenType.EMAIL_CHANGE, now);
        String raw = Tokens.newToken();
        Instant expires = now.plus(EMAIL_CHANGE_TTL);
        tokens.save(new VerificationToken(user, Tokens.sha256(raw), expires, email));
        mail.sendEmailChangeConfirm(new Recipient(email, user.getFullName()), raw, EMAIL_CHANGE_TTL.toHours());
        mail.sendEmailChangeAlert(new Recipient(user.getEmail(), user.getFullName()), mask(email), false);
        return new PendingEmail(email, expires);
    }

    /** The change waiting for confirmation, or null. */
    @Transactional(readOnly = true)
    public PendingEmail pendingEmail(AuthUser caller) {
        return jdbc.query("""
                select new_email, expires_at from verification_tokens
                where user_id = ? and type = 'EMAIL_CHANGE' and used_at is null and expires_at > ?
                order by created_at desc limit 1
                """, rs -> rs.next() ? new PendingEmail(rs.getString(1), rs.getTimestamp(2).toInstant()) : null,
                caller.id(), java.sql.Timestamp.from(clock.instant()));
    }

    @Transactional
    public void cancelEmailChange(AuthUser caller) {
        tokens.invalidateOpen(caller.id(), TokenType.EMAIL_CHANGE, clock.instant());
    }

    /** From the link in the email; works without being logged in (it may be opened on another device). */
    @Transactional
    public void confirmEmailChange(String rawToken) {
        Instant now = clock.instant();
        VerificationToken token = tokens.findByTokenHashAndType(Tokens.sha256(rawToken), TokenType.EMAIL_CHANGE)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID",
                        "This link is invalid or has expired. Ask for the change again from your profile."));
        User user = token.getUser();
        if (user.isDeleted() || !user.isEnabled()) {
            throw ApiException.badRequest("TOKEN_INVALID", "This link is no longer valid.");
        }
        String newEmail = token.getNewEmail();
        if (users.existsByEmail(newEmail)) {
            throw ApiException.conflict("EMAIL_TAKEN", "That email has since been used by another account.");
        }
        String oldEmail = user.getEmail();
        token.markUsed(now);
        user.setEmail(newEmail);
        user.setEmailVerified(true);
        try {
            users.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "That email has since been used by another account.");
        }
        refreshTokens.revokeAllForUser(user.getId(), RefreshToken.REASON_EMAIL_CHANGED, now);
        sessionGuard.forgetAll();
        mail.sendEmailChangeAlert(new Recipient(oldEmail, user.getFullName()), mask(newEmail), true);
    }

    // ------------------------------------------------------------------
    // Deletion
    // ------------------------------------------------------------------

    @Transactional
    public void deleteAccount(AuthUser caller, String password, String confirmation) {
        CustomerOnly.require(caller);
        if (!"DELETE".equals(confirmation == null ? null : confirmation.strip())) {
            throw ApiException.badRequest("CONFIRMATION_REQUIRED", "Type DELETE to confirm.");
        }
        // lock the user row: a checkout can't slip in between the check and the anonymising
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, caller.id());
        User user = load(caller);
        checkPassword(user, password);
        String busy = inProgress(user.getId());
        if (busy != null) {
            throw ApiException.conflict("ACCOUNT_BUSY", busy);
        }

        Recipient goodbye = new Recipient(user.getEmail(), user.getFullName());
        long id = user.getId();
        Instant now = clock.instant();
        user.setFullName(FORMER_CUSTOMER);
        user.setEmail("deleted-" + id + "@deleted.invalid");
        user.setPhone(String.format("0%09d", id));
        user.setPasswordHash(passwordEncoder.encode(Tokens.newToken()));
        user.setEmailVerified(false);
        user.setEnabled(false);
        user.setDeletedAt(now);
        users.flush();

        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id = ?)", id);
        jdbc.update("delete from carts where user_id = ?", id);
        for (String table : new String[]{"addresses", "wishlist_items", "recently_viewed", "reading_progress",
                "notifications"}) {
            jdbc.update("delete from " + table + " where user_id = ?", id);
        }
        tokens.invalidateOpen(id, TokenType.EMAIL_VERIFY, now);
        tokens.invalidateOpen(id, TokenType.PASSWORD_RESET, now);
        tokens.invalidateOpen(id, TokenType.EMAIL_CHANGE, now);
        refreshTokens.revokeAllForUser(id, RefreshToken.REASON_ACCOUNT_DELETED, now);
        sessionGuard.forgetAll();
        mail.sendAccountDeleted(goodbye);
    }

    /** Why the account can't be deleted yet, or null if it can. */
    private String inProgress(long userId) {
        Integer orders = jdbc.queryForObject("""
                select count(*) from orders o where o.user_id = ? and (o.status = 'PENDING_PAYMENT'
                  or exists (select 1 from order_items oi where oi.order_id = o.id
                             and oi.fulfillment_status in ('PLACED', 'PROCESSING', 'SHIPPED', 'RETURN_REQUESTED')))
                """, Integer.class, userId);
        if (orders != null && orders > 0) {
            return "You have an order on its way or awaiting payment. You can delete your account once it's delivered or cancelled.";
        }
        Integer returns = jdbc.queryForObject("""
                select count(*) from returns r join order_items oi on oi.id = r.order_item_id
                join orders o on o.id = oi.order_id
                where o.user_id = ? and r.status in ('REQUESTED', 'APPROVED', 'PICKED_UP', 'RECEIVED')
                """, Integer.class, userId);
        Integer refunds = jdbc.queryForObject("""
                select count(*) from refunds f join orders o on o.id = f.order_id
                where o.user_id = ? and f.status in ('PENDING', 'RETRYING', 'FAILED')
                """, Integer.class, userId);
        if ((returns != null && returns > 0) || (refunds != null && refunds > 0)) {
            return "You have a return or refund still in progress. You can delete your account once it's finished.";
        }
        return null;
    }

    // ------------------------------------------------------------------

    private void checkPassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your password is incorrect.");
        }
    }

    private User load(AuthUser caller) {
        return users.findById(caller.id())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Please log in to continue."));
    }

    /** "ravi.kumar@gmail.com" → "r********@gmail.com", for emails to the old address. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "*".repeat(Math.min(at - 1, 8)) + email.substring(at);
    }
}
