package com.parkview.ruleengine.security;

import com.parkview.ruleengine.client.ZoneDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Object-level authorisation rules (the role check says "may use this endpoint",
 * the policy says "may touch this site / zone / plate / record").
 *
 * <ul>
 *   <li>admin: everything</li>
 *   <li>operator: only the sites in their {@code sites} claim (ADR 0009); a zone is reachable when its site is, and a record
 *       when its {@code site_id} is. A record without a site is for administrators only.</li>
 *   <li>driver: only the plates in their {@code plates} claim, only their own user id</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccessPolicy {

    private final ZoneDirectory zones;

    public void requireSite(AuthenticatedUser user, String siteId) {
        if (!canAccessSite(user, siteId)) {
            throw new AccessDeniedException("No access to site");
        }
    }

    public boolean canAccessSite(AuthenticatedUser user, String siteId) {
        if (user.isAdmin()) {
            return true;
        }
        return user.role() == Role.OPERATOR && siteId != null && user.sites().contains(siteId);
    }

    /** A zone-keyed endpoint: the caller must have the site of the zone (see {@link #siteOfZone}). */
    public void requireZone(AuthenticatedUser user, String zoneId) {
        if (!canAccessZone(user, zoneId)) {
            throw new AccessDeniedException("No access to zone");
        }
    }

    public boolean canAccessZone(AuthenticatedUser user, String zoneId) {
        if (user.isAdmin()) {
            return true;
        }
        return zoneId != null && canAccessSite(user, siteOfZone(zoneId));
    }

    /**
     * The site of a zone. A zone that is not known is taken to be its own site, which is true of every zone that existed
     * before sites did (ADR 0009: a site created for a zone has the zone's id); it has no records at another site either.
     */
    public String siteOfZone(String zoneId) {
        return zoneId == null ? null : zones.siteOf(zoneId).orElse(zoneId);
    }

    public void requirePlate(AuthenticatedUser user, String plate) {
        if (!canAccessPlate(user, plate)) {
            throw new AccessDeniedException("No access to plate");
        }
    }

    /**
     * The sites a list of records of a plate must be limited to: the sites of an operator (possibly none), nothing (empty) for
     * an administrator, who sees every site. A driver is not limited by site but by plate, see {@link #requirePlate}.
     * Looking at a plate gives an operator no view into the sites of other operators.
     */
    public Optional<List<String>> siteScopeOf(AuthenticatedUser user) {
        return user.role() == Role.OPERATOR ? Optional.of(List.copyOf(user.sites())) : Optional.empty();
    }

    /**
     * May this caller address the plate at all: staff always (the lists of a plate are then limited to their sites, see
     * {@link #siteScopeOf}), drivers only their own.
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
