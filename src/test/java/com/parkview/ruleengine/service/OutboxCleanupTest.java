package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.OutboxProperties;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OutboxCleanupTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    private OutboxCleanup cleanup(boolean enabled) {
        OutboxProperties properties = new OutboxProperties(enabled, Duration.ofMillis(500), 10, Duration.ofSeconds(5),
                Duration.ofSeconds(60), Duration.ofDays(14), false);
        return new OutboxCleanup(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void removesPublishedRowsOlderThanTheRetention() {
        cleanup(true).purge();

        verify(repository).deletePublishedBefore(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void aDisabledRelayLeavesTheTableAlone() {
        cleanup(false).purge();

        verifyNoInteractions(repository);
    }
}
