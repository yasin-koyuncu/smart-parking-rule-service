package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.util.Plates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Issues the fine for a violation whose grace period has passed; called by the fine sweep in {@link ViolationJobs}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineIssuingService {

    private final FineRepository fines;
    private final PlateOwnerDirectory plateOwners;
    private final SiteDirectory sites;
    private final EngineMetrics metrics;
    private final FineEvents events;
    private final FineEvidenceService evidence;
    private final RuleEngineProperties properties;
    private final Clock clock;

    /**
     * Creates the fine for a violation that the caller has locked, links it, freezes its evidence and writes
     * {@code fine.issued} to the outbox, all in the surrounding transaction: either the fine, its evidence and its
     * event exist, or none of them.
     *
     * <p>The fine takes the currency of its site. A fine is NOT issued, and the violation stays open, when the site has no valid
     * currency or when no fine amount is configured for the currency and the violation type: that is a configuration error, so it is
     * logged as an ERROR (with currency and type) and counted in {@code ruleengine.fines.not_issued}; there is no default currency.
     *
     * @return the fine, empty when it was not issued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Fine> issueFine(Violation violation) {
        String currency = sites.currencyOf(violation.getSiteId()).orElse(null);
        if (currency == null) {
            log.error("No fine issued for violation {} ({}): site {} has no valid currency; the violation stays open until the site is configured",
                    violation.getId(), violation.getViolationType(), violation.getSiteId());
            metrics.fineNotIssued("no_site_currency", "unknown", violation.getViolationType());
            return Optional.empty();
        }
        BigDecimal amount = properties.fineAmountFor(currency, violation.getViolationType()).orElse(null);
        if (amount == null) {
            log.error("No fine issued for violation {} at site {}: no fine amount is configured for currency {} and type {} "
                            + "(parkview.rule-engine.fine-amounts); the violation stays open",
                    violation.getId(), violation.getSiteId(), currency, violation.getViolationType());
            metrics.fineNotIssued("no_fine_amount", currency, violation.getViolationType());
            return Optional.empty();
        }
        Instant now = clock.instant();
        UUID owner = violation.getUserId() != null
                ? violation.getUserId()
                : plateOwners.ownerOf(violation.getPlate()).orElse(null);

        Fine fine = fines.save(Fine.issue(violation, amount, currency, owner, now));
        violation.markFined(fine.getId(), now);

        evidence.record(fine, violation);
        events.issued(fine, violation, owner, now);
        log.info("Fine {} issued: {} {} ({}) for plate {} at site {} zone {}", fine.getId(), fine.getAmount(), fine.getCurrency(),
                fine.getReason(), Plates.mask(fine.getPlate()), fine.getSiteId(), fine.getZoneId());
        return Optional.of(fine);
    }
}
