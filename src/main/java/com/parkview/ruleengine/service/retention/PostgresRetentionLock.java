package com.parkview.ruleengine.service.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A Postgres session-level advisory lock on a connection of its own, held for the whole run (the batches use other connections and
 * transactions). It is released explicitly, and by the database should the connection or the instance die, so a crashed run never blocks the
 * next one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresRetentionLock implements RetentionLock {

    /** Unique per service in the shared database: "PVRULE" in ASCII (session-service uses "PVSESS", notification-service "PVNOTF"). */
    public static final long KEY = 0x505652554C45L;

    private final DataSource dataSource;

    @Override
    public boolean runIfFree(Runnable action) {
        try (Connection connection = dataSource.getConnection()) {
            if (!query(connection, "select pg_try_advisory_lock(?)")) {
                return false;
            }
            try {
                action.run();
                return true;
            } finally {
                release(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take the retention lock: " + e.getSQLState(), e);
        }
    }

    private void release(Connection connection) {
        try {
            if (!query(connection, "select pg_advisory_unlock(?)")) {
                log.warn("Retention lock was not held when it was released");
            }
        } catch (SQLException e) {
            // Never hand a connection that may still hold the lock back to the pool: drop it, the database then frees the lock.
            log.warn("Could not release the retention lock, dropping its connection: {}", e.getSQLState());
            try {
                connection.abort(Runnable::run);
            } catch (SQLException ignored) {
                log.warn("Could not drop the connection of the retention lock");
            }
        }
    }

    private static boolean query(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
