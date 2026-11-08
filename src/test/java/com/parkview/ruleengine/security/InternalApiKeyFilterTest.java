package com.parkview.ruleengine.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which key becomes which authority: own and previous key ROLE_INTERNAL, ops key ROLE_OPS, an unset key never matches. */
class InternalApiKeyFilterTest {

    private final FilterChain chain = (request, response) -> {
    };

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private List<String> authoritiesFor(InternalApiKeyFilter filter, String path, String header) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (header != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, header);
        }
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? List.of()
                : authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void theOwnAndThePreviousKeyAreInternalAndTheOpsKeyIsOps() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("own", "old", "ops");

        assertThat(authoritiesFor(filter, "/internal/v1/x", "own")).containsExactly("ROLE_INTERNAL");
        assertThat(authoritiesFor(filter, "/internal/v1/x", "old")).containsExactly("ROLE_INTERNAL");
        assertThat(authoritiesFor(filter, "/actuator/prometheus", "ops")).containsExactly("ROLE_OPS");
    }

    @Test
    void aWrongOrMissingKeyStaysAnonymous() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("own", "old", "ops");

        assertThat(authoritiesFor(filter, "/internal/v1/x", "other")).isEmpty();
        assertThat(authoritiesFor(filter, "/internal/v1/x", "")).isEmpty();
        assertThat(authoritiesFor(filter, "/internal/v1/x", "OWN")).isEmpty();
        assertThat(authoritiesFor(filter, "/internal/v1/x", null)).isEmpty();
    }

    @Test
    void anUnsetKeyNeverMatches() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("own", null, "");

        assertThat(authoritiesFor(filter, "/internal/v1/x", "")).isEmpty();
        assertThat(authoritiesFor(filter, "/internal/v1/x", " ")).isEmpty();
        assertThat(authoritiesFor(filter, "/actuator/prometheus", "")).isEmpty();
        assertThat(authoritiesFor(filter, "/internal/v1/x", "own")).containsExactly("ROLE_INTERNAL");
    }

    @Test
    void aKeyThatIsBothTheInternalAndTheOpsKeyGrantsOnlyInternal() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("shared", null, "shared");

        assertThat(authoritiesFor(filter, "/internal/v1/x", "shared")).containsExactly("ROLE_INTERNAL");
    }

    @Test
    void otherPathsAreNotFiltered() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("own", "old", "ops");

        assertThat(authoritiesFor(filter, "/api/v1/auth/me", "own")).isEmpty();
    }
}
