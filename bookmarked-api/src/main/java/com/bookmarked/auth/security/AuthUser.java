package com.bookmarked.auth.security;

import com.bookmarked.user.AccountType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * The authenticated caller, rebuilt from the access token on every request
 * (no database hit). {@code sessionId} is the refresh-token row this access
 * token was issued with, used to mark the "current device" in session lists.
 */
public record AuthUser(Long id, String role, AccountType accountType, Long sessionId, List<String> permissions) {

    /** Granted to every staff account; guards the whole /api/admin/** area. */
    public static final String STAFF_AUTHORITY = "ACCOUNT_STAFF";

    public boolean isStaff() {
        return accountType == AccountType.STAFF;
    }

    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_" + role));
        if (isStaff()) {
            list.add(new SimpleGrantedAuthority(STAFF_AUTHORITY));
        }
        permissions.forEach(p -> list.add(new SimpleGrantedAuthority(p)));
        return list;
    }
}
