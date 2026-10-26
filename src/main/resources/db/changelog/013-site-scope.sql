-- liquibase formatted sql

-- ADR 0009, phases 2-4: the site is the place, the zone an optional part of it with its own rules. Violations, fines,
-- disputes and the frozen evidence belong to a site (`site_id NOT NULL`, no foreign key: `sites` is the table of another
-- service) and only optionally to a zone (`zone_id` nullable). Permits get an optional site: site_id null = every site,
-- zone_id null = the whole site. Additive and idempotent like the earlier changesets: columns first, then the fill trigger
-- for writers that do not set site_id yet (an older instance during a rolling deploy, other producers of `fines`), then the
-- backfill, then the constraints. The trigger stays until phase 5.

-- changeset rule-engine:013-site-scope-columns
alter table public.spot_violations add column if not exists site_id text;
alter table public.fines           add column if not exists site_id text;
alter table public.fine_disputes   add column if not exists site_id text;
alter table public.fine_evidence   add column if not exists site_id text;
alter table public.fine_evidence   add column if not exists site_address text;
alter table public.plate_permits   add column if not exists site_id text;

-- changeset rule-engine:013-site-scope-fill-function splitStatements:false
-- site_id from the zone of the row: the zone's site (not simply the zone id: a new zone inside an existing site has its own
-- id). A zone that is not there (deleted, or a database before mapping-service phase 1) falls back to the zone id, which is the
-- id of its site for every zone that existed before sites did.
create or replace function public.rule_fill_site_id() returns trigger language plpgsql as $$
begin
  if new.site_id is null and new.zone_id is not null then
    begin
      select z.site_id into new.site_id from public.zones z where z.id = new.zone_id;
    exception when undefined_column or undefined_table then
      new.site_id := null;
    end;
    new.site_id := coalesce(new.site_id, new.zone_id);
  end if;
  return new;
end
$$;

-- changeset rule-engine:013-site-scope-fill-triggers
drop trigger if exists trg_spot_violations_fill_site_id on public.spot_violations;
create trigger trg_spot_violations_fill_site_id
  before insert on public.spot_violations
  for each row execute function public.rule_fill_site_id();

drop trigger if exists trg_fines_fill_site_id on public.fines;
create trigger trg_fines_fill_site_id
  before insert on public.fines
  for each row execute function public.rule_fill_site_id();

drop trigger if exists trg_fine_disputes_fill_site_id on public.fine_disputes;
create trigger trg_fine_disputes_fill_site_id
  before insert on public.fine_disputes
  for each row execute function public.rule_fill_site_id();

drop trigger if exists trg_fine_evidence_fill_site_id on public.fine_evidence;
create trigger trg_fine_evidence_fill_site_id
  before insert on public.fine_evidence
  for each row execute function public.rule_fill_site_id();

drop trigger if exists trg_plate_permits_fill_site_id on public.plate_permits;
create trigger trg_plate_permits_fill_site_id
  before insert on public.plate_permits
  for each row execute function public.rule_fill_site_id();

-- changeset rule-engine:013-site-scope-backfill splitStatements:false
-- The same rule as the trigger. The zone lookup is skipped on a database whose zones have no site_id yet; then the zone id is
-- the site id. fine_evidence is immutable (009): its guard is switched off for this one update and on again, in the same
-- transaction; the frozen facts are not touched, only the new column is filled.
do $$
declare
  zones_have_sites boolean := exists (
    select 1 from information_schema.columns
    where table_name = 'zones' and table_schema = 'public' and column_name = 'site_id');
