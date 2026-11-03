package com.parkview.ruleengine.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the authenticated caller from the Spring Security context. The token gives identity and role; the sites of an
 * operator are not in it but looked up through {@link OperatorSites}, so they are current (ADR 0011). Admins mean every site
 * and drivers none: neither triggers a lookup.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final OperatorSites operatorSites;

    public Optional<AuthenticatedUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        return Optional.empty();
    }

    /** @throws AccessDeniedException when there is no valid user with a known role */
    public AuthenticatedUser require() {
        return get().orElseThrow(() -> new AccessDeniedException("Authenticated user with a known role required"));
    }

    /** Whether the caller holds the permission (a granted authority named exactly like it, e.g. {@code violation:read}). */
    public boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** The caller described by the token, with the current sites when the role is operator. */
    public Optional<AuthenticatedUser> from(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        if (appMetadata == null) {
            return Optional.empty();
        }
        return Role.parse(appMetadata.get("role")).map(role -> new AuthenticatedUser(
                jwt.getSubject(),
                role,
                role == Role.OPERATOR ? List.copyOf(operatorSites.sitesOf(jwt.getSubject())) : List.of()));
    }
}
