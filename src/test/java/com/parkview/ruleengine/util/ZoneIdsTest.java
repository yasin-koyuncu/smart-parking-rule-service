package com.parkview.ruleengine.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneIdsTest {

    @Test
    void blankAndMissingBecomeNullAndOthersAreTrimmed() {
        assertThat(ZoneIds.normalize(null)).isNull();
        assertThat(ZoneIds.normalize("")).isNull();
        assertThat(ZoneIds.normalize("   ")).isNull();
        assertThat(ZoneIds.normalize("  z1 ")).isEqualTo("z1");
    }
}
