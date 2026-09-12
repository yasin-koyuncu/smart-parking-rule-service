package com.parkview.ruleengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Distribuerat lås via Redis.
 * Förhindrar att samma violation skapas flera gånger
 * om flera instanser av rule-engine körs parallellt.
 *
 * Använder SET NX EX (atomic) — standardmönster för Redis-lås.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final StringRedisTemplate redis;

    private static final String PREFIX = "lock:violation:";
    private static final Duration TTL  = Duration.ofMinutes(5);

    /**
     * Kör action om låset kan tas.
     * Returnerar true om action kördes, false om låset redan togs.
     */
    public boolean tryWithLock(String plate, String violationType, Runnable action) {
        String key = PREFIX + plate + ":" + violationType;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "locked", TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Lås redan taget för {}: {}", plate, violationType);
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            // Lås tas bort av TTL eller explicit här
            // Vi låter TTL hålla låset för att förhindra omedelbar omcreation
        }
    }

    /**
     * Frigör låset manuellt (t.ex. när violation lösts).
     */
    public void releaseLock(String plate, String violationType) {
        redis.delete(PREFIX + plate + ":" + violationType);
    }

    /**
     * Kontrollerar om ett lås finns utan att ta det.
     */
    public boolean isLocked(String plate, String violationType) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + plate + ":" + violationType));
    }
}