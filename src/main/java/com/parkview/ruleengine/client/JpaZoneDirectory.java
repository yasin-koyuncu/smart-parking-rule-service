package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.ZoneInfo;
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
        return zones.findById(zoneId).map(ZoneView::getAddress).filter(a -> !a.isBlank());
    }

    @Override
    public Optional<ZoneInfo> infoOf(String zoneId) {
        return zones.findById(zoneId).map(z -> ZoneInfo.of(z.getAddress(), z.getMaxMinutes(), z.getCountry(), z.getTimezone()));
    }
}
