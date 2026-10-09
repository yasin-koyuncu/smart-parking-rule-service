package com.parkview.ruleengine.client;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
class JdbcZoneDirectory implements ZoneDirectory {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<String> addressOf(String zoneId) {
        return jdbc.query("select address from zones where id = ?", (rs, i) -> rs.getString("address"), zoneId)
                .stream().filter(a -> a != null && !a.isBlank()).findFirst();
    }
}
