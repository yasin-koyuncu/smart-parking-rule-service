package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reads of fines, their audit trail and the state of their disputes. The callers decide who may see what; nothing here changes state. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FineQueryService {

    private final FineRepository fines;
    private final FineActionRepository actions;
    private final DisputeRepository disputes;

    public Fine get(UUID fineId) {
        return fines.findById(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    /** Status of the latest dispute of the fine, null when it never had one. */
    public DisputeStatus disputeStatusOf(UUID fineId) {
        return disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fineId).map(d -> d.getStatus()).orElse(null);
    }

    /** Latest dispute status of every given fine that has one (one query); fines without a dispute are absent from the map. */
    public Map<UUID, DisputeStatus> disputeStatusesOf(Collection<Fine> someFines) {
        Map<UUID, DisputeStatus> statuses = new HashMap<>();
        if (someFines.isEmpty()) {
            return statuses;
        }
        disputes.findLatestStatuses(someFines.stream().map(Fine::getId).toList())
                .forEach(row -> statuses.put(row.getFineId(), DisputeStatus.parse(row.getStatus()).orElseThrow()));
        return statuses;
    }

    /** Whether a dispute is open, which holds the payment of the fine. */
    public boolean isDisputed(UUID fineId) {
        return disputes.existsByFineIdAndStatus(fineId, DisputeStatus.OPEN);
    }

    /** Audit trail of a fine; the caller passes the sort (newest first). */
    public Page<FineAction> history(UUID fineId, Pageable pageable) {
        return actions.findByFineId(fineId, pageable);
    }

    /** @param includeVoided false (the default of the API) leaves out voided fines */
    public Page<Fine> byPlate(String plate, boolean includeVoided, Pageable pageable) {
        return includeVoided ? fines.findByPlate(plate, pageable) : fines.findByPlateAndVoidedAtIsNull(plate, pageable);
    }

    /**
     * @param paid          {@code null} for all fines of the zone, otherwise only the paid or only the unpaid ones
     * @param includeVoided false (the default of the API) leaves out voided fines
     */
    public Page<Fine> byZone(String zoneId, Boolean paid, boolean includeVoided, Pageable pageable) {
        if (paid == null) {
            return includeVoided ? fines.findByZoneId(zoneId, pageable) : fines.findByZoneIdAndVoidedAtIsNull(zoneId, pageable);
        }
        return includeVoided
                ? fines.findByZoneIdAndPaid(zoneId, paid, pageable)
                : fines.findByZoneIdAndPaidAndVoidedAtIsNull(zoneId, paid, pageable);
    }
}
