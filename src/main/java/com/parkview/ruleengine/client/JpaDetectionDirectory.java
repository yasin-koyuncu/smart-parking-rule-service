package com.parkview.ruleengine.client;

import com.parkview.ruleengine.readmodel.DetectionViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
class JpaDetectionDirectory implements DetectionDirectory {

    private final DetectionViewRepository detections;

    @Override
    public List<Detection> involvedIn(String normalizedPlate, String zoneId, Instant from, Instant to, int limit) {
        return detections.findInvolved(normalizedPlate, zoneId, from, to, PageRequest.of(0, limit)).stream()
                .map(d -> new Detection(d.getId(), d.getImagePath() == null || d.getImagePath().isBlank() ? null : d.getImagePath(),
                        d.getConfidence(), d.getAt()))
                .toList();
    }
}
