package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.type.ViolationType;

/**
 * The verdict of the rule engine for one observation: the violation and, in plain language, the rule that decided it
 * (kept with the violation and frozen into the evidence of the fine).
 */
public record Finding(ViolationType type, String ruleSummary) {
}
