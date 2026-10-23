package com.parkview.ruleengine.domain.type;

import java.util.Locale;
import java.util.Optional;

/** State of a dispute, as stored in {@code fine_disputes.status} and shown by the API (lower case). Everything but {@code OPEN} is final. */
public enum DisputeStatus {
    /** Waiting for an operator; the fine cannot be paid meanwhile. */
    OPEN,
    /** An operator reviewed the evidence and the fine stands (also: the fine was paid while the dispute was open). */
    UPHELD,
    /** An operator reviewed the evidence and voided the fine. */
    VOIDED,
    /** The driver took the dispute back. */
    WITHDRAWN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isOpen() {
        return this == OPEN;
    }

    public static Optional<DisputeStatus> parse(String raw) {
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
