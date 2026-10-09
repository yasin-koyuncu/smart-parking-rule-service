package com.parkview.ruleengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.web.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401/403 produced inside the security filter chain (before MVC) use the same
 * problem+json body as everything else. The bearer entry point still sets the
 * {@code WWW-Authenticate} header.
 */
public final class ProblemJsonErrorHandlers {

    private ProblemJsonErrorHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) -> {
            bearer.commence(request, response, ex);
            write(mapper, response, ApiExceptionHandler.problem(
                    HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required"));
        };
    }

    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) ->
                write(mapper, response, ApiExceptionHandler.problem(
                        HttpStatus.FORBIDDEN, "Forbidden", "You are not allowed to perform this action"));
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, ProblemDetail pd) throws IOException {
        response.setStatus(pd.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), pd);
    }
}
