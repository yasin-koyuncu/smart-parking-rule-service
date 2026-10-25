package com.parkview.ruleengine.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /**
     * The zones a list of records of a plate must be limited to: the zones of an operator (possibly none), nothing (empty) for
     * an administrator, who sees every zone. A driver is not limited by zone but by plate, see {@link #requirePlate}.
     * Looking at a plate gives an operator no view into the zones of other operators.
     */
    public Optional<List<String>> zoneScopeOf(AuthenticatedUser user) {
        return user.role() == Role.OPERATOR ? Optional.of(List.copyOf(user.zones())) : Optional.empty();
    }

    /**
     * May this caller address the plate at all: staff always (the lists of a plate are then limited to their zones, see
     * {@link #zoneScopeOf}), drivers only their own.
     */
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
