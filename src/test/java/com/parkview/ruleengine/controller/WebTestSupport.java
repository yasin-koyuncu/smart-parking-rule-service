package com.parkview.ruleengine.controller;

import com.parkview.platform.security.OperatorSites;
import com.parkview.platform.security.PlateDirectory;
import com.parkview.platform.security.Plates;
import com.parkview.platform.security.servlet.ParkviewJwtAuthenticationConverter;
import com.parkview.ruleengine.security.Permission;
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

    /** What the tests give an operator: the staff bundle (no fine:manage, no zone:write, no access:*). */
    private static final List<String> OPERATOR_PERMISSIONS = List.of(
            "zone:read", "spot:read", "spot:write", "session:read", "session:manage", "violation:read", "violation:resolve",
            "fine:read", "permit:read", "permit:manage", "lookup:vehicle", "file:upload", "file:view", "stream:operator",
            "camera:read", "camera:admin", "ai:use");

    /** The permissions a test token of this role carries: everything for admin, the staff bundle for operator, nothing else. */
    static List<String> permissionsOf(String role) {
        return switch (role) {
            case "admin" -> List.copyOf(Permission.ALL);
            case "operator" -> OPERATOR_PERMISSIONS;
            default -> List.of();
        };
    }

    /**
     * A verified JWT shaped like the access-token hook output (no plates, no sites) that carries the permissions of
     * {@link #permissionsOf(String)} as an explicit claim.
     * {@code plates} are the plates and {@code zones} the sites the directories know for the subject of this token.
     */
    static RequestPostProcessor user(String role, List<String> plates, List<String> zones) {
        return token(role, permissionsOf(role), plates, zones);
    }

    /** Same, but the token carries exactly these permissions (an empty list means none). */
    static RequestPostProcessor userWith(String role, List<String> permissions, List<String> plates, List<String> zones) {
        return token(role, permissions, plates, zones);
    }

    /** A token without the permissions claim: it has no permissions, whatever its role. */
    static RequestPostProcessor userWithoutClaim(String role, List<String> plates, List<String> zones) {
        return token(role, null, plates, zones);
    }

    /** {@code permissions == null} means the claim is absent. A user with plates gets a subject of its own, so tokens do not share plates. */
    static RequestPostProcessor token(String role, List<String> permissions, List<String> plates, List<String> zones) {
        String subject = plates.isEmpty() ? "user-1" : "user-1-" + String.join("+", plates);
        return token(role, permissions, plates, zones, subject);
    }

    /** A staff token carrying the permissions of the role, whose subject is a user id (UUID) like Supabase issues it, for commands that record who acted. */
    static RequestPostProcessor actor(String role, List<String> zones) {
        return actor(role, permissionsOf(role), zones);
    }

    /** Same with exactly these permissions ({@code null}: no claim). */
    static RequestPostProcessor actor(String role, List<String> permissions, List<String> zones) {
        return token(role, permissions, List.of(), zones, ACTOR_ID);
    }

    static RequestPostProcessor token(String role, List<String> permissions, List<String> plates, List<String> zones, String subject) {
        PLATES_OF_USER.put(subject, plates.stream().map(Plates::normalize).collect(Collectors.toUnmodifiableSet()));
        SITES_OF_USER.put(subject, Set.copyOf(zones));
        Map<String, Object> appMetadata = new HashMap<>(Map.of("role", role));
        if (permissions != null) {
            appMetadata.put("permissions", permissions);
        }
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "ES256").subject(subject).claim("app_metadata", appMetadata).build();
        return SecurityMockMvcRequestPostProcessors.authentication(new ParkviewJwtAuthenticationConverter().convert(jwt));
    }
}
