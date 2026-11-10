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

/**
 * Maps {@code app_metadata.role} to a single {@code ROLE_*} authority and {@code app_metadata.permissions} to one
 * authority per permission (named exactly like it, e.g. {@code zone:write}). A token without the permissions claim
 * has no permissions.
 */
public class ParkviewJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (appMetadata != null) {
            Role.parse(appMetadata.get("role")).ifPresent(r -> authorities.add(new SimpleGrantedAuthority(r.authority())));
            Permission.fromClaim(appMetadata.get("permissions"))
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
