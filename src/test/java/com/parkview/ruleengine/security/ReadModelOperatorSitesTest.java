package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.ruleengine.config.AccessProperties;
import com.parkview.ruleengine.readmodel.UserSiteView;
import com.parkview.ruleengine.readmodel.UserSiteViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadModelOperatorSitesTest {

    private static final UUID USER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");

    private final UserSiteViewRepository repository = mock(UserSiteViewRepository.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final ReadModelOperatorSites directory = new ReadModelOperatorSites(repository,
            new AccessProperties(Duration.ofSeconds(30), 100, Duration.ofSeconds(30), 100), ticker);

    private static UserSiteView view(String site) {
        UserSiteView view = BeanUtils.instantiateClass(UserSiteView.class);
        ReflectionTestUtils.setField(view, "userId", USER);
        ReflectionTestUtils.setField(view, "siteId", site);
        return view;
    }

    @Test
    void returnsTheSitesOfTheUser() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("s1"), view("s2")));

        assertThat(directory.sitesOf(USER.toString())).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void anIdThatIsNoUserIdOrNothingHasNoSitesAndIsNotQueried() {
        assertThat(directory.sitesOf("not-a-uuid")).isEmpty();
        assertThat(directory.sitesOf(null)).isEmpty();

        verify(repository, never()).findByUserId(any());
    }

    @Test
    void anUnknownUserHasNoSites() {
        when(repository.findByUserId(USER)).thenReturn(List.of());

        assertThat(directory.sitesOf(USER.toString())).isEmpty();
    }

    @Test
    void anEntryIsServedFromTheCacheUntilItsTtlIsOver() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("s1")));

        directory.sitesOf(USER.toString());
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(directory.sitesOf(USER.toString())).containsExactly("s1");
        verify(repository, times(1)).findByUserId(USER);

        // a site removed and one added meanwhile show once the entry has expired
        when(repository.findByUserId(USER)).thenReturn(List.of(view("s2")));
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(directory.sitesOf(USER.toString())).containsExactly("s2");
        verify(repository, times(2)).findByUserId(USER);
    }

    @Test
    void aDatabaseErrorPropagatesInsteadOfAnEmptySet() {
        when(repository.findByUserId(USER)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> directory.sitesOf(USER.toString())).isInstanceOf(IllegalStateException.class);
    }
}
