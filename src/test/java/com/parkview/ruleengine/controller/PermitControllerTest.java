package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.request.CreatePermitRequest;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.PermitService;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PermitController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class PermitControllerTest {

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");
    private static final String BODY = "{\"plate\":\"abc 123\",\"permitType\":\"resident\",\"zoneId\":\"z1\"}";
    private static final String GLOBAL_BODY = "{\"plate\":\"abc 123\",\"permitType\":\"resident\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PermitService permitService;

    private PlatePermit zonePermit;
    private PlatePermit globalPermit;

    @BeforeEach
    void setUp() {
        zonePermit = permit("z1");
        globalPermit = permit(null);
        when(permitService.grant(any(CreatePermitRequest.class), anyString())).thenAnswer(inv -> {
            CreatePermitRequest r = inv.getArgument(0);
            return permit(r.zoneId());
        });
    }

    private static PlatePermit permit(String zoneId) {
        PlatePermit p = PlatePermit.grant("ABC123", "resident", zoneId, T, null, "user-1");
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        return p;
    }

    @Test
    void operatorGrantsPermitInOwnZone() throws Exception {
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/permits/")))
                .andExpect(jsonPath("$.plate").value("ABC123"))
                .andExpect(jsonPath("$.permitType").value("resident"))
                .andExpect(jsonPath("$.zoneId").value("z1"))
                .andExpect(jsonPath("$.createdBy").value("user-1"));

        verify(permitService).grant(any(CreatePermitRequest.class), eq("user-1"));
    }

    @Test
    void operatorCannotGrantForAnotherZoneNorGlobally() throws Exception {
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(GLOBAL_BODY)
                        .with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());

        verify(permitService, never()).grant(any(), anyString());
    }

    @Test
    void adminMayGrantGlobalPermits() throws Exception {
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(GLOBAL_BODY)
                        .with(user("admin", List.of(), List.of())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").doesNotExist());
    }

    @Test
    void driversAndAnonymousCannotManagePermits() throws Exception {
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/permits").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/permits/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBodyIsABadRequestWithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"\",\"permitType\":\"\"}")
                        .with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/api/v1/permits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"AB<script>\",\"permitType\":\"x\",\"zoneId\":\"z1\"}")
                        .with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isBadRequest());
        verify(permitService, never()).grant(any(), anyString());
    }

    @Test
    void operatorListIsRestrictedToOwnZonesAndGlobalPermits() throws Exception {
        when(permitService.search(isNull(), isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(zonePermit, globalPermit)));

        mvc.perform(get("/api/v1/permits").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.length()").value(2));

        verify(permitService).search(isNull(), isNull(), eq(List.of("z1")), any());
    }

    @Test
    void adminListIsUnrestricted() throws Exception {
        when(permitService.search(eq("ABC123"), isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of(zonePermit)));

        mvc.perform(get("/api/v1/permits?plate=ABC123").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());

        verify(permitService).search(eq("ABC123"), isNull(), isNull(), any());
    }

    @Test
    void listingAForeignZoneIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/permits?zoneId=z9").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getChecksTheZoneButGlobalPermitsAreReadable() throws Exception {
        when(permitService.get(zonePermit.getId())).thenReturn(zonePermit);
        when(permitService.get(globalPermit.getId())).thenReturn(globalPermit);

        mvc.perform(get("/api/v1/permits/{id}", zonePermit.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/permits/{id}", zonePermit.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/permits/{id}", globalPermit.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isOk());
    }

    @Test
    void deleteRevokesWithZonePolicyAnd404ForUnknown() throws Exception {
        when(permitService.get(zonePermit.getId())).thenReturn(zonePermit);
        when(permitService.get(globalPermit.getId())).thenReturn(globalPermit);
        UUID unknown = UUID.randomUUID();
        when(permitService.get(unknown)).thenThrow(new ResourceNotFoundException("Permit", unknown));

        mvc.perform(delete("/api/v1/permits/{id}", zonePermit.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isNoContent());
        verify(permitService).revoke(zonePermit.getId());

        mvc.perform(delete("/api/v1/permits/{id}", zonePermit.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/permits/{id}", globalPermit.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/permits/{id}", globalPermit.getId()).with(user("admin", List.of(), List.of())))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/permits/{id}", unknown).with(user("admin", List.of(), List.of())))
                .andExpect(status().isNotFound());
    }
}
