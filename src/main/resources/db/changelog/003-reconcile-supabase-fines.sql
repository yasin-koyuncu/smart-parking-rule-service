-- rule-service/src/main/resources/db/changelog/003-reconcile-supabase-fines.sql
-- liquibase formatted sql

-- changeset rule-engine:003-reconcile-supabase-fines
-- rule-service writes to `fines` (Fine entity) but does not own its creation:
-- on the real database the table comes from the Supabase migrations (older
-- shape), on a fresh one from session-service. Make sure it exists and matches
-- what Fine expects; every statement is a no-op on a table that already does.
create table if not exists public.fines (
  id             uuid primary key default gen_random_uuid(),
  plate          text not null,
  zone_id        text not null,
  camera_id      text,
  reason         text not null,
  reason_code    text,
  reason_text    text,
  amount_sek     numeric(10,2) not null,
  issued_at      timestamptz not null default now(),
  paid           boolean not null default false,
  user_id        uuid,
  image_path     text,
  created_at     timestamptz not null default now()
);
alter table public.fines add column if not exists reason_code text;
alter table public.fines add column if not exists reason_text text;
alter table public.fines add column if not exists image_path  text;
alter table public.fines add column if not exists user_id     uuid;
alter table public.fines add column if not exists created_at  timestamptz not null default now();
alter table public.fines alter column amount_sek type numeric(10,2);
alter table public.fines alter column camera_id drop not null;
alter table public.fines drop constraint if exists fines_reason_check;
alter table public.fines add constraint fines_reason_check
  check (reason in ('no_parking','overstay','wrong_permit','boundary_exceeded'));
