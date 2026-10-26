package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of {@code sites}, which is owned by parking-mapping-service (ADR 0009). A site is the place: it owns the
 * spots and, outside its zones, its own rules and tariff. rule-service needs the street address for the events, and the stay
 * limit, whether parking is allowed, country and timezone to evaluate the site rules at a spot without a zone.
 * {@code price_per_hour} null means free, {@code max_minutes} null no limit. Maps just the columns used; never written by
 * this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "sites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SiteView {

    @Id
    private String id;

    @Column(name = "address")
    private String address;

    @Column(name = "country")
    private String country;

    @Column(name = "currency")
    private String currency;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "price_per_hour")
    private Integer pricePerHour;

    @Column(name = "max_minutes")
    private Integer maxMinutes;

    @Column(name = "allows_parking")
    private boolean allowsParking;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SiteView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return SiteView.class.hashCode();
    }
}
