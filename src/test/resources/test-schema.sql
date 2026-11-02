-- What the infrastructure provides in a real environment (ADR 0010): the schemas of the services, the search_path of the role that runs
-- rule-service (its own schema first, public for the extensions), and the published read views of the other owners. rule-service's
-- Liquibase creates the content of schema `rules` only.
create schema if not exists rules;
create schema if not exists mapping;
create schema if not exists sessions;
create schema if not exists identity;
create schema if not exists ingestion;
alter role current_user set search_path = rules, public;
-- the PostgREST role of ingestion-service: the grants of the published views of rule-service (rules.v_fines, rules.v_fine_evidence) go to it
do $$
begin
  if not exists (select from pg_roles where rolname = 'service_role') then
    create role service_role nologin;
  end if;
end
$$;

-- Base tables of the other services, shaped like the real ones: they have more columns than rule-service maps, which must not matter.
-- rule-service reads them only through the views `v_*` below and never writes them; the tests insert into the base tables.
create table mapping.sites (
  id             text primary key,
  name           text not null default 'site',
  address        text not null default '',
  country        text not null default 'SE',
  currency       text not null default 'SEK',
  timezone       text not null default 'Europe/Stockholm',
  lat            double precision,
  lng            double precision,
  kind           text not null default 'public',
  price_per_hour integer,
  max_minutes    integer,
  allows_parking boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create table mapping.zones (
  id             text primary key,
  site_id        text,
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
create table mapping.parking_spots (
  id                 uuid primary key default gen_random_uuid(),
  site_id            text not null,
  zone_id            text,
  type               text not null,
  shape              text not null,
  coordinates        jsonb,                -- null for a spot that only has a geo polygon
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
  updated_at         timestamptz not null default now(),
  view_id            uuid,                 -- camera view the spot is drawn on, null for a legacy spot (raw camera pixels in coordinates)
  image_polygon      jsonb                 -- polygon in the corrected image of the view, [[x,y],...]
);
-- time / text[] / int[] columns plus columns rule-service does not map
create table mapping.zone_rules (
  id               uuid primary key default gen_random_uuid(),
  site_id          text not null,
  zone_id          text,                 -- null: a site rule
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
create table identity.user_plates (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null,
  plate      text not null unique,
  label      text,
  created_at timestamptz not null default now()
);
create table sessions.sessions (
  id             uuid primary key default gen_random_uuid(),
  plate          text not null,
  site_id        text not null,
  zone_id        text,
  camera_id      text,
  spot_id        uuid,
  started_at     timestamptz not null default now(),
  ended_at       timestamptz,
  voided_at      timestamptz,
  cost_sek       numeric(10,2),
  user_id        uuid,
  payment_status text not null default 'UNPAID',
  created_at     timestamptz not null default now()
);
create table ingestion.detections (
  id               uuid primary key default gen_random_uuid(),
  plate            text not null,
  camera_id        text not null default 'cam-1',
  site_id          text not null,
  zone_id          text,
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

-- The catalogue views (ADR 0010, section 4): the table's columns, as published by their owners.
create view mapping.v_sites as select * from mapping.sites;
create view mapping.v_zones as select * from mapping.zones;
create view mapping.v_zone_rules as select * from mapping.zone_rules;
create view mapping.v_parking_spots as select * from mapping.parking_spots;
create view identity.v_user_plates as select id, user_id, plate from identity.user_plates;
create view sessions.v_sessions as select * from sessions.sessions;
create view ingestion.v_detections as
select id, plate, corrected_plate, site_id, zone_id, image_path, confidence, review_status, at
  from ingestion.detections
 where review_status <> 'rejected';

-- Test convenience only, not part of any service: the tests insert rows of the other owners' tables naming just the zone. A row without
-- a site gets the site of its zone, else the zone id as site id (a site with the zone's id).
create or replace function mapping.zones_fill_site_id() returns trigger language plpgsql as $$
begin
  if new.site_id is null then
    insert into mapping.sites (id, name, address) values (new.id, coalesce(new.name, new.id), coalesce(new.address, ''))
    on conflict (id) do nothing;
    new.site_id := new.id;
  end if;
  return new;
end
$$;
create trigger trg_zones_fill_site_id before insert on mapping.zones for each row execute function mapping.zones_fill_site_id();

create or replace function mapping.test_fill_site_id() returns trigger language plpgsql as $$
begin
  if new.site_id is null and new.zone_id is not null then
    select z.site_id into new.site_id from mapping.zones z where z.id = new.zone_id;
    new.site_id := coalesce(new.site_id, new.zone_id);
  end if;
  return new;
end
$$;
create trigger trg_parking_spots_fill_site_id before insert on mapping.parking_spots for each row execute function mapping.test_fill_site_id();
create trigger trg_zone_rules_fill_site_id before insert on mapping.zone_rules for each row execute function mapping.test_fill_site_id();
create trigger trg_sessions_fill_site_id before insert on sessions.sessions for each row execute function mapping.test_fill_site_id();
create trigger trg_detections_fill_site_id before insert on ingestion.detections for each row execute function mapping.test_fill_site_id();
