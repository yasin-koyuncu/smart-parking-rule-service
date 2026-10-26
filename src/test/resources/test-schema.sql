-- Tables owned by other services (Supabase / parking-mapping-service), shaped like the real ones: they have more
-- columns than rule-service maps, which must not matter. Liquibase of rule-service never creates them.
-- ADR 0009 (sites): every place-bound table has site_id next to an optional zone_id, and the owners' fill triggers set site_id
-- from the zone for writers that do not (site = the zone's site, else the zone id), so rows inserted the old way still have one.
create table if not exists sites (
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
create table if not exists zones (
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
create table if not exists user_plates (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null,
  plate      text not null unique,
  label      text,
  created_at timestamptz not null default now()
);
create table if not exists parking_spots (
  id                 uuid primary key default gen_random_uuid(),
  site_id            text not null,
  zone_id            text,
  type               text not null,
  shape              text not null,
  coordinates        jsonb,                -- null for a spot that only has a geo polygon (parking-mapping-service)
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
  view_id            uuid,                 -- camera_views.id; null for a legacy spot (raw camera pixels in coordinates)
  image_polygon      jsonb                 -- polygon in the corrected image of the view, [[x,y],...]
);
-- Camera views (parking-mapping-service). rule-service does not read this table, only the two parking_spots columns above.
create table if not exists camera_views (
  id                  uuid primary key default gen_random_uuid(),
  camera_id           text not null,
  site_id             text not null,
  zone_id             text,
  label               text not null,
  preset_token        text,
  original_image_key  text not null,
  corrected_image_key text not null,
  image_width         integer not null,
  image_height        integer not null,
  perspective_matrix  jsonb,
  reference_points    jsonb,
  geo_matrix          jsonb,
  active              boolean not null default true,
  calibrated_at       timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
-- zone_rules as created by the Supabase migration: time / text[] / int[] columns plus columns rule-service does not map.
create table if not exists zone_rules (
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
-- sessions as owned by session-service (the Supabase base table has no spot_id; session-service adds it).
create table if not exists sessions (
  id             uuid primary key default gen_random_uuid(),
  plate          text not null,
  site_id        text not null,
  zone_id        text,
  camera_id      text,
  spot_id        uuid,
  started_at     timestamptz not null default now(),
  ended_at       timestamptz,
  voided_at      timestamptz,          -- added by session-service (corrections); rule-service probes for it
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

-- The fill triggers of the owners (mapping-service, session-service, Supabase migrations), see ADR 0009.
create or replace function zones_fill_site_id() returns trigger language plpgsql as $$
begin
  if new.site_id is null then
    insert into sites (id, name, address) values (new.id, coalesce(new.name, new.id), coalesce(new.address, ''))
    on conflict (id) do nothing;
    new.site_id := new.id;
  end if;
  return new;
end
$$;
drop trigger if exists trg_zones_fill_site_id on zones;
create trigger trg_zones_fill_site_id before insert on zones for each row execute function zones_fill_site_id();

create or replace function owner_fill_site_id() returns trigger language plpgsql as $$
begin
  if new.site_id is null and new.zone_id is not null then
    select z.site_id into new.site_id from zones z where z.id = new.zone_id;
    new.site_id := coalesce(new.site_id, new.zone_id);
  end if;
  return new;
end
$$;
drop trigger if exists trg_parking_spots_fill_site_id on parking_spots;
create trigger trg_parking_spots_fill_site_id before insert on parking_spots for each row execute function owner_fill_site_id();
drop trigger if exists trg_camera_views_fill_site_id on camera_views;
create trigger trg_camera_views_fill_site_id before insert on camera_views for each row execute function owner_fill_site_id();
drop trigger if exists trg_zone_rules_fill_site_id on zone_rules;
create trigger trg_zone_rules_fill_site_id before insert on zone_rules for each row execute function owner_fill_site_id();
drop trigger if exists trg_sessions_fill_site_id on sessions;
create trigger trg_sessions_fill_site_id before insert on sessions for each row execute function owner_fill_site_id();
drop trigger if exists trg_detections_fill_site_id on detections;
create trigger trg_detections_fill_site_id before insert on detections for each row execute function owner_fill_site_id();
