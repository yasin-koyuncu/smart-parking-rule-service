package com.parkview.ruleengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static Set<String> authorities(Map<String, Object> appMetadata) {
        return new ParkviewJwtAuthenticationConverter().convert(jwt(appMetadata)).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void presentClaimBecomesOneAuthorityPerPermissionNextToTheRole() {
        assertThat(authorities(claims("operator", List.of("zone:write", "zone:read"))))
                .containsExactlyInAnyOrder("ROLE_OPERATOR", "zone:write", "zone:read");
    }

    @Test
    void presentButEmptyClaimMeansNoPermissions() {
        assertThat(authorities(claims("operator", List.of()))).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void absentClaimMeansNoPermissionsWhateverTheRole() {
        assertThat(authorities(claims("operator", null))).containsExactly("ROLE_OPERATOR");
        assertThat(authorities(claims("admin", null))).containsExactly("ROLE_ADMIN");
        assertThat(authorities(claims("driver", null))).containsExactly("ROLE_DRIVER");
    }

    @Test
    void unknownRoleGetsNoAuthorityAndMalformedClaimsGrantNothing() {
        assertThat(authorities(claims("hacker", null))).isEmpty();
        assertThat(authorities(claims("operator", "zone:write"))).containsExactly("ROLE_OPERATOR");
        assertThat(authorities(claims("operator", Map.of("zone:write", true)))).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void onlyResourceActionNamesAreGranted() {
        List<Object> raw = new ArrayList<>(List.of("zone:write", " spot:write ", "ROLE_ADMIN", "SCOPE_x", "", "a:b:c", "Zone:Write"));
        raw.add(null);
        raw.add(42);
        assertThat(authorities(claims("operator", raw))).containsExactlyInAnyOrder("ROLE_OPERATOR", "zone:write", "spot:write");
    }

    @Test
    void tokenWithoutAppMetadataHasNoAuthorities() {
        Jwt bare = Jwt.withTokenValue("t").header("alg", "ES256").subject("u").claim("sub", "u").build();
        assertThat(new ParkviewJwtAuthenticationConverter().convert(bare).getAuthorities()).isEmpty();
    }

    @Test
    void fromClaimIsEmptyWhenAbsentOrMalformedAndKeepsTheValidNames() {
        assertThat(Permission.fromClaim(null)).isEmpty();
        assertThat(Permission.fromClaim("zone:write")).isEmpty();
        assertThat(Permission.fromClaim(List.of("a:b", "nope"))).containsExactly("a:b");
    }

    @Test
    void theCatalogueHoldsTheAccessPermissions() {
        assertThat(Permission.ALL).contains("zone:write", "access:manage", "role:manage");
    }
}
