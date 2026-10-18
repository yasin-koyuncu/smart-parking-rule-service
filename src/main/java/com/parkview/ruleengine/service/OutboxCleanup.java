package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.OutboxProperties;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Hourly removal of published outbox rows older than the retention period. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanup {

    private final OutboxEventRepository outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void purge() {
        if (!properties.relayEnabled()) {
            return;
        }
        int rows = outbox.deletePublishedBefore(clock.instant().minus(properties.retention()));
        if (rows > 0) {
            log.info("Purged {} published outbox rows", rows);
        }
    }
}
