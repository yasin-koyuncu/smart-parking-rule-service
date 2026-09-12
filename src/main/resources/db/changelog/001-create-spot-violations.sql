-- rule-engine/src/main/resources/db/changelog/001-create-spot-violations.sql
-- liquibase formatted sql

-- changeset rule-engine:001-spot-violations
create table if not exists public.spot_violations (
  id               uuid primary key default gen_random_uuid(),
  spot_id          uuid not null,
  zone_id          text not null,
  plate            text not null,
  user_id          uuid,
  violation_type   text not null check (violation_type in ('wrong_permit','boundary_exceeded','overstay','no_parking')),
  detected_at      timestamptz not null default now(),
  grace_until      timestamptz not null,
  resolved_at      timestamptz,
  fine_issued_at   timestamptz,
  fine_id          uuid,
  image_path       text,
  notified_at      timestamptz,
  created_at       timestamptz not null default now()
);

create index if not exists spot_violations_plate_idx    on public.spot_violations (plate);
create index if not exists spot_violations_zone_idx     on public.spot_violations (zone_id);
create index if not exists spot_violations_active_idx   on public.spot_violations (grace_until)
  where resolved_at is null and fine_issued_at is null;

-- rollback drop table if exists public.spot_violations;