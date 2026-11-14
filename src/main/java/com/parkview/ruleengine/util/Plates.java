package com.parkview.ruleengine.util;

/**
 * Plate helpers of the service on top of {@link com.parkview.platform.security.Plates}: blank input counts as "no plate", and
 * masking for logs (plates are personal data).
 */
public final class Plates {

    private Plates() {
    }

    /**
     * The normal form of {@link com.parkview.platform.security.Plates#normalize} (upper-case, without spaces and dashes) for
     * storage and comparison; null stays null and blank becomes null.
     */
    public static String normalize(String plate) {
        String normalized = com.parkview.platform.security.Plates.normalize(plate);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    /** {@code ABC123} becomes {@code A***3}. */
    public static String mask(String plate) {
        if (plate == null || plate.length() <= 2) {
            return "***";
        }
        return plate.charAt(0) + "***" + plate.charAt(plate.length() - 1);
    }
}
