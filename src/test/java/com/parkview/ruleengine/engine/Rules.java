package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.value.ZoneRule;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test builder for {@link ZoneRule}: by default an all-day, all-week, all-vehicle rule without any outcome. */
final class Rules {

    static final String ZONE = "zone-1";

    private UUID id = UUID.randomUUID();
    private int priority = 100;
    private List<String> vehicleTypes = List.of();
    private String permitRequired;
    private Set<Integer> weekdays = Set.of(1, 2, 3, 4, 5, 6, 7);
    private LocalTime start = LocalTime.MIDNIGHT;
    private LocalTime end = LocalTime.of(23, 59);
    private boolean appliesHolidays = true;
    private Integer maxMinutes;
    private boolean noParking;

    static Rules rule() {
        return new Rules();
    }

    Rules id(String id) {
        this.id = UUID.fromString(id);
        return this;
    }

    Rules priority(int priority) {
        this.priority = priority;
        return this;
    }

    Rules vehicles(String... codes) {
        this.vehicleTypes = List.of(codes);
        return this;
    }

    Rules permit(String permit) {
        this.permitRequired = permit;
        return this;
    }

    Rules weekdays(Integer... days) {
        this.weekdays = Set.of(days);
        return this;
    }

    Rules window(String start, String end) {
        this.start = LocalTime.parse(start);
        this.end = LocalTime.parse(end);
        return this;
    }

    Rules holidays(boolean appliesHolidays) {
        this.appliesHolidays = appliesHolidays;
        return this;
    }

    Rules maxMinutes(Integer maxMinutes) {
        this.maxMinutes = maxMinutes;
        return this;
    }

    Rules noParking() {
        this.noParking = true;
        return this;
    }

    ZoneRule build() {
        return new ZoneRule(id, ZONE, "rule", priority, vehicleTypes, permitRequired, weekdays, start, end,
                appliesHolidays, maxMinutes, noParking);
    }
}
