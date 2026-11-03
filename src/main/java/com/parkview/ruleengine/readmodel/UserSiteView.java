package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only view of {@code identity.v_user_sites}, the published view of the sites an operator may work at (authentication-service,
 * ADR 0010, 0011). rule-service reads it to resolve the sites of an operator ({@code ReadModelOperatorSites}).
 * Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@IdClass(UserSiteView.Key.class)
@Table(schema = "identity", name = "v_user_sites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSiteView {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "site_id")
    private String siteId;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserSiteView other && userId != null
                && Objects.equals(userId, other.userId) && Objects.equals(siteId, other.siteId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, siteId);
    }

    /** The composite key: a user has a site at most once. */
    public record Key(UUID userId, String siteId) implements Serializable {
    }
}
