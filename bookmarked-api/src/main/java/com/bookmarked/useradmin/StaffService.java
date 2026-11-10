package com.bookmarked.useradmin;

import com.bookmarked.audit.AuditService;
import com.bookmarked.auth.dto.AuthDtos;
import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.auth.security.SessionGuard;
import com.bookmarked.auth.token.RefreshToken;
import com.bookmarked.auth.token.RefreshTokenRepository;
import com.bookmarked.auth.token.TokenType;
import com.bookmarked.auth.token.VerificationToken;
import com.bookmarked.auth.token.VerificationTokenRepository;
import com.bookmarked.common.ApiException;
import com.bookmarked.common.Tokens;
import com.bookmarked.mail.MailService;
import com.bookmarked.user.AccountType;
import com.bookmarked.user.Role;
import com.bookmarked.user.RoleRepository;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Staff accounts, managed by holders of STAFF_MANAGE. There is no public staff
 * signup: an admin creates the account and the person sets their own password from
 * an emailed invite (the admin never knows it).
 * <p>
 * Guard rails: you can't change, block or sign out yourself here, and the last
 * active Super Admin can't be demoted or blocked, so the store can't be locked out.
 * Role changes and blocks end the person's sessions at once (permissions live in
 * the access token, so a stale token must not keep old rights).
 */
@Service
public class StaffService {

    public static final Set<String> STAFF_ROLES = Set.of(Role.SUPER_ADMIN, Role.INVENTORY_MANAGER, Role.ORDER_MANAGER,
            Role.DEMO_ADMIN);
    static final Duration INVITE_TTL = Duration.ofHours(48);

    public record CreateStaffRequest(
            @NotBlank @Pattern(regexp = AuthDtos.NAME_REGEX, message = "Name must be 2-60 letters") String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = AuthDtos.PHONE_REGEX, message = "Enter a valid 10-digit mobile number") String phone,
            @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record StaffView(Long id, String fullName, String email, String phone, String role, boolean enabled,
                            boolean invitePending, Instant lastLoginAt, Instant createdAt, boolean you) {
    }

    private final UserRepository users;
    private final RoleRepository roles;
    private final VerificationTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final AuditService audit;
    private final SessionGuard sessionGuard;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StaffService(UserRepository users, RoleRepository roles, VerificationTokenRepository tokens,
                        RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, MailService mail,
                        AuditService audit, SessionGuard sessionGuard, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.audit = audit;
        this.sessionGuard = sessionGuard;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StaffView> list(AuthUser caller) {
        Instant now = clock.instant();
        return jdbc.query("""
                select u.id, u.full_name, u.email, u.phone, r.name, u.enabled, u.last_login_at, u.created_at,
                       exists (select 1 from verification_tokens t where t.user_id = u.id and t.type = 'STAFF_INVITE'
                               and t.used_at is null and t.expires_at > ?)
                       and not exists (select 1 from verification_tokens t where t.user_id = u.id and t.type = 'STAFF_INVITE'
                               and t.used_at is not null)
                from users u join roles r on r.id = u.role_id
                where u.account_type = 'STAFF' and u.deleted_at is null
                order by u.enabled desc, r.name, u.full_name
                """, (rs, i) -> new StaffView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBoolean(6), rs.getBoolean(9),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
                rs.getLong(1) == caller.id()), java.sql.Timestamp.from(now));
    }

    @Transactional
    public StaffView create(AuthUser caller, CreateStaffRequest req, String ip) {
        String role = staffRole(req.role());
        String email = req.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        if (users.existsByPhone(req.phone().strip())) {
            throw ApiException.conflict("PHONE_TAKEN", "An account with this mobile number already exists.");
        }
        User u = new User();
        u.setFullName(req.fullName().strip().replaceAll("\\s+", " "));
        u.setEmail(email);
        u.setPhone(req.phone().strip());
        // unusable until the invite is accepted: nobody knows this password
        u.setPasswordHash(passwordEncoder.encode(Tokens.newToken()));
        u.setAccountType(AccountType.STAFF);
        u.setRole(roles.findByName(role).orElseThrow());
        try {
            users.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email or mobile number already exists.");
        }
        sendInvite(u);
        audit.record(caller.id(), "Staff.create", "STAFF", u.getId().toString(), null,
                Map.of("name", u.getFullName(), "email", email, "role", role), ip);
        return view(caller, u.getId());
    }

