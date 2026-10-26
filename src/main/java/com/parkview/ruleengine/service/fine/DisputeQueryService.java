package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads of disputes. The callers decide who may see what; nothing here changes state. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisputeQueryService {

    /** Which disputes the operator list shows. {@code DECIDED} is everything that is not open any more (upheld, voided, withdrawn). */
    public enum Filter {
        OPEN(EnumSet.of(DisputeStatus.OPEN)),
        DECIDED(EnumSet.of(DisputeStatus.UPHELD, DisputeStatus.VOIDED, DisputeStatus.WITHDRAWN)),
        ALL(EnumSet.allOf(DisputeStatus.class));

        private final Set<DisputeStatus> statuses;

        Filter(Set<DisputeStatus> statuses) {
            this.statuses = statuses;
        }

        public Set<DisputeStatus> statuses() {
            return statuses;
        }
    }

    private final DisputeRepository disputes;
    private final FineRepository fines;
    private final ZoneDirectory zones;
    private final SiteDirectory sites;

    public Dispute get(UUID disputeId) {
        return disputes.findById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Dispute", disputeId));
    }

    /** The latest dispute of the fine, empty when there never was one. */
    public Optional<Dispute> latestOf(UUID fineId) {
        return disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fineId);
    }

    public DisputeDetails detailsOf(Dispute dispute) {
        Fine fine = fines.findById(dispute.getFineId()).orElseThrow(() -> new ResourceNotFoundException("Fine", dispute.getFineId()));
        return new DisputeDetails(dispute, fine, zoneAddressOf(dispute.getZoneId()), sites.addressOf(dispute.getSiteId()).orElse(null));
    }

    /**
     * The operator list.
     *
     * @param siteIds the sites the caller may see; {@code null} means every site (administrators)
     * @param zoneId  only the disputes of this zone, null for all of the sites
     */
    public Page<DisputeDetails> list(Filter filter, Collection<String> siteIds, String zoneId, Pageable pageable) {
        if (siteIds != null && siteIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Dispute> page;
        if (zoneId != null) {
            page = disputes.findByStatusInAndZoneIdIn(filter.statuses(), List.of(zoneId), pageable);
        } else if (siteIds != null) {
            page = disputes.findByStatusInAndSiteIdIn(filter.statuses(), siteIds, pageable);
        } else {
            page = disputes.findByStatusIn(filter.statuses(), pageable);
        }
        return new PageImpl<>(detailsOf(page.getContent()), pageable, page.getTotalElements());
    }

    /** One query for the fines and one per zone and site for the addresses, however long the page is. */
    private List<DisputeDetails> detailsOf(List<Dispute> page) {
        Map<UUID, Fine> byId = fines.findAllById(page.stream().map(Dispute::getFineId).distinct().toList()).stream()
                .collect(Collectors.toMap(Fine::getId, Function.identity()));
        Map<String, Optional<String>> zoneAddresses = new HashMap<>();
        Map<String, Optional<String>> siteAddresses = new HashMap<>();
        return page.stream()
                .map(d -> new DisputeDetails(d, byId.get(d.getFineId()),
                        d.getZoneId() == null ? null : zoneAddresses.computeIfAbsent(d.getZoneId(), zones::addressOf).orElse(null),
                        siteAddresses.computeIfAbsent(d.getSiteId(), sites::addressOf).orElse(null)))
                .toList();
    }

    private String zoneAddressOf(String zoneId) {
        return zoneId == null ? null : zones.addressOf(zoneId).orElse(null);
    }
}
