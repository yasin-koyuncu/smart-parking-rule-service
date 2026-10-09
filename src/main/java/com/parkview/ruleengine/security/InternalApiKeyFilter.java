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
 * Service-to-service authentication for {@code /internal/**} and the non-health actuator endpoints: the caller presents the shared
 * secret in {@code X-Internal-Api-Key} and is granted {@code ROLE_INTERNAL}. Requests without
 * (or with a wrong) key simply stay anonymous and are rejected by the filter chain.
 * The gateway never routes {@code /internal/**}, so these endpoints are only reachable
 * from inside the cluster network.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL";

    private final byte[] expected;

    public InternalApiKeyFilter(String apiKey) {
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/internal/") || uri.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new InternalAuthentication());
        }
        chain.doFilter(request, response);
    }

    static final class InternalAuthentication extends AbstractAuthenticationToken {
        InternalAuthentication() {
            super(AuthorityUtils.createAuthorityList(AUTHORITY));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return "internal-service";
        }
    }
}
