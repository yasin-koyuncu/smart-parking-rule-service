package com.parkview.ruleengine.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fine-grained permissions carried in the JWT claim {@code app_metadata.permissions} (string[], set by the Supabase
 * access-token hook). Each one becomes a granted authority named exactly like the permission, e.g. {@code zone:write}.
 * The catalogue is {@code contracts/permissions.json} in smart-parking-e2e (the block below is generated from it).
 */
public final class Permission {

    /** {@code resource:action}; anything else in the claim (e.g. {@code ROLE_ADMIN}) is ignored, never granted. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    // BEGIN GENERATED permissions (contracts/permissions.json in smart-parking-e2e; do not edit)
    /** Every permission of the catalogue (contracts/permissions.json). */
    public static final Set<String> ALL = Set.of(
            "zone:read", "zone:write", "spot:read", "spot:write", "session:read", "session:manage", "session:correct",
            "violation:read", "violation:resolve", "fine:read", "fine:manage", "permit:read", "permit:manage",
            "lookup:vehicle", "file:upload", "file:view", "stream:operator", "camera:read", "camera:admin", "ai:use",
            "access:read", "access:manage", "role:manage");
    // END GENERATED permissions

    private Permission() {
    }

    /**
     * The permissions in the claim. Empty when the claim is absent, malformed or empty: a token without the claim has no
     * permissions and nothing is derived from its role.
     */
    public static Set<String> fromClaim(Object raw) {
        Set<String> granted = new LinkedHashSet<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String s && NAME.matcher(s.trim()).matches()) {
                    granted.add(s.trim());
                }
            }
        }
        return granted;
    }
}
