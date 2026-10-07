package com.parkview.ruleengine.service;

import com.parkview.ruleengine.model.FineIssuedEvent;
import com.parkview.ruleengine.model.ViolationType;
import com.parkview.ruleengine.model.entity.Fine;
import com.parkview.ruleengine.model.entity.Violation;
import com.parkview.ruleengine.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Skapar den faktiska Fine-raden när en violations grace period löper ut.
 * Utan detta publicerar ViolationService bara ett "violation.expired"-event
 * som notification-service tolkar som "bot utfärdad" — men det fanns
 * ingen bot att ta betalt för (payment-service har inget att slå upp).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineService {

    private final FineRepository       fineRepository;
    private final ContactLookupService contactLookup;

    @Value("${parkview.rule-engine.fine-amount-no-parking-sek:900}")
    private int fineNoParking;

    @Value("${parkview.rule-engine.fine-amount-overstay-sek:450}")
    private int fineOverstay;

    @Value("${parkview.rule-engine.fine-amount-wrong-permit-sek:700}")
    private int fineWrongPermit;

    @Value("${parkview.rule-engine.fine-amount-boundary-exceeded-sek:900}")
    private int fineBoundaryExceeded;

    @Transactional
    public FineIssuedEvent issueFine(Violation violation) {
        int amount = amountFor(violation.getViolationType());
        UUID owner = contactLookup.ownerOfPlate(violation.getPlate());

        Fine fine = Fine.builder()
                .plate(violation.getPlate())
                .zoneId(violation.getZoneId())
                .reason(violation.getViolationType().name().toLowerCase())
                .amountSek(BigDecimal.valueOf(amount))
                .issuedAt(Instant.now())
                .paid(false)
                .userId(owner)
                .imagePath(violation.getImagePath())
                .build();

        fine = fineRepository.save(fine);

        var event = new FineIssuedEvent();
        event.setFineId(fine.getId());
        event.setViolationId(violation.getId());
        event.setPlate(violation.getPlate());
        event.setUserId(owner != null ? owner.toString() : null);
        event.setZoneId(violation.getZoneId());
        event.setZoneAddress(contactLookup.zoneAddress(violation.getZoneId()));
        event.setAmountSek(amount);
        event.setTimestamp(Instant.now().toEpochMilli());

        log.info("Bot utfärdad: {} — {} — {} kr ({})",
                fine.getId(), violation.getPlate(), amount, fine.getReason());
        return event;
    }

    private int amountFor(ViolationType type) {
        return switch (type) {
            case NO_PARKING        -> fineNoParking;
            case OVERSTAY          -> fineOverstay;
            case WRONG_PERMIT      -> fineWrongPermit;
            case BOUNDARY_EXCEEDED -> fineBoundaryExceeded;
        };
    }
}
