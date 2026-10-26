package com.parkview.ruleengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.web.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Locale;

/**
 * 401/403 produced inside the security filter chain (before MVC) use the same
 * problem+json body as everything else. The bearer entry point still sets the
 * {@code WWW-Authenticate} header.
 *
 * <p>The texts are looked up in {@code messages/errors[_lang].properties} in the language of the request's
 * {@code Accept-Language} header, like the errors of the controllers (English when it is missing or unknown), and the
 * problem carries the stable {@code code} ({@code error.unauthorized}, {@code error.forbidden}).
 */
public final class ProblemJsonErrorHandlers {

    private ProblemJsonErrorHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper, MessageSource messages) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) -> {
            bearer.commence(request, response, ex);
            write(mapper, response, problem(messages, request, HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Authentication is required"));
        };
    }

    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper, MessageSource messages) {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) ->
                write(mapper, response, problem(messages, request, HttpStatus.FORBIDDEN, "forbidden",
                        "Forbidden", "You are not allowed to perform this action"));
    }

    private static ProblemDetail problem(MessageSource messages, HttpServletRequest request, HttpStatus status, String key,
                                         String title, String detail) {
        Locale locale = localeOf(request);
        ProblemDetail pd = ApiExceptionHandler.problem(status,
                messages.getMessage("title." + key, null, title, locale),
                messages.getMessage("error." + key, null, detail, locale));
        pd.setProperty("code", "error." + key);
        return pd;
    }

    /**
     * The most preferred language of {@code Accept-Language}; English when the header is missing, malformed or only
     * {@code *}. Never the language of the server: a language without a bundle falls back to the English base.
     */
    static Locale localeOf(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return Locale.ENGLISH;
        }
        try {
            Locale.LanguageRange best = null;
            for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
                if (!"*".equals(range.getRange()) && range.getWeight() > 0
                        && (best == null || range.getWeight() > best.getWeight())) {
                    best = range;
                }
            }
            return best == null ? Locale.ENGLISH : Locale.forLanguageTag(best.getRange());
        } catch (IllegalArgumentException malformed) {
            return Locale.ENGLISH;
        }
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, ProblemDetail pd) throws IOException {
        response.setStatus(pd.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), pd);
    }
}
