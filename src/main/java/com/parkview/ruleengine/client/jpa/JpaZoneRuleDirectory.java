package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.ZoneRuleDirectory;
import com.parkview.ruleengine.domain.value.ZoneRule;
import com.parkview.ruleengine.readmodel.ZoneRuleView;
import com.parkview.ruleengine.readmodel.ZoneRuleViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads the enabled rules of a zone or of a site. Rules that cannot be evaluated (no window) are skipped with a warning. */
@Slf4j
@Component
@RequiredArgsConstructor
class JpaZoneRuleDirectory implements ZoneRuleDirectory {

    private final ZoneRuleViewRepository rules;

    @Override
    public List<ZoneRule> enabledRulesOf(String zoneId) {
        return toRules(rules.findByZoneIdAndEnabledTrue(zoneId));
    }

    @Override
    public List<ZoneRule> enabledSiteRulesOf(String siteId) {
        return toRules(rules.findBySiteIdAndZoneIdIsNullAndEnabledTrue(siteId));
    }

    private List<ZoneRule> toRules(List<ZoneRuleView> views) {
        return views.stream()
                .map(this::toRule)
                .filter(Objects::nonNull)
                .toList();
    }

    /** @return the rule, or null (skipped) when its time window is missing or unreadable */
    private ZoneRule toRule(ZoneRuleView view) {
        LocalTime start = parseTime(view.getStartTime());
        LocalTime end = parseTime(view.getEndTime());
        if (start == null || end == null) {
            log.warn("Skipping rule {} of site {} zone {}: no usable time window", view.getId(), view.getSiteId(), view.getZoneId());
            return null;
        }
        return new ZoneRule(view.getId(), view.getZoneId(), view.getLabel(), view.getPriority(),
                codes(view.getVehicleTypes()), view.getPermitRequired(), weekdays(view.getWeekdays()),
                start, end, view.isAppliesHolidays(), view.getMaxMinutes(), view.isNoParking());
    }

    private static LocalTime parseTime(String value) {
        try {
            return value == null ? null : LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<String> codes(String[] vehicleTypes) {
        if (vehicleTypes == null) {
            return List.of();
        }
        return Arrays.stream(vehicleTypes)
                .filter(Objects::nonNull)
                .map(code -> code.trim().toLowerCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .toList();
    }

    private static Set<Integer> weekdays(Integer[] weekdays) {
        return weekdays == null ? Set.of() : Arrays.stream(weekdays).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }
}
