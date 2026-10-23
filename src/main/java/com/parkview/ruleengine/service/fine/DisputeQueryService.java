package com.parkview.ruleengine.service.fine;

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

    public Dispute get(UUID disputeId) {
        return disputes.findById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Dispute", disputeId));
    }

    /** The latest dispute of the fine, empty when there never was one. */
    public Optional<Dispute> latestOf(UUID fineId) {
        return disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fineId);
    }

    public DisputeDetails detailsOf(Dispute dispute) {
        Fine fine = fines.findById(dispute.getFineId()).orElseThrow(() -> new ResourceNotFoundException("Fine", dispute.getFineId()));
        return new DisputeDetails(dispute, fine, zones.addressOf(dispute.getZoneId()).orElse(null));
    }

    /**
     * The operator list.
     *
     * @param zoneIds the zones the caller may see; {@code null} means every zone (administrators)
     */
    public Page<DisputeDetails> list(Filter filter, Collection<String> zoneIds, Pageable pageable) {
        if (zoneIds != null && zoneIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Dispute> page = zoneIds == null
                ? disputes.findByStatusIn(filter.statuses(), pageable)
                : disputes.findByStatusInAndZoneIdIn(filter.statuses(), zoneIds, pageable);
        return new PageImpl<>(detailsOf(page.getContent()), pageable, page.getTotalElements());
    }

    /** One query for the fines and one per zone for the addresses, however long the page is. */
    private List<DisputeDetails> detailsOf(List<Dispute> page) {
        Map<UUID, Fine> byId = fines.findAllById(page.stream().map(Dispute::getFineId).distinct().toList()).stream()
                .collect(Collectors.toMap(Fine::getId, Function.identity()));
        Map<String, Optional<String>> addresses = new HashMap<>();
        return page.stream()
                .map(d -> new DisputeDetails(d, byId.get(d.getFineId()),
                        addresses.computeIfAbsent(d.getZoneId(), zones::addressOf).orElse(null)))
                .toList();
    }
}
