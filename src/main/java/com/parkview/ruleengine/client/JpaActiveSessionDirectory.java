package com.parkview.ruleengine.client;

import com.parkview.ruleengine.readmodel.SessionView;
import com.parkview.ruleengine.readmodel.SessionViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
class JpaActiveSessionDirectory implements ActiveSessionDirectory {

    private final SessionViewRepository sessions;

    @Override
    public Optional<Instant> startOfActiveSession(String normalizedPlate, String zoneId) {
        return sessions.findFirstByPlateAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc(normalizedPlate, zoneId)
                .map(SessionView::getStartedAt);
    }
}
