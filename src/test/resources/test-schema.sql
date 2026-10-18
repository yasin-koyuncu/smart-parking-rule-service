-- Tables owned by other services (Supabase / parking-mapping-service), shaped like the real ones: they have more
-- columns than rule-service maps, which must not matter. Liquibase of rule-service never creates them.
create table if not exists zones (
  id             text primary key,
  name           text not null default 'zone',
  address        text,
  price_per_hour integer not null default 0,
  max_minutes    integer not null default 0,
  allows_parking boolean not null default true,
  country        text not null default 'SE',
  currency       text not null default 'SEK',
  timezone       text not null default 'Europe/Stockholm',
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
-- zone_rules as created by the Supabase migration: time / text[] / int[] columns plus columns rule-service does not map.
create table if not exists zone_rules (
  id               uuid primary key default gen_random_uuid(),
  zone_id          text not null,
  label            text not null,
  priority         int not null default 100,
  enabled          boolean not null default true,
  vehicle_types    text[] not null default '{}'::text[],
  permit_required  text,
  weekdays         int[] not null default '{1,2,3,4,5,6,7}'::int[],
  start_time       time not null default '00:00',
  end_time         time not null default '23:59',
  applies_holidays boolean not null default true,
  max_minutes      int,
  price_per_hour   int,
  no_parking       boolean not null default false,
  note             text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
-- sessions as owned by session-service (the Supabase base table has no spot_id; session-service adds it).
create table if not exists sessions (
  id             uuid primary key default gen_random_uuid(),
  plate          text not null,
  zone_id        text not null,
  camera_id      text,
  spot_id        uuid,
  started_at     timestamptz not null default now(),
  ended_at       timestamptz,
  cost_sek       numeric(10,2),
  user_id        uuid,
  payment_status text not null default 'UNPAID',
  created_at     timestamptz not null default now()
);
-- detections as owned by ingestion-service (Supabase base table plus the later columns), with more columns than rule-service maps.
create table if not exists detections (
  id               uuid primary key default gen_random_uuid(),
  plate            text not null,
  camera_id        text not null default 'cam-1',
  zone_id          text not null,
  outcome          text not null default 'detected',
  at               timestamptz not null default now(),
  user_id          uuid,
  image_path       text,
  confidence       numeric,
  review_status    text not null default 'auto',
  reviewed_by      uuid,
  reviewed_at      timestamptz,
  corrected_plate  text,
  source           text
);
