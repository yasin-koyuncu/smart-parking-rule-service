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
 * @param internalApiKey the OWN secret of this service for service-to-service calls to {@code /internal/**} (callers hold it as
 *                       {@code RULE_INTERNAL_API_KEY}); blank means it matches nothing
 * @param internalApiKeyPrevious the key before a rotation, accepted as well while it is set; optional
 * @param opsApiKey      secret of the operations endpoints (non-health actuator, retention); blank means it matches nothing
 * @param publicPaths    Ant patterns that need no authentication (health is always public)
 */
@Validated
@ConfigurationProperties("parkview.security")
public record SecurityProperties(
        @NotBlank String issuer,
        String jwksUri,
        @DefaultValue("authenticated") String audience,
        String internalApiKey,
        String internalApiKeyPrevious,
        String opsApiKey,
        @DefaultValue List<String> publicPaths
) {

    public String resolvedJwksUri() {
        return (jwksUri == null || jwksUri.isBlank()) ? issuer + "/.well-known/jwks.json" : jwksUri;
    }

    /** False when no key is set at all: {@code /internal/**} and the non-health actuator endpoints are then denied entirely. */
    public boolean internalApiEnabled() {
        return isSet(internalApiKey) || isSet(internalApiKeyPrevious) || isSet(opsApiKey);
    }

    private static boolean isSet(String key) {
        return key != null && !key.isBlank();
    }
}
