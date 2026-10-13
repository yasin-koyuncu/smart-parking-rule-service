package com.parkview.ruleengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionTest {

    private static Jwt jwt(Map<String, Object> appMetadata) {
        return Jwt.withTokenValue("t").header("alg", "ES256").subject("user-1").claim("app_metadata", appMetadata).build();
    }

    private static Map<String, Object> claims(String role, Object permissions) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        if (permissions != null) {
            m.put("permissions", permissions);
        }
        return m;
    }

    private static Set<String> authorities(boolean fallback, Map<String, Object> appMetadata) {
        return new ParkviewJwtAuthenticationConverter(fallback).convert(jwt(appMetadata)).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void presentClaimBecomesOneAuthorityPerPermissionNextToTheRole() {
        assertThat(authorities(true, claims("operator", List.of("zone:write", "mapping:view"))))
                .containsExactlyInAnyOrder("ROLE_OPERATOR", "zone:write", "mapping:view");
    }

    @Test
    void presentButEmptyClaimMeansNoPermissionsEvenWithFallback() {
        assertThat(authorities(true, claims("operator", List.of()))).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void absentClaimFallsBackToTheRoleDefaultsOnlyWhileTheFallbackIsOn() {
        assertThat(authorities(true, claims("operator", null))).contains("ROLE_OPERATOR", "spot:write", "violation:resolve")
                .doesNotContain("zone:write", "access:manage", "session:correct", "fine:manage");
        assertThat(authorities(true, claims("admin", null))).contains("ROLE_ADMIN", "zone:write", "access:manage", "session:correct", "fine:manage");
        assertThat(authorities(true, claims("driver", null))).containsExactly("ROLE_DRIVER");
        assertThat(authorities(false, claims("operator", null))).containsExactly("ROLE_OPERATOR");
        assertThat(authorities(false, claims("admin", null))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownRoleGetsNoFallbackAndMalformedClaimsGrantNothing() {
        assertThat(authorities(true, claims("hacker", null))).isEmpty();
        assertThat(authorities(true, claims("operator", "zone:write"))).containsExactly("ROLE_OPERATOR");
        assertThat(authorities(true, claims("operator", Map.of("zone:write", true)))).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void onlyResourceActionNamesAreGranted() {
        List<Object> raw = new ArrayList<>(List.of("zone:write", " spot:write ", "ROLE_ADMIN", "SCOPE_x", "", "a:b:c", "Zone:Write"));
        raw.add(null);
        raw.add(42);
        assertThat(authorities(true, claims("operator", raw))).containsExactlyInAnyOrder("ROLE_OPERATOR", "zone:write", "spot:write");
    }

    @Test
    void tokenWithoutAppMetadataHasNoAuthorities() {
        Jwt bare = Jwt.withTokenValue("t").header("alg", "ES256").subject("u").claim("sub", "u").build();
        assertThat(new ParkviewJwtAuthenticationConverter().convert(bare).getAuthorities()).isEmpty();
    }

    @Test
    void resolveReturnsTheClaimWhenPresentAndTheDefaultsOtherwise() {
        assertThat(Permission.resolve(List.of("a:b"), Optional.of(Role.ADMIN), true)).containsExactly("a:b");
        assertThat(Permission.resolve(null, Optional.of(Role.OPERATOR), true)).isEqualTo(Permission.defaultsFor(Role.OPERATOR));
        assertThat(Permission.resolve(null, Optional.empty(), true)).isEmpty();
        assertThat(Permission.resolve(null, Optional.of(Role.OPERATOR), false)).isEmpty();
    }
}
