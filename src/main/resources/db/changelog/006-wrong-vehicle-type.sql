-- liquibase formatted sql

-- changeset rule-engine:006-wrong-vehicle-type
-- New violation type WRONG_VEHICLE_TYPE (e.g. a combustion car in an EV spot). Both tables pre-exist (Supabase /
-- earlier changesets), so the CHECK constraints are dropped if present and re-created with the widened value list.
-- spot_violations.violation_type is upper-case, fines.reason is lower-case.
alter table public.spot_violations drop constraint if exists spot_violations_violation_type_check;
alter table public.spot_violations add constraint spot_violations_violation_type_check
  check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING','WRONG_VEHICLE_TYPE'));

alter table public.fines drop constraint if exists fines_reason_check;
alter table public.fines add constraint fines_reason_check
  check (reason in ('no_parking','overstay','wrong_permit','boundary_exceeded','wrong_vehicle_type'));
