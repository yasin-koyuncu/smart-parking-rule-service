package com.parkview.ruleengine.domain;

import java.util.Locale;

/** Who asked for the review: the driver themself, or an operator on the phone with a driver. */
public enum DisputeChannel {
    DRIVER,
    OPERATOR;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
