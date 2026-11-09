package com.bookmarked.common;

import com.bookmarked.auth.security.AuthUser;

/** Staff accounts run the store; they cannot shop with their staff login. */
public final class CustomerOnly {

    private CustomerOnly() {
    }

    public static void require(AuthUser caller) {
        if (caller == null) {
            throw ApiException.unauthorized("UNAUTHORIZED", "Please log in to continue.");
        }
        if (caller.isStaff()) {
            throw ApiException.forbidden("STAFF_CANNOT_SHOP",
                    "Staff accounts can't shop. Use a customer account for purchases.");
        }
    }
}
