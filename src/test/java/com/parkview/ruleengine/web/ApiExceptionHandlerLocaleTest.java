package com.parkview.ruleengine.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The language of an error text is the one of {@code Accept-Language}: Swedish for sv, English when the header is missing
 * or the language is unknown. No server and no database: the handler on its own, with the real bundles.
 */
class ApiExceptionHandlerLocaleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN_EN = "A review of this fine has already been requested and is still open.";

    @RestController
    static class Throwing {

        @GetMapping("/test/coded")
        String coded() {
            throw ConflictException.coded("dispute.alreadyOpen", OPEN_EN);
        }

        @GetMapping("/test/unknown-code")
        String unknownCode() {
            throw BusinessRuleException.coded("test.noSuchEntry", "The English text stays when there is no entry");
        }

        @GetMapping("/test/plain")
        String plain() {
            throw new ConflictException("An uncoded message stays as it is");
        }

        @GetMapping("/test/notfound/{id}")
        String notFound(@PathVariable String id) {
            throw new ResourceNotFoundException("Fine", id);
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        mvc = MockMvcBuilders.standaloneSetup(new Throwing())
                .setControllerAdvice(new ApiExceptionHandler(new ErrorMessagesConfig().errorMessages()))
                .setLocaleResolver(resolver)
                .build();
    }

    private static JsonNode body(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void swedishWhenTheCallerAsksForSwedish() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/coded").header("Accept-Language", "sv")).andReturn());

        assertThat(problem.get("detail").asText()).isEqualTo("En granskning av boten har redan begärts och är fortfarande öppen.");
        assertThat(problem.get("message").asText()).isEqualTo(problem.get("detail").asText());
        assertThat(problem.get("title").asText()).isEqualTo("Konflikt");
        assertThat(problem.get("code").asText()).isEqualTo("dispute.alreadyOpen");
        assertThat(problem.get("status").asInt()).isEqualTo(409);
    }

    @Test
    void aRegionalSwedishTagAndAWeightedListAlsoGiveSwedish() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/coded").header("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8")).andReturn());

        assertThat(problem.get("title").asText()).isEqualTo("Konflikt");
    }

    @Test
    void englishWithoutAHeader() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/coded")).andReturn());

        assertThat(problem.get("detail").asText()).isEqualTo(OPEN_EN);
        assertThat(problem.get("title").asText()).isEqualTo("Conflict");
        assertThat(problem.get("code").asText()).isEqualTo("dispute.alreadyOpen");
    }

    @Test
    void englishForALanguageWithoutABundle() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/coded").header("Accept-Language", "fr")).andReturn());

        assertThat(problem.get("detail").asText()).isEqualTo(OPEN_EN);
        assertThat(problem.get("title").asText()).isEqualTo("Conflict");
    }

    @Test
    void aCodeWithoutAnEntryKeepsTheEnglishMessageAndStillCarriesTheCode() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/unknown-code").header("Accept-Language", "sv")).andReturn());

        assertThat(problem.get("detail").asText()).isEqualTo("The English text stays when there is no entry");
        assertThat(problem.get("title").asText()).isEqualTo("Affärsregel bruten");
        assertThat(problem.get("code").asText()).isEqualTo("test.noSuchEntry");
    }

    @Test
    void anUncodedExceptionKeepsItsMessageAndHasNoCode() throws Exception {
        JsonNode problem = body(mvc.perform(get("/test/plain").header("Accept-Language", "sv")).andReturn());

        assertThat(problem.get("detail").asText()).isEqualTo("An uncoded message stays as it is");
        assertThat(problem.has("code")).isFalse();
    }

    @Test
    void notFoundTakesTheIdAsAParameter() throws Exception {
        JsonNode swedish = body(mvc.perform(get("/test/notfound/abc-1").header("Accept-Language", "sv")).andReturn());
        JsonNode english = body(mvc.perform(get("/test/notfound/abc-1")).andReturn());

        assertThat(swedish.get("detail").asText()).isEqualTo("Boten hittades inte: abc-1");
        assertThat(swedish.get("code").asText()).isEqualTo("notFound.fine");
        assertThat(english.get("detail").asText()).isEqualTo("Fine not found: abc-1");
        assertThat(english.get("status").asInt()).isEqualTo(404);
    }
}
