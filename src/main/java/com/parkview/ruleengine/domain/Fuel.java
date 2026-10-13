package com.parkview.ruleengine.domain;

import java.util.Locale;
import java.util.Set;

/** Drive of a vehicle as far as the engine cares: purely electric or not. Hybrids are {@link #OTHER}. */
public enum Fuel {
    ELECTRIC,
    OTHER,
    UNKNOWN;

    private static final Set<String> ELECTRIC_VALUES = Set.of("el", "electric", "electricity");

    /** Maps the registry fuel type ({@code El}, {@code Electric}, {@code Bensin}, {@code El/Bensin}, ...); blank is unknown. */
    public static Fuel fromRegistry(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return ELECTRIC_VALUES.contains(value.trim().toLowerCase(Locale.ROOT)) ? ELECTRIC : OTHER;
    }
}
