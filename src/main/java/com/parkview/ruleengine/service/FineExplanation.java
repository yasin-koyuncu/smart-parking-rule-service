package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;

/**
 * The facts behind a fine as the driver may see them.
 *
 * @param evidence      null for fines issued before evidence was recorded
 * @param zoneAddress   from the evidence, else the zone as it is now
 * @param disputeStatus status of the latest dispute, null when there never was one
 */
public record FineExplanation(Fine fine, FineEvidence evidence, String zoneAddress, DisputeStatus disputeStatus) {
}
