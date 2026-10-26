package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.readmodel.ZoneView;
import com.parkview.ruleengine.readmodel.ZoneViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
class JpaZoneDirectory implements ZoneDirectory {

    private final ZoneViewRepository zones;

    @Override
    public Optional<String> addressOf(String zoneId) {
        return zoneId == null ? Optional.empty()
                : zones.findById(zoneId).map(ZoneView::getAddress).filter(a -> !a.isBlank());
    }

    @Override
    public Optional<ZoneInfo> infoOf(String zoneId) {
        return zoneId == null ? Optional.empty()
                : zones.findById(zoneId).map(z -> ZoneInfo.of(z.getAddress(), z.getMaxMinutes(), z.getCountry(), z.getTimezone(),
                        z.isAllowsParking()));
    }

    @Override
    public Optional<String> siteOf(String zoneId) {
        return zoneId == null ? Optional.empty() : zones.findById(zoneId).map(ZoneView::getSiteId);
    }
}
