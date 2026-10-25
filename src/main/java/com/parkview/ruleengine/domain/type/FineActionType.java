package com.parkview.ruleengine.domain.type;

import java.util.Locale;

/** What happened to a fine, as stored in {@code fine_actions.action} (lower case, CHECK constrained). */
public enum FineActionType {
    VOID,
    MARK_PAID,
    PAID_ONLINE,
    DISPUTE_OPENED,
    DISPUTE_UPHELD,
    DISPUTE_VOIDED,
    DISPUTE_WITHDRAWN,
    /** A payment completed that the fine cannot account for (already paid, voided, other amount): a person must look at it. */
    PAYMENT_NEEDS_REVIEW;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
