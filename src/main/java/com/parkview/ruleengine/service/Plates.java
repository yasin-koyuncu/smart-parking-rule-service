package com.parkview.ruleengine.service;

import com.parkview.ruleengine.security.AuthenticatedUser;

/** Plate helpers: one normal form for storage and comparison, and masking for logs (plates are personal data). */
public final class Plates {

    private Plates() {
    }

    /** Upper-case, without spaces and dashes; null stays null and blank becomes null. */
    public static String normalize(String plate) {
        String normalized = AuthenticatedUser.normalizePlate(plate);
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
