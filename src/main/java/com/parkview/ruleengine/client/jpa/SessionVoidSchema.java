package com.parkview.ruleengine.client.jpa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether {@code sessions} already has the {@code voided_at} column that session-service adds (an operator can void a
 * session). A database without it has no voided sessions, and a query that names the column would fail every observation
 * that needs the active session. Once the column is seen it is there for good; while it is missing the probe is repeated
 * at most once a minute, so a migration is noticed without a restart and without a catalog query per observation.
 */
@Component
@RequiredArgsConstructor
class SessionVoidSchema {

    private static final Duration RECHECK = Duration.ofMinutes(1);
    private static final String PROBE = """
            select count(*) from information_schema.columns
            where table_name = 'sessions' and table_schema = any (current_schemas(false)) and column_name = 'voided_at'""";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    private volatile boolean present;
    private volatile Instant checkedAt = Instant.MIN;

    boolean present() {
        if (present) {
            return true;
        }
        Instant now = clock.instant();
        if (Duration.between(checkedAt, now).compareTo(RECHECK) >= 0) {
            Integer found = jdbc.queryForObject(PROBE, Integer.class);
            present = found != null && found > 0;
            checkedAt = now;
        }
        return present;
    }
}