    @Transactional
    public StaffView resendInvite(AuthUser caller, long id, String ip) {
        User u = staff(id);
        Integer used = jdbc.queryForObject(
                "select count(*) from verification_tokens where user_id = ? and type = 'STAFF_INVITE' and used_at is not null",
                Integer.class, id);
        if (used != null && used > 0) {
            throw ApiException.conflict("INVITE_ACCEPTED", "This person has already set their password.");
        }
        sendInvite(u);
        audit.record(caller.id(), "Staff.resendInvite", "STAFF", String.valueOf(id), null, null, ip);
        return view(caller, id);
    }

    @Transactional
    public StaffView changeRole(AuthUser caller, long id, String newRole, String ip) {
        notYourself(caller, id);
        String role = staffRole(newRole);
        User u = staff(id);
        String old = u.getRole().getName();
        if (old.equals(role)) {
            return view(caller, id);
        }
        if (Role.SUPER_ADMIN.equals(old)) {
            keepOneSuperAdmin(id);
        }
        u.setRole(roles.findByName(role).orElseThrow());
        endSessions(u, RefreshToken.REASON_ADMIN);
        audit.record(caller.id(), "Staff.changeRole", "STAFF", String.valueOf(id), Map.of("role", old),
                Map.of("role", role), ip);
        return view(caller, id);
    }

    @Transactional
    public StaffView setEnabled(AuthUser caller, long id, boolean enabled, String ip) {
        notYourself(caller, id);
        User u = staff(id);
        if (u.isEnabled() == enabled) {
            return view(caller, id);
        }
        if (!enabled && Role.SUPER_ADMIN.equals(u.getRole().getName())) {
            keepOneSuperAdmin(id);
        }
        u.setEnabled(enabled);
        if (!enabled) {
            endSessions(u, RefreshToken.REASON_ADMIN);
        }
        audit.record(caller.id(), enabled ? "Staff.unblock" : "Staff.block", "STAFF", String.valueOf(id),
                Map.of("enabled", !enabled), Map.of("enabled", enabled), ip);
        return view(caller, id);
    }

    @Transactional
    public StaffView signOut(AuthUser caller, long id, String ip) {
        notYourself(caller, id);
        User u = staff(id);
        int ended = endSessions(u, RefreshToken.REASON_ADMIN);
        audit.record(caller.id(), "Staff.signOut", "STAFF", String.valueOf(id), null, Map.of("sessionsEnded", ended), ip);
        return view(caller, id);
    }

    // ------------------------------------------------------------------

    private void sendInvite(User u) {
        Instant now = clock.instant();
        tokens.invalidateOpen(u.getId(), TokenType.STAFF_INVITE, now);
        String raw = Tokens.newToken();
        tokens.save(new VerificationToken(u, Tokens.sha256(raw), TokenType.STAFF_INVITE, now.plus(INVITE_TTL)));
        mail.sendStaffInvite(new MailService.Recipient(u.getEmail(), u.getFullName()), raw,
                roleLabel(u.getRole().getName()), INVITE_TTL.toHours());
    }

    private int endSessions(User u, String reason) {
        int n = refreshTokens.revokeAllForUser(u.getId(), reason, clock.instant());
        sessionGuard.forgetAll();
        return n;
    }

    private void keepOneSuperAdmin(long exceptId) {
        Integer others = jdbc.queryForObject("""
                select count(*) from users u join roles r on r.id = u.role_id
                where r.name = 'SUPER_ADMIN' and u.enabled = true and u.deleted_at is null and u.id <> ?
                """, Integer.class, exceptId);
        if (others == null || others == 0) {
            throw ApiException.conflict("LAST_SUPER_ADMIN", "This is the only active Super Admin. Make someone else Super Admin first.");
        }
    }

    private static void notYourself(AuthUser caller, long id) {
        if (caller.id() == id) {
            throw ApiException.conflict("NOT_YOURSELF", "You can't do this to your own account.");
        }
    }

    private User staff(long id) {
        return users.findById(id)
                .filter(u -> u.getAccountType() == AccountType.STAFF && !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("STAFF_NOT_FOUND", "Staff member not found."));
    }

    private static String staffRole(String role) {
        String r = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(r)) {
            throw ApiException.badRequest("INVALID_ROLE", "Role must be SUPER_ADMIN, INVENTORY_MANAGER, ORDER_MANAGER or DEMO_ADMIN.");
        }
        return r;
    }

    static String roleLabel(String role) {
        return switch (role) {
            case Role.SUPER_ADMIN -> "Super Admin";
            case Role.INVENTORY_MANAGER -> "Inventory Manager";
            case Role.ORDER_MANAGER -> "Order Manager";
            case Role.DEMO_ADMIN -> "Demo (read-only)";
            default -> role;
        };
    }

    private StaffView view(AuthUser caller, long id) {
        users.flush();   // the JDBC query below must see changes made through JPA
        return list(caller).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }
}
