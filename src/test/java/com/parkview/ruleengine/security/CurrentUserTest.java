package com.parkview.ruleengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurrentUserTest {

    private static final String USER = "00000000-0000-0000-0000-0000000000c1";

    private final OperatorSites operatorSites = mock(OperatorSites.class);
    private final CurrentUser currentUser = new CurrentUser(operatorSites);

    private static Jwt token(Map<String, Object> appMetadata) {
        return Jwt.withTokenValue("t").header("alg", "ES256").subject(USER).claim("app_metadata", appMetadata).build();
    }

    @Test
    void anOperatorGetsTheCurrentSitesFromTheDirectoryNotFromTheToken() {
        when(operatorSites.sitesOf(USER)).thenReturn(Set.of("s1", "s2"));

        // a stale "sites" claim in an old token is ignored
        AuthenticatedUser user = currentUser.from(token(Map.of("role", "operator", "sites", List.of("old")))).orElseThrow();

        assertThat(user.sites()).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void anAdminAndADriverAreNotLookedUpAndHaveNoSiteList() {
        assertThat(currentUser.from(token(Map.of("role", "admin"))).orElseThrow().sites()).isEmpty();
        assertThat(currentUser.from(token(Map.of("role", "driver"))).orElseThrow().sites()).isEmpty();

        verifyNoInteractions(operatorSites);
    }

    @Test
    void aTokenWithoutAKnownRoleIsNoUser() {
        assertThat(currentUser.from(token(Map.of("role", "stranger")))).isEmpty();
        verifyNoInteractions(operatorSites);
    }
}
