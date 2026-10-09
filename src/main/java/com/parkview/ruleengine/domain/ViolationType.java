package com.parkview.ruleengine.domain;

/**
 * Kinds of parking violations. The constant names are the wire format (events, REST) and the value
 * stored in {@code spot_violations.violation_type}; {@code fines.reason} stores the lower-case form
 * (see {@link LowercaseViolationTypeConverter}).
 */
public enum ViolationType {
    BOUNDARY_EXCEEDED,
    WRONG_PERMIT,
    OVERSTAY,
    NO_PARKING
}
