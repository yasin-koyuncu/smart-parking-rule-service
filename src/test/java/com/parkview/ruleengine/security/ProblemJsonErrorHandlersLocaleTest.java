package com.parkview.ruleengine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 401/403 written by the security chain (outside MVC) use the language of Accept-Language too, English otherwise. */
class ProblemJsonErrorHandlersLocaleTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final MessageSource messages = bundles();

    private static MessageSource bundles() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages/errors");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private JsonNode forbidden(String acceptLanguage) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemJsonErrorHandlers.accessDeniedHandler(mapper, messages).handle(request, response, new AccessDeniedException("no"));
        assertThat(response.getStatus()).isEqualTo(403);
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void forbiddenInSwedish() throws Exception {
        JsonNode problem = forbidden("sv");

        assertThat(problem.get("title").asText()).isEqualTo("Inte tillåtet");
        assertThat(problem.get("detail").asText()).isEqualTo("Du har inte behörighet att göra detta");
        assertThat(problem.get("code").asText()).isEqualTo("error.forbidden");
    }

    @Test
    void forbiddenInEnglishWithoutHeaderAndForUnknownOrMalformedLanguages() throws Exception {
        for (String header : new String[] {null, "", "fr", "*", "not a language!!"}) {
            JsonNode problem = forbidden(header);
            assertThat(problem.get("title").asText()).as("title for " + header).isEqualTo("Forbidden");
            assertThat(problem.get("detail").asText()).isEqualTo("You are not allowed to perform this action");
            assertThat(problem.get("code").asText()).isEqualTo("error.forbidden");
        }
    }

    @Test
    void unauthorizedInSwedishKeepsTheBearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemJsonErrorHandlers.authenticationEntryPoint(mapper, messages)
                .commence(request, response, new BadCredentialsException("no token"));

        JsonNode problem = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(problem.get("title").asText()).isEqualTo("Inte inloggad");
        assertThat(problem.get("detail").asText()).isEqualTo("Inloggning krävs");
        assertThat(problem.get("code").asText()).isEqualTo("error.unauthorized");
    }

    @Test
    void theMostPreferredLanguageWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en;q=0.5,sv;q=0.9");

        assertThat(ProblemJsonErrorHandlers.localeOf(request).getLanguage()).isEqualTo("sv");
        assertThat(ProblemJsonErrorHandlers.localeOf(new MockHttpServletRequest())).isEqualTo(Locale.ENGLISH);
    }
}
