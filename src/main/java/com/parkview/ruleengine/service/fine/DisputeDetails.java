package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.Fine;

/**
 * A dispute together with what a reader needs to understand it: the fine it is about and the street addresses of its zone
 * (null without a zone, or when the zone is unknown or has none) and of its site (null when unknown or it has none).
 */
public record DisputeDetails(Dispute dispute, Fine fine, String zoneAddress, String siteAddress) {
}
