-- liquibase formatted sql

-- changeset rule-engine:004-violation-lifecycle-columns
-- last_seen_at: updated whenever a detection re-confirms the violation; drives the stale sweep and
-- prevents fining a vehicle that has not been seen for a while.
-- resolution_reason: why an open violation was closed without a fine (CORRECTED | LEFT | MANUAL).
alter table public.spot_violations add column if not exists last_seen_at timestamptz;
update public.spot_violations
   set last_seen_at = case when resolved_at is null and fine_issued_at is null
                           then now()
                           else coalesce(resolved_at, fine_issued_at) end
 where last_seen_at is null;
alter table public.spot_violations alter column last_seen_at set default now();
alter table public.spot_violations alter column last_seen_at set not null;

alter table public.spot_violations add column if not exists resolution_reason text;
alter table public.spot_violations drop constraint if exists spot_violations_resolution_reason_check;
alter table public.spot_violations add constraint spot_violations_resolution_reason_check
  check (resolution_reason is null or resolution_reason in ('CORRECTED','LEFT','MANUAL'));

-- changeset rule-engine:004-one-open-violation-per-plate-spot-type
-- At most one open violation per plate, spot and type, enforced by the database. Duplicates left
-- over from before this rule existed are closed (keeping the newest) so the index can be built.
with ranked as (
  select id,
         row_number() over (partition by plate, spot_id, violation_type order by detected_at desc, id) as rn
    from public.spot_violations
   where resolved_at is null and fine_issued_at is null
)
update public.spot_violations v
   set resolved_at = now(), resolution_reason = 'MANUAL'
  from ranked r
 where v.id = r.id and r.rn > 1;

create unique index if not exists spot_violations_open_uq
  on public.spot_violations (plate, spot_id, violation_type)
  where resolved_at is null and fine_issued_at is null;

-- changeset rule-engine:004-violation-query-indexes
create index if not exists spot_violations_stale_idx
  on public.spot_violations (last_seen_at)
  where resolved_at is null and fine_issued_at is null;
create index if not exists spot_violations_zone_active_idx
  on public.spot_violations (zone_id, detected_at desc)
  where resolved_at is null and fine_issued_at is null;
create index if not exists spot_violations_plate_detected_idx
  on public.spot_violations (plate, detected_at desc);
