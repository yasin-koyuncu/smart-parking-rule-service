package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.ZoneInfo;

import java.util.Optional;

/** Port: site master data owned by parking-mapping-service (ADR 0009). Today a read of {@code sites}. */
public interface SiteDirectory {

    Optional<String> addressOf(String siteId);

    /**
     * The currency of the site (ISO 4217, upper case): the currency of the fines issued there. Empty when the site is unknown or has no
     * valid currency, which is a configuration error of the site (there is no default currency).
     */
    Optional<String> currencyOf(String siteId);

    /**
     * Address, stay limit, parking allowed, country and timezone of the site ({@link ZoneInfo#siteLevel()} true, defaults
     * applied for missing values): the parameters of a spot without a zone. Empty when the site is unknown.
     */
    Optional<ZoneInfo> infoOf(String siteId);
}
