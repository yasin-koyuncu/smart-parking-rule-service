-- liquibase formatted sql

-- changeset rule-engine:005-plate-permits
-- Permits that allow a plate to park in permit spots. zone_id null = valid in every zone.
-- Row level security is enabled without policies: the table must not be readable or writable
-- through Supabase PostgREST; the service connects as the table owner.
create table if not exists public.plate_permits (
  id           uuid primary key default gen_random_uuid(),
  plate        text not null,
  permit_type  text not null,
  zone_id      text,
  valid_from   timestamptz not null default now(),
  valid_to     timestamptz,
  created_by   text not null,
  created_at   timestamptz not null default now(),
  constraint plate_permits_valid_range check (valid_to is null or valid_to > valid_from)
);

create index if not exists plate_permits_plate_idx on public.plate_permits (plate);
create index if not exists plate_permits_zone_idx  on public.plate_permits (zone_id);

alter table public.plate_permits enable row level security;

-- rollback drop table if exists public.plate_permits;
