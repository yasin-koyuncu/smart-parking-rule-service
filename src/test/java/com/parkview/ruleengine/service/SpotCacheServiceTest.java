package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.SpotDirectory;
import com.parkview.ruleengine.domain.ParkingSpot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SpotCacheServiceTest {

    private static final String ZONE = "zone-1";

    private final SpotDirectory directory = mock(SpotDirectory.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T08:00:00Z"));
    private final ParkingSpot legacySpot = TestFixtures.spot("paid", null, 0, 0);
    private final ParkingSpot viewSpot = TestFixtures.spot("permit", "resident", 0, 0);
    private final UUID viewId = UUID.randomUUID();
    private SpotCacheService cache;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        cache = new SpotCacheService(directory, TestFixtures.properties(), clock);
        when(directory.polygonSpotsOf(ZONE)).thenReturn(List.of(legacySpot));
        when(directory.polygonSpotsOfView(ZONE, viewId)).thenReturn(List.of(viewSpot));
    }

    @Test
    void legacySpotsAndTheSpotsOfAViewAreCachedApartForTheSameZone() {
        assertThat(cache.spotsOf(ZONE)).containsExactly(legacySpot);
        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot);
        assertThat(cache.spotsOf(ZONE, null)).containsExactly(legacySpot);
        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot);

        verify(directory, times(1)).polygonSpotsOf(ZONE);
        verify(directory, times(1)).polygonSpotsOfView(ZONE, viewId);
        verifyNoMoreInteractions(directory);
    }

    @Test
    void everyViewHasItsOwnEntry() {
        UUID other = UUID.randomUUID();
        ParkingSpot otherSpot = TestFixtures.spot("disabled", null, 0, 0);
        when(directory.polygonSpotsOfView(ZONE, other)).thenReturn(List.of(otherSpot));

        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot);
        assertThat(cache.spotsOf(ZONE, other)).containsExactly(otherSpot);
    }

    @Test
    void entriesExpireAfterTheTtlAndPickUpChanges() {
        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot);
        ParkingSpot added = TestFixtures.spot("ev", null, 0, 0);
        when(directory.polygonSpotsOfView(ZONE, viewId)).thenReturn(List.of(viewSpot, added));

        now.set(now.get().plus(TestFixtures.properties().spotCacheTtl()).minusSeconds(1));
        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot);
        now.set(now.get().plus(Duration.ofSeconds(2)));

        assertThat(cache.spotsOf(ZONE, viewId)).containsExactly(viewSpot, added);
    }

    @Test
    void aViewWithoutSpotsIsCachedToo() {
        UUID empty = UUID.randomUUID();
        when(directory.polygonSpotsOfView(ZONE, empty)).thenReturn(List.of());

        assertThat(cache.spotsOf(ZONE, empty)).isEmpty();
        assertThat(cache.spotsOf(ZONE, empty)).isEmpty();

        verify(directory, times(1)).polygonSpotsOfView(ZONE, empty);
    }

    @Test
    void theSpotNumberIsReadThroughForAnyKindOfSpot() {
        when(directory.spotNumberOf(viewSpot.id())).thenReturn(Optional.of("B7"));

        assertThat(cache.spotNumberOf(viewSpot.id())).contains("B7");
    }
}
