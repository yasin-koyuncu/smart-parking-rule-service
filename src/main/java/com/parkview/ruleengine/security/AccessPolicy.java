package com.parkview.ruleengine.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Object-level authorisation rules (the role check says "may use this endpoint",
 * the policy says "may touch this zone / plate / record").
 *
 * <ul>
 *   <li>admin: everything</li>
 *   <li>operator: only the zones in their {@code zones} claim</li>
 *   <li>driver: only the plates in their {@code plates} claim, only their own user id</li>
 * </ul>
 */
@Component
public class AccessPolicy {

    public void requireZone(AuthenticatedUser user, String zoneId) {
        if (!canAccessZone(user, zoneId)) {
            throw new AccessDeniedException("No access to zone");
        }
    }

    public boolean canAccessZone(AuthenticatedUser user, String zoneId) {
        if (user.isAdmin()) {
            return true;
        }
        return user.role() == Role.OPERATOR && zoneId != null && user.zones().contains(zoneId);
    }

    public void requirePlate(AuthenticatedUser user, String plate) {
        if (!canAccessPlate(user, plate)) {
            throw new AccessDeniedException("No access to plate");
        }
    }

    /** Operators and admins may look up any plate (enforcement work); drivers only their own. */
    public boolean canAccessPlate(AuthenticatedUser user, String plate) {
        if (user.isOperatorOrAdmin()) {
            return true;
        }
        String normalized = AuthenticatedUser.normalizePlate(plate);
        return normalized != null && user.plates().contains(normalized);
    }

    /** The record's owner (drivers) or any operator/admin. */
    public void requireOwnerOrStaff(AuthenticatedUser user, String ownerUserId) {
        if (user.isOperatorOrAdmin()) {
            return;
        }
        if (ownerUserId == null || !Objects.equals(user.userId(), ownerUserId)) {
            throw new AccessDeniedException("Not the owner");
        }
    }
}
