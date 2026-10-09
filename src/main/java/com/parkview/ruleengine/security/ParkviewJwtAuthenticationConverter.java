package com.parkview.ruleengine.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

/** Maps {@code app_metadata.role} to a single {@code ROLE_*} authority. */
public class ParkviewJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
        List<GrantedAuthority> authorities = appMetadata == null
                ? List.of()
                : Role.parse(appMetadata.get("role"))
                        .<List<GrantedAuthority>>map(r -> List.of(new SimpleGrantedAuthority(r.authority())))
                        .orElse(List.of());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
