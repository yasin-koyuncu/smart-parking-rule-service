package com.parkview.ruleengine.security;

import java.util.List;
import java.util.Locale;

/**
 * The caller, as described by the verified JWT.
 *
 * @param userId the {@code sub} claim
 * @param role   single effective role (admin &gt; operator &gt; driver)
 * @param plates normalised plates the user owns (drivers)
 * @param zones  zone ids the user may operate (operators)
 */
public record AuthenticatedUser(String userId, Role role, List<String> plates, List<String> zones) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isOperatorOrAdmin() {
        return role == Role.ADMIN || role == Role.OPERATOR;
    }

    /** Upper-case, no spaces or dashes — the form plates are stored and compared in. */
    public static String normalizePlate(String plate) {
        return plate == null ? null : plate.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }
}
