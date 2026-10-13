-- liquibase formatted sql

-- changeset rule-engine:007-fines-read-indexes
-- The read API lists fines per plate and per zone, newest first, paged: one index per access path
-- so that neither needs a scan and sort of the whole table.
create index if not exists fines_plate_issued_idx on public.fines (plate, issued_at desc);
create index if not exists fines_zone_issued_idx  on public.fines (zone_id, issued_at desc);

-- rollback drop index if exists public.fines_plate_issued_idx;
-- rollback drop index if exists public.fines_zone_issued_idx;
