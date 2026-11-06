package com.parkview.ruleengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {

    private static SecurityProperties bind(Map<String, String> properties) {
        Map<String, String> all = new java.util.HashMap<>(Map.of("parkview.security.issuer", "http://issuer.test/auth/v1"));
        all.putAll(properties);
        return new Binder(new MapConfigurationPropertySource(all)).bind("parkview.security", SecurityProperties.class).get();
    }

    @Test
    void legacyRoleFallbackIsOffByDefault() {
        assertThat(bind(Map.of()).legacyRoleFallback()).isFalse();
    }

    @Test
    void legacyRoleFallbackCanBeSwitchedOn() {
        assertThat(bind(Map.of("parkview.security.legacy-role-fallback", "true")).legacyRoleFallback()).isTrue();
    }
}
