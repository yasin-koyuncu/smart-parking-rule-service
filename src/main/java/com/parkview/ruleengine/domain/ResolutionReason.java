package com.parkview.ruleengine.domain;

/** Why an open violation was closed without a fine. */
public enum ResolutionReason {
    /** The vehicle was seen again and no longer violates the rule (moved, or a permit now covers it). */
    CORRECTED,
    /** The vehicle was not seen for the configured stale period, or is no longer in any spot. */
    LEFT,
    /** An operator closed it. */
    MANUAL
}
