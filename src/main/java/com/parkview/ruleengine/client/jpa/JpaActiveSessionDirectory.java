package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
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
    private final SessionVoidSchema schema;

    /** A voided session (an operator corrected a wrong one) is not active: it must not cause an overstay. */
    @Override
    public Optional<Instant> startOfActiveSession(String normalizedPlate, String zoneId) {
        Optional<SessionView> active = schema.present()
                ? sessions.findActiveNotVoided(normalizedPlate, zoneId)
                : sessions.findFirstByPlateAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc(normalizedPlate, zoneId);
        return active.map(SessionView::getStartedAt);
    }
}
