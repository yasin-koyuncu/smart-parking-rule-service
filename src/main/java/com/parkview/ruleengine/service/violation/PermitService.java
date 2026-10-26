package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.request.CreatePermitRequest;
import com.parkview.ruleengine.repository.PlatePermitRepository;
import com.parkview.ruleengine.util.Plates;
import com.parkview.ruleengine.util.ZoneIds;
import com.parkview.ruleengine.web.BusinessRuleException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Permit data: the question the rule engine asks ("may this plate park here?") and operator CRUD. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermitService {

    private final PlatePermitRepository repository;
    private final Clock clock;

    /**
     * True when the plate holds a valid permit of every given type (case-insensitive) that covers the zone
     * (or all zones) at {@code at}. One query however many types are required.
     */
    @Transactional(readOnly = true)
    public boolean holdsAll(String normalizedPlate, String zoneId, Collection<String> permitTypes, Instant at) {
        Set<String> required = permitTypes.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return required.isEmpty() || repository.countHeldTypes(normalizedPlate, required, zoneId, at) == required.size();
    }

    @Transactional
    public PlatePermit grant(CreatePermitRequest request, String createdBy) {
        String plate = Plates.normalize(request.plate());
        if (plate == null) {
            throw BusinessRuleException.coded("permit.plateEmpty", "Plate must not be empty");
        }
        Instant validFrom = request.validFrom() != null ? request.validFrom() : clock.instant();
        if (request.validTo() != null && !request.validTo().isAfter(validFrom)) {
            throw BusinessRuleException.coded("permit.validToNotAfterValidFrom", "validTo must be after validFrom");
        }
        String zoneId = ZoneIds.normalize(request.zoneId());
        PlatePermit permit = repository.save(PlatePermit.grant(
                plate, request.permitType(), zoneId, validFrom, request.validTo(), createdBy));
        log.info("Permit {} granted ({} for plate {}, zone {})", permit.getId(), permit.getPermitType(),
                Plates.mask(plate), zoneId == null ? "all" : zoneId);
        return permit;
    }

    /**
     * @param plate        optional filter (normalised here)
     * @param zoneId       optional filter
     * @param visibleZones zones the caller may see besides global permits; null means no restriction (administrators)
     */
    @Transactional(readOnly = true)
    public Page<PlatePermit> search(String plate, String zoneId, Collection<String> visibleZones, Pageable pageable) {
        Specification<PlatePermit> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String normalized = Plates.normalize(plate);
            if (normalized != null) {
                predicates.add(cb.equal(root.get("plate"), normalized));
            }
            if (zoneId != null && !zoneId.isBlank()) {
                predicates.add(cb.equal(root.get("zoneId"), zoneId));
            }
            if (visibleZones != null) {
                Predicate global = cb.isNull(root.get("zoneId"));
                predicates.add(visibleZones.isEmpty() ? global : cb.or(global, root.get("zoneId").in(visibleZones)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public PlatePermit get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Permit", id));
    }

    @Transactional
    public void revoke(UUID id) {
        PlatePermit permit = get(id);
        repository.delete(permit);
        log.info("Permit {} revoked", id);
    }
}
