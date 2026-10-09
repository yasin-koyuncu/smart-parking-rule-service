package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.controller.internal.InternalFineController;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.InternalApiKeyFilter;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.FineService;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.INTERNAL_KEY;
import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalFineController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + INTERNAL_KEY})
class InternalFineControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FineService fineService;

    private Fine fine() {
        Instant t = Instant.parse("2026-10-09T10:00:00Z");
        Violation v = Violation.open(UUID.randomUUID(), "z1", "ABC123", null, ViolationType.WRONG_PERMIT, t, t);
        Fine fine = Fine.issue(v, new BigDecimal("700"), UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11"), t);
        ReflectionTestUtils.setField(fine, "id", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        return fine;
    }

    @Test
    void returnsTheFineForTheInternalKey() throws Exception {
        Fine fine = fine();
        when(fineService.get(fine.getId())).thenReturn(fine);

        mvc.perform(get("/internal/v1/fines/{id}", fine.getId()).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.plate").value("ABC123"))
                .andExpect(jsonPath("$.userId").value("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11"))
                .andExpect(jsonPath("$.zoneId").value("z1"))
                .andExpect(jsonPath("$.amountSek").value(700.0))
                .andExpect(jsonPath("$.currency").value("SEK"))
                .andExpect(jsonPath("$.paid").value(false));
    }

    @Test
    void rejectsMissingOrWrongKeyAndUserTokens() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/internal/v1/fines/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/fines/{id}", id).header(InternalApiKeyFilter.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/fines/{id}", id).with(user("admin", List.of(), List.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownFineIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(fineService.get(id)).thenThrow(new ResourceNotFoundException("Fine", id));

        mvc.perform(get("/internal/v1/fines/{id}", id).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
