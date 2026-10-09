package com.parkview.ruleengine.security;

import java.util.Locale;
import java.util.Optional;

/** Roles carried in the JWT claim {@code app_metadata.role} (set by the Supabase access-token hook). */
public enum Role {
    ADMIN,
    OPERATOR,
    DRIVER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> parse(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.toString().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
