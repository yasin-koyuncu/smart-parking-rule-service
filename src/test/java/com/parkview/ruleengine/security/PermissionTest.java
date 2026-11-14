package com.parkview.ruleengine.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing the claim is tested in parkview-platform ({@code PermissionClaimsTest}); here only this service's catalogue. */
class PermissionTest {

    @Test
    void theCatalogueHoldsTheAccessPermissions() {
        assertThat(Permission.ALL).contains("zone:write", "access:manage", "role:manage");
    }
}
