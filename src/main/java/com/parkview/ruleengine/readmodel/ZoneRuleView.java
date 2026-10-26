package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Read-only view of {@code zone_rules}, the rules operators edit in the operator UI. The table is owned by the
 * Supabase schema / parking-mapping-service. A row with a zone is a zone rule, a row without one a site rule (ADR 0009).
 * {@code start_time} and {@code end_time} are {@code time} columns,
 * {@code vehicle_types} is {@code text[]} and {@code weekdays} {@code int[]}.
 *
 * <p>The two time columns are read as text ({@code HH:mm:ss}, or {@code HH:mm} while a database still has text
 * columns) and parsed by the adapter: mapping a {@code time} column to {@code LocalTime} goes through
 * {@code java.sql.Time}, which together with {@code hibernate.jdbc.time_zone=UTC} shifts the value by the UTC offset of
 * the JVM default zone (22:00 became 23:00 on a Stockholm host).
 * Maps just the columns used (not the price); never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "zone_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneRuleView {

    @Id
    private UUID id;

    /** The site the rule belongs to (ADR 0009); never null. */
    @Column(name = "site_id")
    private String siteId;

    /** The zone of a zone rule; null for a site rule, which applies in the whole site, inside its zones too. */
    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "label")
    private String label;

    @Column(name = "priority")
    private int priority;

    @Column(name = "enabled")
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "vehicle_types", columnDefinition = "text[]")
    private String[] vehicleTypes;

    @Column(name = "permit_required")
    private String permitRequired;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "weekdays", columnDefinition = "integer[]")
    private Integer[] weekdays;

    @Formula("cast(start_time as text)")
    private String startTime;

    @Formula("cast(end_time as text)")
    private String endTime;

    @Column(name = "applies_holidays")
    private boolean appliesHolidays;

    @Column(name = "max_minutes")
    private Integer maxMinutes;

    @Column(name = "no_parking")
    private boolean noParking;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ZoneRuleView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return ZoneRuleView.class.hashCode();
    }
}