begin
  if zones_have_sites then
    update public.spot_violations t set site_id = coalesce((select z.site_id from public.zones z where z.id = t.zone_id), t.zone_id)
     where t.site_id is null;
    update public.fines t set site_id = coalesce((select z.site_id from public.zones z where z.id = t.zone_id), t.zone_id)
     where t.site_id is null;
    update public.fine_disputes t set site_id = coalesce((select z.site_id from public.zones z where z.id = t.zone_id), t.zone_id)
     where t.site_id is null;
    alter table public.fine_evidence disable trigger fine_evidence_immutable;
    update public.fine_evidence t set site_id = coalesce((select z.site_id from public.zones z where z.id = t.zone_id), t.zone_id)
     where t.site_id is null;
    alter table public.fine_evidence enable trigger fine_evidence_immutable;
    update public.plate_permits t set site_id = coalesce((select z.site_id from public.zones z where z.id = t.zone_id), t.zone_id)
     where t.site_id is null and t.zone_id is not null;
  else
    update public.spot_violations set site_id = zone_id where site_id is null;
    update public.fines set site_id = zone_id where site_id is null;
    update public.fine_disputes set site_id = zone_id where site_id is null;
    alter table public.fine_evidence disable trigger fine_evidence_immutable;
    update public.fine_evidence set site_id = zone_id where site_id is null;
    alter table public.fine_evidence enable trigger fine_evidence_immutable;
    update public.plate_permits set site_id = zone_id where site_id is null and zone_id is not null;
  end if;
end
$$;

-- changeset rule-engine:013-site-scope-not-null
-- Every row has a site now; plate_permits.site_id stays nullable (null = every site).
alter table public.spot_violations alter column site_id set not null;
alter table public.fines           alter column site_id set not null;
alter table public.fine_disputes   alter column site_id set not null;
alter table public.fine_evidence   alter column site_id set not null;

-- changeset rule-engine:013-site-scope-zone-nullable
-- A spot without a zone is still controlled with the rules of its site, so its violations, fines and disputes have no zone.
alter table public.spot_violations alter column zone_id drop not null;
alter table public.fines           alter column zone_id drop not null;
alter table public.fine_disputes   alter column zone_id drop not null;
alter table public.fine_evidence   alter column zone_id drop not null;

-- changeset rule-engine:013-site-scope-indexes
-- The read API lists per site like it does per zone (see 004, 007, 010); the scope of an operator is a list of sites.
create index if not exists spot_violations_site_active_idx
  on public.spot_violations (site_id, detected_at desc)
  where resolved_at is null and fine_issued_at is null;
create index if not exists spot_violations_plate_site_idx on public.spot_violations (plate, site_id);
create index if not exists fines_site_issued_idx          on public.fines (site_id, issued_at desc);
create index if not exists fine_disputes_site_opened_idx  on public.fine_disputes (site_id, opened_at desc);
create index if not exists plate_permits_site_idx         on public.plate_permits (site_id);

-- rollback drop index if exists public.plate_permits_site_idx;
-- rollback drop index if exists public.fine_disputes_site_opened_idx;
-- rollback drop index if exists public.fines_site_issued_idx;
-- rollback drop index if exists public.spot_violations_plate_site_idx;
-- rollback drop index if exists public.spot_violations_site_active_idx;
-- rollback drop trigger if exists trg_spot_violations_fill_site_id on public.spot_violations;
-- rollback drop trigger if exists trg_fines_fill_site_id on public.fines;
-- rollback drop trigger if exists trg_fine_disputes_fill_site_id on public.fine_disputes;
-- rollback drop trigger if exists trg_fine_evidence_fill_site_id on public.fine_evidence;
-- rollback drop trigger if exists trg_plate_permits_fill_site_id on public.plate_permits;
-- rollback drop function if exists public.rule_fill_site_id();
-- rollback alter table public.spot_violations drop column if exists site_id;
-- rollback alter table public.fines drop column if exists site_id;
-- rollback alter table public.fine_disputes drop column if exists site_id;
-- rollback alter table public.plate_permits drop column if exists site_id;
-- rollback alter table public.fine_evidence drop column if exists site_id;
-- rollback alter table public.fine_evidence drop column if exists site_address;
