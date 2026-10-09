package com.parkview.ruleengine.controller;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

final class WebTestSupport {

    static final String INTERNAL_KEY = "test-internal-key";

    private WebTestSupport() {
    }

    /** A verified JWT shaped like the Supabase access-token hook output. */
    static RequestPostProcessor user(String role, List<String> plates, List<String> zones) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject("user-1").claim("app_metadata", Map.of("role", role, "plates", plates, "zones", zones)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }
}
