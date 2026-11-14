package com.parkview.ruleengine.security;

import java.util.Set;

/**
 * The permission catalogue of this service ({@code contracts/permissions.json} in smart-parking-e2e; the block below is generated
 * from it). Parsing the JWT claim and turning it into authorities is done by {@code parkview-platform}
 * ({@code PermissionClaims}, {@code ParkviewJwtAuthenticationConverter}).
 */
public final class Permission {

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
}
