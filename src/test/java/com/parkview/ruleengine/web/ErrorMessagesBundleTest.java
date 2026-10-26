package com.parkview.ruleengine.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The bundles of the API error texts stay in step: every English key has a Swedish text with the same parameters. */
class ErrorMessagesBundleTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");

    private static Properties load(String name) throws IOException {
        try (InputStream in = ErrorMessagesBundleTest.class.getResourceAsStream(name)) {
            assertThat(in).as(name + " is on the classpath").isNotNull();
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    @Test
    void everyBaseKeyHasASwedishTextAndNothingIsBlank() throws IOException {
        Properties en = load("/messages/errors.properties");
        Properties sv = load("/messages/errors_sv.properties");

        assertThat(en.stringPropertyNames()).isNotEmpty();
        assertThat(sv.stringPropertyNames()).containsAll(en.stringPropertyNames());
        // no stale Swedish key left behind when a code is removed or renamed
        assertThat(en.stringPropertyNames()).containsAll(sv.stringPropertyNames());
        for (String key : en.stringPropertyNames()) {
            assertThat(en.getProperty(key)).as("en " + key).isNotBlank();
            assertThat(sv.getProperty(key)).as("sv " + key).isNotBlank();
        }
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws IOException {
        Properties en = load("/messages/errors.properties");
        Properties sv = load("/messages/errors_sv.properties");

        for (String key : en.stringPropertyNames()) {
            assertThat(placeholders(sv.getProperty(key))).as("placeholders of " + key)
                    .isEqualTo(placeholders(en.getProperty(key)));
        }
    }

    @Test
    void theCommonKeysOfTheKitAreThere() throws IOException {
        Properties en = load("/messages/errors.properties");

        assertThat(en.stringPropertyNames()).contains(
                "title.notFound", "title.conflict", "title.businessRule", "title.forbidden", "title.unauthorized",
                "title.dataConflict", "title.validation.params", "title.validation.body", "title.internal",
                "error.forbidden", "error.unauthorized", "error.dataConflict", "error.validation.params",
                "error.validation.body", "error.internal");
    }
}
