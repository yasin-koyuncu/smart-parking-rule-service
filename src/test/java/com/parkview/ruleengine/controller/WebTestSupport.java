package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.OperatorSites;
import com.parkview.ruleengine.security.ParkviewJwtAuthenticationConverter;
import com.parkview.ruleengine.security.PlateDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class WebTestSupport {

    static final String INTERNAL_KEY = "test-internal-key";
    static final String PREVIOUS_KEY = "test-previous-key";
    static final String OPS_KEY = "test-ops-key";
    static final String ACTOR_ID = "2f6c1e0a-3b7d-4c55-8f1e-6a0b9d7c1234";

    /** The plates of a user are not in the token: a token helper records them here for its subject, {@link TestPlates} serves them. */
    private static final Map<String, Set<String>> PLATES_OF_USER = new ConcurrentHashMap<>();

    /** The sites of an operator are not in the token either: a token helper records them here for its subject, {@link TestSites} serves them. */
    private static final Map<String, Set<String>> SITES_OF_USER = new ConcurrentHashMap<>();

    private WebTestSupport() {
    }

    /** The {@link PlateDirectory} of the web-layer tests: the plates given to the token helpers of this class. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestPlates {
        @Bean
        PlateDirectory plateDirectory() {
            return userId -> PLATES_OF_USER.getOrDefault(userId, Set.of());
        }
    }

    /** The {@link OperatorSites} of the web-layer tests: the sites ({@code zones}) given to the token helpers of this class. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestSites {
        @Bean
        OperatorSites operatorSites() {
            return userId -> SITES_OF_USER.getOrDefault(userId, Set.of());
        }
    }

    /**
     * A verified JWT shaped like the access-token hook output (no plates, no sites), without the permissions claim: the converter
     * is built with the legacy role fallback switched on explicitly, so the caller holds the default permissions of the role.
     * {@code plates} are the plates and {@code zones} the sites the directories know for the subject of this token.
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

    /** {@code permissions == null} means the claim is absent. A user with plates gets a subject of its own, so tokens do not share plates. */
    static RequestPostProcessor token(boolean legacyRoleFallback, String role, List<String> permissions,
                                      List<String> plates, List<String> zones) {
        String subject = plates.isEmpty() ? "user-1" : "user-1-" + String.join("+", plates);
        return token(legacyRoleFallback, role, permissions, plates, zones, subject);
    }

    /** A staff token whose subject is a user id (UUID) like Supabase issues it, for commands that record who acted. */
    static RequestPostProcessor actor(String role, List<String> permissions, List<String> zones) {
        return token(true, role, permissions, List.of(), zones, ACTOR_ID);
    }

    static RequestPostProcessor token(boolean legacyRoleFallback, String role, List<String> permissions,
                                      List<String> plates, List<String> zones, String subject) {
        PLATES_OF_USER.put(subject, plates.stream().map(AuthenticatedUser::normalizePlate).collect(Collectors.toUnmodifiableSet()));
        SITES_OF_USER.put(subject, Set.copyOf(zones));
        Map<String, Object> appMetadata = new HashMap<>(Map.of("role", role));
        if (permissions != null) {
            appMetadata.put("permissions", permissions);
        }
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "ES256").subject(subject).claim("app_metadata", appMetadata).build();
        return SecurityMockMvcRequestPostProcessors.authentication(new ParkviewJwtAuthenticationConverter(legacyRoleFallback).convert(jwt));
    }
}
