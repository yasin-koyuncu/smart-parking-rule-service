package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.client.DetectionDirectory;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineEvidenceRepository;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.util.ReasonTexts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Freezes and reads the evidence of fines. {@link #record} runs inside the transaction that issues the fine, so a fine never
 * exists without its evidence and a rolled-back fine leaves none. Nothing here ever updates or deletes evidence.
 */
@Service
@RequiredArgsConstructor
public class FineEvidenceService {

    /** Snapshots are looked up from this long before the violation was opened (the first sighting precedes the observation event). */
    static final Duration LOOKBACK = Duration.ofMinutes(5);
    /** The newest detections of the plate that count as evidence; keeps the row and the review small. */
    static final int MAX_DETECTIONS = 10;

    private final FineEvidenceRepository evidence;
    private final DetectionDirectory detections;
    private final ZoneDirectory zones;
    private final SiteDirectory sites;
    private final SpotCacheService spots;
    private final DisputeRepository disputes;

    /** Writes the evidence of a fine that is being issued for {@code violation}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FineEvidence record(Fine fine, Violation violation) {
        List<DetectionDirectory.Detection> involved = detections.involvedIn(fine.getPlate(), fine.getSiteId(),
                violation.getDetectedAt().minus(LOOKBACK), fine.getIssuedAt(), MAX_DETECTIONS);
        List<String> images = involved.stream().map(DetectionDirectory.Detection::imagePath).filter(p -> p != null).toList();
        BigDecimal minConfidence = involved.stream().map(DetectionDirectory.Detection::confidence)
                .filter(c -> c != null).min(BigDecimal::compareTo).orElse(null);
        String summary = violation.getRuleSummary() != null ? violation.getRuleSummary() : ReasonTexts.of(violation.getViolationType());

        return evidence.save(FineEvidence.of(fine, violation, zoneAddressOf(fine), sites.addressOf(fine.getSiteId()).orElse(null),
                spotNumberOf(violation), summary, involved.stream().map(d -> d.id().toString()).toList(), images, minConfidence));
    }

    /** The frozen evidence; empty for fines issued before evidence was recorded. */
    @Transactional(readOnly = true)
    public Optional<FineEvidence> find(UUID fineId) {
        return evidence.findById(fineId);
    }

    /**
     * What the driver may see of a fine: the evidence without snapshots and ids, plus the state of the latest dispute. The
     * addresses are those frozen with the evidence, else the zone and site as they are now.
     */
    @Transactional(readOnly = true)
    public FineExplanation explain(Fine fine) {
        Optional<FineEvidence> frozen = evidence.findById(fine.getId());
        String zoneAddress = frozen.isPresent() ? frozen.get().getZoneAddress() : zoneAddressOf(fine);
        String siteAddress = frozen.map(FineEvidence::getSiteAddress)
                .orElseGet(() -> sites.addressOf(fine.getSiteId()).orElse(null));
        DisputeStatus status = disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fine.getId()).map(d -> d.getStatus()).orElse(null);
        return new FineExplanation(fine, frozen.orElse(null), zoneAddress, siteAddress, status);
    }

    /** Null for a fine without a zone. */
    private String zoneAddressOf(Fine fine) {
        return fine.getZoneId() == null ? null : zones.addressOf(fine.getZoneId()).orElse(null);
    }

    /** The spot's label, whether it is a legacy spot or drawn on a camera view; null when the spot no longer exists. */
    private String spotNumberOf(Violation violation) {
        return spots.spotNumberOf(violation.getSpotId()).orElse(null);
    }
}
