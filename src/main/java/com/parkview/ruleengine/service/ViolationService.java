package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RabbitMQConfig;
import com.parkview.ruleengine.model.*;
import com.parkview.ruleengine.model.entity.Violation;
import com.parkview.ruleengine.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    private final ViolationRepository  violationRepository;
    private final RabbitTemplate       rabbitTemplate;
    private final FineService          fineService;
    private final ContactLookupService contactLookup;

    @Value("${parkview.rule-engine.default-boundary-grace-minutes:10}")
    private int defaultBoundaryGrace;

    @Value("${parkview.rule-engine.default-permit-grace-minutes:30}")
    private int defaultPermitGrace;

    /**
     * Skapar en ny violation om ingen aktiv av samma typ redan finns.
     */
    @Transactional
    public void createIfNew(BoundaryCheckResult result, ParkingSpotDto spot) {
        if (result.getViolation() == null) return;

        // Undvik dubbletter — rapportera bara en gång per fordon+typ
        var existing = violationRepository
                .findByPlateAndViolationTypeAndResolvedAtIsNullAndFineIssuedAtIsNull(
                        result.getPlate(), result.getViolation()
                );
        if (existing.isPresent()) return;

        int graceMinutes = switch (result.getViolation()) {
            case WRONG_PERMIT      -> spot.getPermitGraceMin()  > 0 ? spot.getPermitGraceMin()   : defaultPermitGrace;
            case BOUNDARY_EXCEEDED -> spot.getBoundaryGraceMin() > 0 ? spot.getBoundaryGraceMin() : defaultBoundaryGrace;
            default                -> defaultBoundaryGrace;
        };

        var violation = Violation.builder()
                .spotId(spot.getId())
                .zoneId(result.getZoneId())
                .plate(result.getPlate())
                .violationType(result.getViolation())
                .detectedAt(Instant.now())
                .graceUntil(Instant.now().plus(graceMinutes, ChronoUnit.MINUTES))
                .build();

        violationRepository.save(violation);

        // Publicera event för notifieringstjänst
        UUID owner = contactLookup.ownerOfPlate(violation.getPlate());

        var event = new ViolationEvent();
        event.setViolationId(violation.getId());
        event.setPlate(violation.getPlate());
        event.setZoneId(violation.getZoneId());
        event.setZoneAddress(contactLookup.zoneAddress(violation.getZoneId()));
        event.setUserId(owner != null ? owner.toString() : null);
        event.setSpotId(spot.getId());
        event.setSpotNumber(spot.getSpotNumber());
        event.setViolationType(violation.getViolationType());
        event.setGraceMinutes(graceMinutes);
        event.setTimestamp(Instant.now().toEpochMilli());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.VIOLATION_EXCHANGE,
                RabbitMQConfig.VIOLATION_CREATED_KEY,
                event
        );

        log.warn("Violation skapad: {} — {} — grace {}min", violation.getPlate(), violation.getViolationType(), graceMinutes);
    }

    /**
     * Markerar en violation som löst.
     */
    @Transactional
    public void resolve(UUID violationId) {
        violationRepository.findById(violationId).ifPresent(v -> {
            v.setResolvedAt(Instant.now());
            violationRepository.save(v);
            log.info("Violation löst: {}", violationId);
        });
    }

    /**
     * Hämtar aktiva violations för en zon.
     */
    public List<Violation> getActiveByZone(String zoneId) {
        return violationRepository.findActiveByZone(zoneId);
    }

    /**
     * Hämtar alla violations för en registreringsskylt.
     */
    public List<Violation> getByPlate(String plate) {
        return violationRepository.findByPlateOrderByDetectedAtDesc(plate);
    }

    /**
     * Körs varje minut — utfärdar en bot (Fine) för varje violation vars
     * grace period har löpt ut, och publicerar den till notification-service.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void processExpired() {
        var expired = violationRepository.findExpired(Instant.now());
        if (expired.isEmpty()) return;

        log.info("Bearbetar {} utgångna violations — utfärdar böter", expired.size());
        for (var v : expired) {
            var fineEvent = fineService.issueFine(v);

            v.setFineIssuedAt(Instant.now());
            v.setFineId(fineEvent.getFineId());
            violationRepository.save(v);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.VIOLATION_EXCHANGE,
                    "violation.expired",
                    fineEvent
            );
        }
    }
}