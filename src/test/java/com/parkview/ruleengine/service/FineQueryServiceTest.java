package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class FineQueryServiceTest extends FineServiceTestBase {

    private final FineQueryService service = new FineQueryService(fines, actions);

    @Test
    void getUnknownFineIsNotFound() {
        UUID id = UUID.randomUUID();
        when(fines.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void byPlateLeavesOutVoidedFinesUnlessAskedTo() {
        Pageable page = PageRequest.of(1, 50);
        Page<Fine> visible = new PageImpl<>(List.of());
        Page<Fine> everything = new PageImpl<>(List.of());
        when(fines.findByPlateAndVoidedAtIsNull("ABC123", page)).thenReturn(visible);
        when(fines.findByPlate("ABC123", page)).thenReturn(everything);

        assertThat(service.byPlate("ABC123", false, page)).isSameAs(visible);
        assertThat(service.byPlate("ABC123", true, page)).isSameAs(everything);
    }

    @Test
    void byZoneFiltersOnPaidOnlyWhenAskedToAndLeavesOutVoidedFinesUnlessAskedTo() {
        Pageable page = PageRequest.of(0, 100);
        Page<Fine> all = new PageImpl<>(List.of());
        Page<Fine> allVisible = new PageImpl<>(List.of());
        Page<Fine> paid = new PageImpl<>(List.of());
        Page<Fine> paidVisible = new PageImpl<>(List.of());
        Page<Fine> unpaid = new PageImpl<>(List.of());
        Page<Fine> unpaidVisible = new PageImpl<>(List.of());
        when(fines.findByZoneId("z1", page)).thenReturn(all);
        when(fines.findByZoneIdAndVoidedAtIsNull("z1", page)).thenReturn(allVisible);
        when(fines.findByZoneIdAndPaid("z1", true, page)).thenReturn(paid);
        when(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", true, page)).thenReturn(paidVisible);
        when(fines.findByZoneIdAndPaid("z1", false, page)).thenReturn(unpaid);
        when(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", false, page)).thenReturn(unpaidVisible);

        assertThat(service.byZone("z1", null, true, page)).isSameAs(all);
        assertThat(service.byZone("z1", null, false, page)).isSameAs(allVisible);
        assertThat(service.byZone("z1", true, true, page)).isSameAs(paid);
        assertThat(service.byZone("z1", true, false, page)).isSameAs(paidVisible);
        assertThat(service.byZone("z1", false, true, page)).isSameAs(unpaid);
        assertThat(service.byZone("z1", false, false, page)).isSameAs(unpaidVisible);
    }

    @Test
    void historyDelegatesToTheRepositoryWithTheGivenPage() {
        UUID id = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 10);
        Page<FineAction> result = new PageImpl<>(List.of());
        when(actions.findByFineId(id, page)).thenReturn(result);

        assertThat(service.history(id, page)).isSameAs(result);
    }
}
