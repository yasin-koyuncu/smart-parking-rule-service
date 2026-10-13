package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.HolidayCalendar;
import com.parkview.ruleengine.client.VehicleDirectory;
import com.parkview.ruleengine.domain.Fuel;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.VehicleInfo;
import com.parkview.ruleengine.domain.ZoneInfo;
import com.parkview.ruleengine.domain.ZoneRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleResolverTest {

    // Monday 2026-10-12 10:00 Stockholm (CEST)
    private static final Instant MONDAY_10 = Instant.parse("2026-10-12T08:00:00Z");
    private static final ZoneInfo ZONE = ZoneInfo.unknown();
    private static final String PLATE = "ABC123";
    private static final String ID_A = "00000000-0000-0000-0000-00000000000a";
    private static final String ID_B = "00000000-0000-0000-0000-00000000000b";

    private final HolidayCalendar holidays = mock(HolidayCalendar.class);
    private final VehicleDirectory directory = mock(VehicleDirectory.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RuleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RuleResolver(holidays, new EngineMetrics(registry));
        when(directory.lookup(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private VehicleContext vehicle(VehicleClass detected) {
        return new VehicleContext(PLATE, detected, "SE", directory, true);
    }

    private RuleOutcome resolve(VehicleContext vehicle, ZoneRule... rules) {
        return resolver.resolve(List.of(rules), ZONE, MONDAY_10, vehicle);
    }

    @Test
    void noRulesMeansNoOutcome() {
        assertThat(resolve(vehicle(VehicleClass.CAR))).isEqualTo(RuleOutcome.NONE);
    }

    @Test
    void aRuleWithoutAnyOutcomeDecidesNothing() {
        assertThat(resolve(vehicle(VehicleClass.CAR), Rules.rule().build())).isEqualTo(RuleOutcome.NONE);
    }

    @Test
    void theLowerPriorityNumberIsEvaluatedFirst() {
        ZoneRule strict = Rules.rule().priority(10).maxMinutes(30).build();
        ZoneRule relaxed = Rules.rule().priority(20).maxMinutes(120).build();

        assertThat(resolve(vehicle(VehicleClass.CAR), relaxed, strict).maxMinutes()).isEqualTo(30);
    }

    @Test
    void equalPrioritiesAreOrderedById() {
        ZoneRule a = Rules.rule().id(ID_A).priority(50).maxMinutes(15).build();
        ZoneRule b = Rules.rule().id(ID_B).priority(50).maxMinutes(90).build();

        assertThat(resolve(vehicle(VehicleClass.CAR), b, a).maxMinutes()).isEqualTo(15);
    }

    @Test
    void firstMatchWinsPerCategoryAndLowerRulesCannotOverrideIt() {
        ZoneRule first = Rules.rule().priority(1).maxMinutes(30).build();
        ZoneRule second = Rules.rule().priority(2).maxMinutes(240).permit("resident").build();
        ZoneRule third = Rules.rule().priority(3).permit("disabled").noParking().build();

        RuleOutcome outcome = resolve(vehicle(VehicleClass.CAR), third, second, first);

        assertThat(outcome.maxMinutes()).isEqualTo(30);
        assertThat(outcome.permitRequired()).isEqualTo("resident");
        assertThat(outcome.noParking()).isTrue();
    }

    @Test
    void aRuleThatDoesNotApplyNowDoesNotBlockLowerRules() {
        ZoneRule evening = Rules.rule().priority(1).window("18:00", "22:00").maxMinutes(10).build();
        ZoneRule always = Rules.rule().priority(2).maxMinutes(60).build();

        assertThat(resolve(vehicle(VehicleClass.CAR), evening, always).maxMinutes()).isEqualTo(60);
    }

    @Test
    void weekdayAndWindowAreChecked() {
        ZoneRule weekend = Rules.rule().weekdays(6, 7).noParking().build();
        ZoneRule lunch = Rules.rule().window("11:00", "13:00").permit("resident").build();

        assertThat(resolve(vehicle(VehicleClass.CAR), weekend, lunch)).isEqualTo(RuleOutcome.NONE);
    }

    @Test
    void aMaxMinutesOfZeroOrLessIsNotALimit() {
        assertThat(resolve(vehicle(VehicleClass.CAR), Rules.rule().maxMinutes(0).build())).isEqualTo(RuleOutcome.NONE);
    }

    @Test
    void aHolidayExcludesRulesThatDoNotApplyOnHolidays() {
        when(holidays.isHoliday("SE", LocalDate.of(2026, 10, 12))).thenReturn(true);
        ZoneRule workdaysOnly = Rules.rule().holidays(false).noParking().build();
        ZoneRule everyDay = Rules.rule().holidays(true).permit("resident").build();

        RuleOutcome outcome = resolve(vehicle(VehicleClass.CAR), workdaysOnly, everyDay);

        assertThat(outcome.noParking()).isFalse();
        assertThat(outcome.permitRequired()).isEqualTo("resident");
    }

    @Test
    void theHolidayCalendarIsAskedForTheZonesCountry() {
        ZoneInfo norway = new ZoneInfo(null, 0, "NO", ZONE.timezone());

        resolver.resolve(List.of(Rules.rule().holidays(false).noParking().build()), norway, MONDAY_10, vehicle(VehicleClass.CAR));

        verify(holidays).isHoliday("NO", LocalDate.of(2026, 10, 12));
    }

    @Test
    void theHolidayCalendarIsNotAskedForRulesThatApplyOnHolidays() {
        resolve(vehicle(VehicleClass.CAR), Rules.rule().holidays(true).noParking().build());

        verifyNoInteractions(holidays);
    }

    @Test
    void theVehicleFilterMatchesTheDetectorClassCode() {
        ZoneRule trucksOnly = Rules.rule().vehicles("truck", "bus").noParking().build();

        assertThat(resolve(vehicle(VehicleClass.TRUCK), trucksOnly).noParking()).isTrue();
        assertThat(resolve(vehicle(VehicleClass.BUS), trucksOnly).noParking()).isTrue();
        assertThat(resolve(vehicle(VehicleClass.CAR), trucksOnly).noParking()).isFalse();
        assertThat(resolve(vehicle(VehicleClass.MOTORCYCLE), Rules.rule().vehicles("mc").noParking().build()).noParking()).isTrue();
        verifyNoInteractions(directory);
    }

    @Test
    void aFilterNeedsTheRegistryWhenTheDetectorClassIsUnknown() {
        when(directory.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(VehicleClass.TRUCK, Fuel.OTHER)));
        ZoneRule trucksOnly = Rules.rule().vehicles("truck").noParking().build();

        assertThat(resolve(vehicle(null), trucksOnly).noParking()).isTrue();
    }

    @Test
    void anUnknownVehicleNeverMatchesAFilter() {
        ZoneRule trucksOnly = Rules.rule().vehicles("truck").noParking().build();

        assertThat(resolve(vehicle(null), trucksOnly).noParking()).isFalse();
        verify(directory).lookup(PLATE, "SE");
    }

    @Test
    void aRegistryTypeThatIsNotRecognisedIsAnUnknownVehicle() {
        when(directory.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(null, Fuel.OTHER)));

        assertThat(resolve(vehicle(null), Rules.rule().vehicles("car").noParking().build()).noParking()).isFalse();
    }

    @Test
    void theEvCodeMatchesKnownElectricVehiclesOfAnyBodyType() {
        when(directory.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(VehicleClass.CAR, Fuel.ELECTRIC)));
        ZoneRule evOnly = Rules.rule().vehicles("ev").permit("ev").build();

        assertThat(resolve(vehicle(VehicleClass.BUS), evOnly).permitRequired()).isEqualTo("ev");
    }

    @Test
    void theEvCodeDoesNotMatchHybridsOrUnknownDrives() {
        ZoneRule evOnly = Rules.rule().vehicles("ev").permit("ev").build();

        when(directory.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(VehicleClass.CAR, Fuel.OTHER)));
        assertThat(resolve(vehicle(VehicleClass.CAR), evOnly).permitRequired()).isNull();

        when(directory.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(VehicleClass.CAR, Fuel.UNKNOWN)));
        assertThat(resolve(new VehicleContext(PLATE, VehicleClass.CAR, "SE", directory, true), evOnly).permitRequired()).isNull();
    }

    @Test
    void aBodyCodeAlongsideEvMatchesWithoutALookup() {
        ZoneRule carsOrEvs = Rules.rule().vehicles("car", "ev").noParking().build();

        assertThat(resolve(vehicle(VehicleClass.CAR), carsOrEvs).noParking()).isTrue();
        verifyNoInteractions(directory);
    }

    @Test
    void theRegistryIsAskedAtMostOncePerObservation() {
        ZoneRule first = Rules.rule().priority(1).vehicles("truck").noParking().build();
        ZoneRule second = Rules.rule().priority(2).vehicles("bus").permit("resident").build();
        ZoneRule third = Rules.rule().priority(3).vehicles("ev").maxMinutes(10).build();

        resolve(vehicle(null), first, second, third);

        verify(directory, times(1)).lookup(PLATE, "SE");
    }

    @Test
    void noLookupHappensForRulesWithoutAFilterOrWhoseCategoryIsAlreadyDecided() {
        ZoneRule decided = Rules.rule().priority(1).noParking().build();
        ZoneRule shadowed = Rules.rule().priority(2).vehicles("truck").noParking().build();

        assertThat(resolve(vehicle(null), decided, shadowed).noParking()).isTrue();
        verifyNoInteractions(directory);
    }

    @Test
    void noLookupHappensForRulesOutsideTheirWindow() {
        ZoneRule night = Rules.rule().window("22:00", "06:00").vehicles("truck").noParking().build();

        resolve(vehicle(null), night);

        verifyNoInteractions(directory);
    }

    @Test
    void noLookupHappensWhenTheSwitchIsOffOrThePlateIsUnknown() {
        ZoneRule trucksOnly = Rules.rule().vehicles("truck").noParking().build();

        assertThat(resolve(new VehicleContext(PLATE, null, "SE", directory, false), trucksOnly).noParking()).isFalse();
        assertThat(resolve(new VehicleContext(null, null, "SE", directory, true), trucksOnly).noParking()).isFalse();
        verify(directory, never()).lookup(any(), any());
    }

    @Test
    void matchedRulesAreCountedPerOutcomeCategory() {
        resolve(vehicle(VehicleClass.CAR), Rules.rule().noParking().maxMinutes(30).build());

        assertThat(registry.counter("ruleengine.rules.matched", "outcome", "no_parking").count()).isEqualTo(1.0);
        assertThat(registry.counter("ruleengine.rules.matched", "outcome", "max_minutes").count()).isEqualTo(1.0);
        assertThat(registry.counter("ruleengine.rules.matched", "outcome", "permit").count()).isZero();
    }
}
