package com.parkview.ruleengine.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Service-to-service authentication for {@code /internal/**} and the non-health actuator endpoints. The caller presents a
 * secret in {@code X-Internal-Api-Key}: this service's own key or its previous one (rotation) grants {@code ROLE_INTERNAL},
 * the ops key grants {@code ROLE_OPS}. Requests without (or with a wrong) key simply stay anonymous and are rejected by the
 * filter chain. An unset key never matches; comparisons take constant time. The gateway never routes {@code /internal/**},
 * so these endpoints are only reachable from inside the cluster network.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL";
    public static final String OPS_AUTHORITY = "ROLE_OPS";

    private final byte[] own;
    private final byte[] previous;
    private final byte[] ops;

    public InternalApiKeyFilter(String ownKey, String previousKey, String opsKey) {
        this.own = bytes(ownKey);
        this.previous = bytes(previousKey);
        this.ops = bytes(opsKey);
    }

    /** @return the key as bytes, or null when it is unset (blank): such a key never matches */
    private static byte[] bytes(String key) {
        return key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/internal/") || uri.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null) {
            byte[] presented = header.getBytes(StandardCharsets.UTF_8);
            // every comparison runs, so the time does not tell which key matched
            boolean internal = matches(own, presented) | matches(previous, presented);
            boolean operations = matches(ops, presented);
            if (internal) {
                SecurityContextHolder.getContext().setAuthentication(new KeyAuthentication("internal-service", AUTHORITY));
            } else if (operations) {
                SecurityContextHolder.getContext().setAuthentication(new KeyAuthentication("ops", OPS_AUTHORITY));
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(byte[] expected, byte[] presented) {
        return expected != null && MessageDigest.isEqual(expected, presented);
    }

    static final class KeyAuthentication extends AbstractAuthenticationToken {
        private final String principal;

        KeyAuthentication(String principal, String authority) {
            super(AuthorityUtils.createAuthorityList(authority));
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
