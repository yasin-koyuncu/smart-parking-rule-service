package com.parkview.ruleengine.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Security settings shared by every service ({@code parkview.security.*}).
 *
 * @param issuer         expected JWT issuer, e.g. {@code https://<ref>.supabase.co/auth/v1}
 * @param jwksUri        JWKS endpoint; defaults to {@code <issuer>/.well-known/jwks.json}
 * @param audience       required {@code aud} claim
 * @param internalApiKey shared secret for service-to-service calls to {@code /internal/**};
 *                       blank means the internal API is disabled (denyAll)
 * @param publicPaths    Ant patterns that need no authentication (health is always public)
 */
@Validated
@ConfigurationProperties("parkview.security")
public record SecurityProperties(
        @NotBlank String issuer,
        String jwksUri,
        @DefaultValue("authenticated") String audience,
        String internalApiKey,
        @DefaultValue List<String> publicPaths
) {

    public String resolvedJwksUri() {
        return (jwksUri == null || jwksUri.isBlank()) ? issuer + "/.well-known/jwks.json" : jwksUri;
    }

    public boolean internalApiEnabled() {
        return internalApiKey != null && !internalApiKey.isBlank();
    }
}
