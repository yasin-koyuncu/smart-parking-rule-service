-- Tables owned by other services (Supabase / parking-mapping-service), shaped like the real ones: they have more
-- columns than rule-service maps, which must not matter. Liquibase of rule-service never creates them.
create table if not exists zones (
  id             text primary key,
  name           text not null default 'zone',
  address        text,
  price_per_hour integer not null default 0,
  max_minutes    integer not null default 0,
  allows_parking boolean not null default true,
  created_at     timestamptz not null default now()
);
create table if not exists user_plates (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null,
  plate      text not null unique,
  label      text,
  created_at timestamptz not null default now()
);
create table if not exists parking_spots (
  id                 uuid primary key default gen_random_uuid(),
  zone_id            text not null,
  type               text not null,
  shape              text not null,
  coordinates        jsonb not null,
  spot_number        text,
  label              text,
  max_minutes        integer,
  permit_type        text,
  price_per_hour     numeric(10,2),
  boundary_grace_min integer default 10,
  permit_grace_min   integer default 30,
  source             text default 'manual',
  ai_confidence      numeric(4,3),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
