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
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Service-to-service authentication for {@code /internal/**} and the non-health actuator endpoints: the caller presents a secret
 * in {@code X-Internal-Api-Key}. The own key of the service (or, during a rotation, the previous one) grants {@code ROLE_INTERNAL},
 * the ops key grants {@code ROLE_OPS}; all comparisons take constant time and an unset key never matches. Requests without
 * (or with a wrong) key simply stay anonymous and are rejected by the filter chain.
 * The gateway never routes {@code /internal/**}, so these endpoints are only reachable
 * from inside the cluster network.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL";
    public static final String OPS_AUTHORITY = "ROLE_OPS";

    private final byte[] internalKey;
    private final byte[] previousKey;
    private final byte[] opsKey;

    public InternalApiKeyFilter(String internalKey, String previousKey, String opsKey) {
        this.internalKey = bytesOf(internalKey);
        this.previousKey = bytesOf(previousKey);
        this.opsKey = bytesOf(opsKey);
    }

    private static byte[] bytesOf(String key) {
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
        String presented = request.getHeader(HEADER);
        if (presented != null) {
            byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
            // non-short-circuit: every comparison runs, whatever the first one says
            boolean internal = matches(internalKey, bytes) | matches(previousKey, bytes);
            boolean ops = matches(opsKey, bytes);
            if (internal || ops) {
                SecurityContextHolder.getContext().setAuthentication(new InternalAuthentication(internal, ops));
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(byte[] key, byte[] presented) {
        return key != null && MessageDigest.isEqual(key, presented);
    }

    static final class InternalAuthentication extends AbstractAuthenticationToken {
        InternalAuthentication(boolean internal, boolean ops) {
            super(AuthorityUtils.createAuthorityList(Stream.of(internal ? AUTHORITY : null, ops ? OPS_AUTHORITY : null)
                    .filter(Objects::nonNull).toArray(String[]::new)));
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
