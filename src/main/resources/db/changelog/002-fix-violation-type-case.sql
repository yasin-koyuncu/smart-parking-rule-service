-- rule-engine/src/main/resources/db/changelog/002-fix-violation-type-case.sql
-- liquibase formatted sql

-- changeset rule-engine:002-fix-violation-type-case
-- Violation.violationType använder @Enumerated(EnumType.STRING), vilket
-- Hibernate serialiserar som exakt enum-namn (versaler: BOUNDARY_EXCEEDED,
-- WRONG_PERMIT, OVERSTAY, NO_PARKING) — men ursprungs-constraint:en tillät
-- bara gemener. Varje verklig violation-insert misslyckades med
-- "violates check constraint spot_violations_violation_type_check" tills
-- detta upptäcktes i ett helhetstest mot en riktig databas.
alter table public.spot_violations drop constraint if exists spot_violations_violation_type_check;
alter table public.spot_violations add constraint spot_violations_violation_type_check
  check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING'));
