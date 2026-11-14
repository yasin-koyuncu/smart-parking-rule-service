package com.parkview.ruleengine.security;

import com.parkview.platform.security.PlateDirectory;
import com.parkview.platform.security.Role;
import com.parkview.platform.security.servlet.AccessPolicy;
import com.parkview.platform.security.servlet.AuthenticatedUser;
import com.parkview.ruleengine.client.ZoneDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The rule-service rules on top of the platform {@link AccessPolicy} (admin everything, operator the sites of
 * {@link com.parkview.platform.security.OperatorSites}, driver the plates of the {@link PlateDirectory}): a zone is reachable when
 * its site is, and a plate list of an operator is limited to their sites. Declared as the {@code AccessPolicy} bean, so the
 * platform default backs off.
 */
@Component
@RequiredArgsConstructor
public class RuleAccessPolicy extends AccessPolicy {

    private final ZoneDirectory zones;
    private final PlateDirectory plateDirectory;

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

    /** The site of a zone; null when the zone is not known (then no operator has access to it). */
    public String siteOfZone(String zoneId) {
        return zoneId == null ? null : zones.siteOf(zoneId).orElse(null);
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
        return canAccessPlate(user, plate, () -> plateDirectory.platesOf(user.userId()));
    }

    /** Whether the caller holds the permission (a granted authority named exactly like it, e.g. {@code violation:read}). */
    public boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
