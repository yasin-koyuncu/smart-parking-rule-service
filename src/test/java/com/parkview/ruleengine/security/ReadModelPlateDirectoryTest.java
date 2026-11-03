package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.ruleengine.config.AccessProperties;
import com.parkview.ruleengine.readmodel.PlateOwnerView;
import com.parkview.ruleengine.readmodel.PlateOwnerViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadModelPlateDirectoryTest {

    private static final UUID USER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");

    private final PlateOwnerViewRepository repository = mock(PlateOwnerViewRepository.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final ReadModelPlateDirectory directory =
            new ReadModelPlateDirectory(repository, new AccessProperties(Duration.ofSeconds(30), 100, Duration.ofSeconds(30), 100), ticker);

    private static PlateOwnerView view(String plate) {
        PlateOwnerView view = newView();
        ReflectionTestUtils.setField(view, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(view, "userId", USER);
        ReflectionTestUtils.setField(view, "plate", plate);
        return view;
    }

    private static PlateOwnerView newView() {
        return org.springframework.beans.BeanUtils.instantiateClass(PlateOwnerView.class);
    }

    @Test
    void returnsTheNormalisedPlatesOfTheUser() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("abc-123"), view("XYZ 999")));

        assertThat(directory.platesOf(USER.toString())).containsExactlyInAnyOrder("ABC123", "XYZ999");
    }

    @Test
    void anIdThatIsNoUserIdOrNothingHasNoPlatesAndIsNotQueried() {
        assertThat(directory.platesOf("not-a-uuid")).isEmpty();
        assertThat(directory.platesOf(null)).isEmpty();

        verify(repository, never()).findByUserId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anUnknownUserHasNoPlates() {
        when(repository.findByUserId(USER)).thenReturn(List.of());

        assertThat(directory.platesOf(USER.toString())).isEmpty();
    }

    @Test
    void anEntryIsServedFromTheCacheUntilItsTtlIsOver() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("ABC123")));

        directory.platesOf(USER.toString());
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(directory.platesOf(USER.toString())).containsExactly("ABC123");
        verify(repository, times(1)).findByUserId(USER);

        // a plate added meanwhile is visible once the entry has expired
        when(repository.findByUserId(USER)).thenReturn(List.of(view("ABC123"), view("NEW111")));
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(directory.platesOf(USER.toString())).containsExactlyInAnyOrder("ABC123", "NEW111");
        verify(repository, times(2)).findByUserId(USER);
    }
}
