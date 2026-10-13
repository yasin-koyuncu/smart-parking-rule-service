package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.security.ParkviewJwtAuthenticationConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WebTestSupport {

    static final String INTERNAL_KEY = "test-internal-key";
    static final String ACTOR_ID = "2f6c1e0a-3b7d-4c55-8f1e-6a0b9d7c1234";

    private WebTestSupport() {
    }

    /**
     * A verified JWT shaped like the Supabase access-token hook output, without the permissions claim: the real converter
     * applies the legacy role fallback (default on), so the caller holds the default permissions of the role.
     */
    static RequestPostProcessor user(String role, List<String> plates, List<String> zones) {
        return token(true, role, null, plates, zones);
    }

    /** Same, but the token carries exactly these permissions (an empty list means none, no fallback). */
    static RequestPostProcessor userWith(String role, List<String> permissions, List<String> plates, List<String> zones) {
        return token(true, role, permissions, plates, zones);
    }

    /** A token without the permissions claim, seen by a service with {@code legacy-role-fallback=false}. */
    static RequestPostProcessor userWithoutFallback(String role, List<String> plates, List<String> zones) {
        return token(false, role, null, plates, zones);
    }

    /** {@code permissions == null} means the claim is absent. */
    static RequestPostProcessor token(boolean legacyRoleFallback, String role, List<String> permissions,
                                      List<String> plates, List<String> zones) {
        return token(legacyRoleFallback, role, permissions, plates, zones, "user-1");
    }

    /** A staff token whose subject is a user id (UUID) like Supabase issues it, for commands that record who acted. */
    static RequestPostProcessor actor(String role, List<String> permissions, List<String> zones) {
        return token(true, role, permissions, List.of(), zones, ACTOR_ID);
    }

    static RequestPostProcessor token(boolean legacyRoleFallback, String role, List<String> permissions,
                                      List<String> plates, List<String> zones, String subject) {
        Map<String, Object> appMetadata = new HashMap<>(Map.of("role", role, "plates", plates, "zones", zones));
        if (permissions != null) {
            appMetadata.put("permissions", permissions);
        }
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "ES256").subject(subject).claim("app_metadata", appMetadata).build();
        return SecurityMockMvcRequestPostProcessors.authentication(new ParkviewJwtAuthenticationConverter(legacyRoleFallback).convert(jwt));
    }
}
