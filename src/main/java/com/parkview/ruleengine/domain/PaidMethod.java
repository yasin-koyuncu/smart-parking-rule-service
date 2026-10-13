package com.parkview.ruleengine.domain;

import java.util.Locale;
import java.util.Optional;

/** How a fine was paid. {@code ONLINE} is the payment-service flow, the others are recorded by an operator. */
public enum PaidMethod {
    ONLINE,
    CASH,
    TRANSFER,
    OTHER;

    /** The lower-case form used in the database, the API and the {@code fine.paid} event. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive; {@code online} is not a manual method and is only parsed for completeness. */
    public static Optional<PaidMethod> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
