package com.parkview.ruleengine.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port: camera detections owned by ingestion-service. Today a read of {@code detections}. */
public interface DetectionDirectory {

    /**
     * The detections of the plate at the site between {@code from} and {@code to} that count as evidence (rejected
     * ones do not), newest first, at most {@code limit}.
     */
    List<Detection> involvedIn(String normalizedPlate, String siteId, Instant from, Instant to, int limit);

    /**
     * @param imagePath storage key of the camera snapshot, null when none was stored
     * @param confidence detector confidence 0..1, null when the plate was typed in or the detector gave none
     */
    record Detection(UUID id, String imagePath, BigDecimal confidence, Instant at) {
    }
}
