-- Tables owned by other services (Supabase / parking-mapping-service) in the minimal shape rule-service reads.
create table if not exists zones (id text primary key, address text);
create table if not exists user_plates (
  id      uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  plate   text not null unique
);
create table if not exists parking_spots (
  id                 uuid primary key default gen_random_uuid(),
  zone_id            text not null,
  type               text not null,
  shape              text not null,
  coordinates        jsonb not null,
  spot_number        text,
  permit_type        text,
  boundary_grace_min integer default 10,
  permit_grace_min   integer default 30
);
