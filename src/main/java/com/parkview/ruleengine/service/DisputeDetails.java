package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.Fine;

/**
 * A dispute together with what a reader needs to understand it: the fine it is about and the street address of its zone
 * (null when the zone is unknown or has none).
 */
public record DisputeDetails(Dispute dispute, Fine fine, String zoneAddress) {
}
