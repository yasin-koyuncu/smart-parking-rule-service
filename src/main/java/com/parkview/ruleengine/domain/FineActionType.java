package com.parkview.ruleengine.domain;

import java.util.Locale;

/** What happened to a fine, as stored in {@code fine_actions.action} (lower case, CHECK constrained). */
public enum FineActionType {
    VOID,
    MARK_PAID,
    PAID_ONLINE,
    DISPUTE_OPENED,
    DISPUTE_UPHELD,
    DISPUTE_VOIDED,
    DISPUTE_WITHDRAWN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
