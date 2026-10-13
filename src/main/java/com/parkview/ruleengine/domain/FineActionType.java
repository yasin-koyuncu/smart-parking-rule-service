package com.parkview.ruleengine.domain;

import java.util.Locale;

/** What happened to a fine, as stored in {@code fine_actions.action} (lower case, CHECK constrained). */
public enum FineActionType {
    VOID,
    MARK_PAID,
    PAID_ONLINE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
