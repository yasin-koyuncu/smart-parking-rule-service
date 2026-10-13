package com.parkview.ruleengine.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fine-grained permissions carried in the JWT claim {@code app_metadata.permissions} (string[], set by the Supabase
 * access-token hook). Each one becomes a granted authority named exactly like the permission, e.g. {@code zone:write}.
 * The catalogue is {@code contracts/permissions.json} in smart-parking-e2e; keep the tables below identical to its
 * {@code defaultsByRole}.
 */
public final class Permission {

    /** {@code resource:action}; anything else in the claim (e.g. {@code ROLE_ADMIN}) is ignored, never granted. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    private static final Set<String> OPERATOR_DEFAULTS = Set.of(
            "mapping:view", "spot:write", "session:read", "session:manage", "violation:read", "violation:resolve",
            "permit:manage", "lookup:vehicle", "file:upload", "file:view", "stream:operator", "camera:admin", "ai:use");

    private static final Set<String> ADMIN_DEFAULTS = union(OPERATOR_DEFAULTS, Set.of("zone:write", "access:read", "access:manage"));

    private static final Map<Role, Set<String>> DEFAULTS_BY_ROLE = Map.of(
            Role.ADMIN, ADMIN_DEFAULTS,
            Role.OPERATOR, OPERATOR_DEFAULTS,
            Role.DRIVER, Set.of());

    private Permission() {
    }

    /** The permissions a role has when nobody has restricted it (what tokens without the claim are treated as). */
    public static Set<String> defaultsFor(Role role) {
        return DEFAULTS_BY_ROLE.getOrDefault(role, Set.of());
    }

    /**
     * The permissions in the claim. Empty when the claim is ABSENT (a token minted before permissions existed);
     * a present claim, even an empty list or a malformed value, yields a set (possibly empty) and never triggers a fallback.
     */
    public static Optional<Set<String>> fromClaim(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Set<String> granted = new LinkedHashSet<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String s && NAME.matcher(s.trim()).matches()) {
                    granted.add(s.trim());
                }
            }
        }
        return Optional.of(granted);
    }

    /**
     * The effective permissions of a token: the claim when present, otherwise (only while
     * {@code parkview.security.legacy-role-fallback} is on) the defaults of the token's role.
     */
    public static Set<String> resolve(Object rawClaim, Optional<Role> role, boolean legacyRoleFallback) {
        return fromClaim(rawClaim).orElseGet(() ->
                legacyRoleFallback ? role.map(Permission::defaultsFor).orElse(Set.of()) : Set.of());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
