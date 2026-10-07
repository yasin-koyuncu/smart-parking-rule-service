package com.parkview.ruleengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Slår upp ägaren (user_id) för en plåt och en zons adress, för att kunna
 * berika violation-/fine-events med det notification-service behöver för
 * att faktiskt skicka en notis (den ignorerar events utan userId).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactLookupService {

    private final JdbcTemplate jdbc;

    public UUID ownerOfPlate(String plate) {
        try {
            return jdbc.queryForObject(
                    "select user_id from user_plates where plate = ?",
                    (rs, i) -> UUID.fromString(rs.getString("user_id")),
                    plate
            );
        } catch (Exception e) {
            log.debug("Ingen ägare hittad för plate {}: {}", plate, e.getMessage());
            return null;
        }
    }

    public String zoneAddress(String zoneId) {
        try {
            return jdbc.queryForObject(
                    "select address from zones where id = ?", String.class, zoneId
            );
        } catch (Exception e) {
            log.debug("Kunde inte slå upp adress för zon {}: {}", zoneId, e.getMessage());
            return null;
        }
    }
}
