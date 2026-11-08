package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.readmodel.SessionView;
import com.parkview.ruleengine.readmodel.SessionViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class JpaActiveSessionDirectory implements ActiveSessionDirectory {

    private final SessionViewRepository sessions;

    /**
     * A voided session (an operator corrected a wrong one) is not active: it must not cause an overstay. Always limited to the
     * site, so a spot without a zone never sees a session of another site.
     */
    @Override
    public Optional<Instant> startOfActiveSession(String normalizedPlate, String siteId, String zoneId) {
        return activeSession(normalizedPlate, siteId, zoneId).map(SessionView::getStartedAt);
    }

    @Override
    public Optional<UUID> idOfActiveSession(String normalizedPlate, String siteId, String zoneId) {
        return activeSession(normalizedPlate, siteId, zoneId).map(SessionView::getId);
    }

    private Optional<SessionView> activeSession(String normalizedPlate, String siteId, String zoneId) {
        if (siteId == null) {
            return Optional.empty();
        }
        return zoneId != null
                ? sessions.findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullAndVoidedAtIsNullOrderByStartedAtDesc(
                        normalizedPlate, siteId, zoneId)
                : sessions.findFirstByPlateAndSiteIdAndEndedAtIsNullAndVoidedAtIsNullOrderByStartedAtDesc(normalizedPlate, siteId);
    }
}
