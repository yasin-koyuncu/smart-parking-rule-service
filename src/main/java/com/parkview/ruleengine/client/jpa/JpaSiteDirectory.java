package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.readmodel.SiteView;
import com.parkview.ruleengine.readmodel.SiteViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
class JpaSiteDirectory implements SiteDirectory {

    private final SiteViewRepository sites;

    @Override
    public Optional<String> addressOf(String siteId) {
        return siteId == null ? Optional.empty()
                : sites.findById(siteId).map(SiteView::getAddress).filter(a -> a != null && !a.isBlank());
    }

    @Override
    public Optional<ZoneInfo> infoOf(String siteId) {
        return siteId == null ? Optional.empty()
                : sites.findById(siteId).map(s -> ZoneInfo.ofSite(s.getAddress(), s.getMaxMinutes(), s.getCountry(), s.getTimezone(),
                        s.isAllowsParking()));
    }
}
