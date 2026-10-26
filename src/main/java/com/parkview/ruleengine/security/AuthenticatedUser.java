package com.parkview.ruleengine.security;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The caller, as described by the verified JWT.
 *
 * @param userId the {@code sub} claim
 * @param role   single effective role (admin &gt; operator &gt; driver)
 * @param plates normalised plates the user owns (drivers)
 * @param sites  site ids the user may operate (operators): the {@code sites} claim, else the {@code zones} claim read as site
 *               ids (ADR 0009: a site created for a zone has the zone's id)
 */
public record AuthenticatedUser(String userId, Role role, List<String> plates, List<String> sites) {

    /** @deprecated the scope is a list of sites now (ADR 0009); same list as {@link #sites()} */
    @Deprecated
    public List<String> zones() {
        return sites;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isOperatorOrAdmin() {
        return role == Role.ADMIN || role == Role.OPERATOR;
    }

    /** The token subject as a user id, or null when it is not a UUID (Supabase always issues one). */
    public UUID userUuid() {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Upper-case, no spaces or dashes — the form plates are stored and compared in. */
    public static String normalizePlate(String plate) {
        return plate == null ? null : plate.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }
}
