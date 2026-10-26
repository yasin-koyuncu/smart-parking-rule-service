package com.parkview.ruleengine.service.cache;

import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.client.ZoneRuleDirectory;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.domain.value.ZoneRule;
import com.parkview.ruleengine.service.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleCacheServiceTest {

    private static final String ZONE = "zone-1";

    private final ZoneRuleDirectory ruleDirectory = mock(ZoneRuleDirectory.class);
    private final ZoneDirectory zoneDirectory = mock(ZoneDirectory.class);
    private final SiteDirectory siteDirectory = mock(SiteDirectory.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T08:00:00Z"));
    private RuleCacheService cache;

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
        cache = new RuleCacheService(ruleDirectory, zoneDirectory, siteDirectory, TestFixtures.properties(), clock);
    }

    @Test
    void rulesAreLoadedOnceAndReloadedAfterTheTtl() {
        ZoneRule before = new ZoneRule(UUID.randomUUID(), ZONE, "old", 1, List.of(), null, Set.of(1), LocalTime.MIDNIGHT,
                LocalTime.of(23, 59), true, 30, false);
        ZoneRule after = new ZoneRule(UUID.randomUUID(), ZONE, "new", 1, List.of(), null, Set.of(1), LocalTime.MIDNIGHT,
                LocalTime.of(23, 59), true, 60, false);
        when(ruleDirectory.enabledRulesOf(ZONE)).thenReturn(List.of(before)).thenReturn(List.of(after));

        assertThat(cache.rulesOf(ZONE, ZONE)).containsExactly(before);
        now.set(now.get().plus(Duration.ofSeconds(59)));
        assertThat(cache.rulesOf(ZONE, ZONE)).containsExactly(before);
        verify(ruleDirectory, times(1)).enabledRulesOf(ZONE);

        now.set(now.get().plus(Duration.ofSeconds(2)));
        assertThat(cache.rulesOf(ZONE, ZONE)).containsExactly(after);
        verify(ruleDirectory, times(2)).enabledRulesOf(ZONE);
    }

    @Test
    void zonesAreCachedAndDefaultedWhenUnknown() {
        ZoneInfo oslo = new ZoneInfo("Storgatan 1", 60, "NO", ZoneId.of("Europe/Oslo"));
        when(zoneDirectory.infoOf(ZONE)).thenReturn(Optional.of(oslo));
        when(zoneDirectory.infoOf("ghost")).thenReturn(Optional.empty());

        assertThat(cache.zoneOf(ZONE)).isEqualTo(oslo);
        assertThat(cache.zoneOf(ZONE)).isEqualTo(oslo);
        assertThat(cache.zoneOf("ghost")).isEqualTo(ZoneInfo.unknown());
        assertThat(cache.zoneOf("ghost")).isEqualTo(ZoneInfo.unknown());

        verify(zoneDirectory, times(1)).infoOf(ZONE);
        verify(zoneDirectory, times(1)).infoOf("ghost");
    }

    @Test
    void aZoneWithoutRulesIsCachedToo() {
        when(ruleDirectory.enabledRulesOf(ZONE)).thenReturn(List.of());

        assertThat(cache.rulesOf(ZONE, ZONE)).isEmpty();
        assertThat(cache.rulesOf(ZONE, ZONE)).isEmpty();

        verify(ruleDirectory, times(1)).enabledRulesOf(ZONE);
    }
}
