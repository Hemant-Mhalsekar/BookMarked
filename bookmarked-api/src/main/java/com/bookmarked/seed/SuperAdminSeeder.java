package com.bookmarked.seed;

import com.bookmarked.config.AppProperties;
import com.bookmarked.user.AccountType;
import com.bookmarked.user.Role;
import com.bookmarked.user.RoleRepository;
import com.bookmarked.user.User;
import com.bookmarked.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Creates the first Super Admin from environment variables on startup.
 * There is no public admin signup; every other staff member is invited by this account.
 */
@Component
public class SuperAdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSeeder.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Seed seed;

    public SuperAdminSeeder(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                            AppProperties props) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.seed = props.seed();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!StringUtils.hasText(seed.superAdminEmail()) || !StringUtils.hasText(seed.superAdminPassword())) {
            log.info("SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD not set; skipping super admin seed");
            return;
        }
        if (users.existsByRole_Name(Role.SUPER_ADMIN)) {
            return;
        }
        String email = seed.superAdminEmail().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            log.warn("Cannot seed super admin: {} is already used by another account", email);
            return;
        }
        String phone = StringUtils.hasText(seed.superAdminPhone()) ? seed.superAdminPhone().trim() : "9000000001";

        User admin = new User();
        admin.setFullName(seed.superAdminName());
        admin.setEmail(email);
        admin.setPhone(phone);
        admin.setPasswordHash(passwordEncoder.encode(seed.superAdminPassword()));
        admin.setAccountType(AccountType.STAFF);
        admin.setRole(roles.findByName(Role.SUPER_ADMIN).orElseThrow());
        admin.setEmailVerified(true);
        users.save(admin);
        log.info("Seeded super admin {}", email);
    }
}
