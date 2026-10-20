package com.parkview.ruleengine.client;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether {@code parking_spots} already has the camera-view columns ({@code view_id}, {@code image_polygon}) that
 * parking-mapping-service adds. A database without them is a legacy database: it has no views, and queries that name
 * the columns would fail. Asked each time the spot cache reloads a zone, so a migration is noticed without a restart.
 */
@Component
@RequiredArgsConstructor
class SpotViewSchema {

    private static final String PROBE = """
            select count(*) from information_schema.columns
            where table_name = 'parking_spots' and table_schema = any (current_schemas(false))
              and column_name in ('view_id', 'image_polygon')""";

    private final JdbcTemplate jdbc;

    boolean present() {
        Integer found = jdbc.queryForObject(PROBE, Integer.class);
        return found != null && found == 2;
    }
}
