package com.parkview.ruleengine.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves the authenticated caller from the Spring Security context. */
@Component
public class CurrentUser {

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

    public static Optional<AuthenticatedUser> from(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        if (appMetadata == null) {
            return Optional.empty();
        }
        return Role.parse(appMetadata.get("role")).map(role -> new AuthenticatedUser(
                jwt.getSubject(),
                role,
                stringList(appMetadata.get("plates")).stream().map(AuthenticatedUser::normalizePlate).toList(),
                stringList(appMetadata.get("zones"))));
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }
}
