package com.parkview.ruleengine.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps {@code app_metadata.role} to a single {@code ROLE_*} authority and {@code app_metadata.permissions} to one
 * authority per permission (named exactly like it, e.g. {@code zone:write}). A token without the permissions claim
 * gets the defaults of its role while {@code legacyRoleFallback} is on; a present claim is never second-guessed.
 */
public class ParkviewJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final boolean legacyRoleFallback;

    public ParkviewJwtAuthenticationConverter() {
        this(true);
    }

    public ParkviewJwtAuthenticationConverter(boolean legacyRoleFallback) {
        this.legacyRoleFallback = legacyRoleFallback;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (appMetadata != null) {
            Optional<Role> role = Role.parse(appMetadata.get("role"));
            role.ifPresent(r -> authorities.add(new SimpleGrantedAuthority(r.authority())));
            Permission.resolve(appMetadata.get("permissions"), role, legacyRoleFallback)
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
