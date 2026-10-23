package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.service.fine.FineQueryService;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Which fines a caller may see through the evidence and dispute endpoints. A fine the caller may not see is reported as
 * unknown (404), so the answer never reveals that it exists; the role and permission checks stay on the endpoints.
 */
@Component
@RequiredArgsConstructor
class FineAccess {

    private final FineQueryService fines;
    private final AccessPolicy accessPolicy;

    /** A fine in a zone of the staff member (administrators: any zone). */
    Fine forStaff(UUID fineId, AuthenticatedUser user) {
        Fine fine = fines.get(fineId);
        if (!accessPolicy.canAccessZone(user, fine.getZoneId())) {
            throw new ResourceNotFoundException("Fine", fineId);
        }
        return fine;
    }

    /** Staff: a fine of their zones. Driver: a fine of a plate in their {@code plates} claim, voided or not (it may be the outcome of their dispute). */
    Fine forOwnerOrStaff(UUID fineId, AuthenticatedUser user) {
        if (user.isOperatorOrAdmin()) {
            return forStaff(fineId, user);
        }
        Fine fine = fines.get(fineId);
        if (!accessPolicy.canAccessPlate(user, fine.getPlate())) {
            throw new ResourceNotFoundException("Fine", fineId);
        }
        return fine;
    }
}
