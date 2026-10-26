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

    /**
     * A voided session (an operator corrected a wrong one) is not active: it must not cause an overstay. Always limited to the
     * site, so a spot without a zone never sees a session of another site.
     */
    @Override
    public Optional<Instant> startOfActiveSession(String normalizedPlate, String siteId, String zoneId) {
        if (siteId == null) {
            return Optional.empty();
        }
        Optional<SessionView> active;
        if (zoneId != null) {
            active = schema.present()
                    ? sessions.findActiveNotVoided(normalizedPlate, siteId, zoneId)
                    : sessions.findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc(normalizedPlate, siteId, zoneId);
        } else {
            active = schema.present()
                    ? sessions.findActiveNotVoidedAtSite(normalizedPlate, siteId)
                    : sessions.findFirstByPlateAndSiteIdAndEndedAtIsNullOrderByStartedAtDesc(normalizedPlate, siteId);
        }
        return active.map(SessionView::getStartedAt);
    }
}
