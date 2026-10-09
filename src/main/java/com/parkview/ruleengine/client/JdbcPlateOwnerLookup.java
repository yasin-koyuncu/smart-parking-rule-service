package com.parkview.ruleengine.client;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class JdbcPlateOwnerLookup implements PlateOwnerLookup {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<UUID> ownerOf(String normalizedPlate) {
        return jdbc.query("select user_id from user_plates where plate = ? limit 1",
                        (rs, i) -> rs.getObject("user_id", UUID.class), normalizedPlate)
                .stream().findFirst();
    }
}
