package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads of fines and their audit trail. The callers decide who may see what; nothing here changes state. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FineQueryService {

    private final FineRepository fines;
    private final FineActionRepository actions;

    public Fine get(UUID fineId) {
        return fines.findById(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
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
