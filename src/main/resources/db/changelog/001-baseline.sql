-- liquibase formatted sql

-- The schema of rule-service (schema `rules`, role `svc_rule`; ADR 0010). The schema, the role and the grants to the Supabase roles are
-- created by the infrastructure, not here; names are unqualified and resolve through the default schema of Liquibase and the search_path
-- of the role.
--
-- Every row belongs to a site (`site_id NOT NULL`) and only optionally to a zone (`zone_id` nullable; ADR 0009). Permits are the exception:
-- `site_id` null = valid at every site, `zone_id` null = valid in the whole site. No foreign key leaves this schema: the ids of spots, zones,
-- sites, cameras and users are plain columns, the service that writes a row checks them where it matters. Deleting a spot or zone in
-- parking-mapping-service therefore never touches the data here; rule-service reacts to `map.updated` (see MapEventListener).

-- changeset parkview:1-baseline
-- Fines. A fine ends either paid or voided, never both (refunds are a separate flow). paid_at / paid_method / paid_by / voided_* are set by
-- the commands of Fine, camera_id, reason_code and reason_text are optional display data that no command of this service writes.
create table fines (
  id           uuid primary key default gen_random_uuid(),
  plate        text          not null,
  site_id      text          not null,
  zone_id      text,
  camera_id    text,
  reason       text          not null,
  reason_code  text,
  reason_text  text,
  amount_sek   numeric(10,2) not null,
  issued_at    timestamptz   not null default now(),
  paid         boolean       not null default false,
  user_id      uuid,
  image_path   text,
  created_at   timestamptz   not null default now(),
  paid_at      timestamptz,
  paid_method  text,
  paid_by      uuid,
  voided_at    timestamptz,
  void_reason  text,
  voided_by    uuid,
  constraint fines_reason_check
    check (reason in ('no_parking','overstay','wrong_permit','boundary_exceeded','wrong_vehicle_type')),
  constraint fines_paid_method_check
    check (paid_method is null or paid_method in ('online','cash','transfer','other')),
  constraint fines_not_paid_and_voided_check
    check (voided_at is null or not paid)
);

-- the read API lists fines per plate, per site and per zone, newest first, paged: one index per access path
create index fines_plate_issued_idx on fines (plate, issued_at desc);
create index fines_site_issued_idx  on fines (site_id, issued_at desc);
create index fines_zone_issued_idx  on fines (zone_id, issued_at desc);

-- Audit trail of fine commands (void, manual and online payment, disputes, payments that need a review), newest first per fine.
-- `reason` is operator free text and is only ever returned to staff holding violation:read, never logged.
create table fine_actions (
  id        uuid primary key default gen_random_uuid(),
  fine_id   uuid        not null references fines (id) on delete cascade,
  at        timestamptz not null default now(),
  actor_id  uuid,
  action    text        not null,
  reason    text,
  details   jsonb       not null default '{}'::jsonb,
  constraint fine_actions_action_check
    check (action in ('void','mark_paid','paid_online','dispute_opened','dispute_upheld','dispute_voided','dispute_withdrawn',
                      'payment_needs_review'))
);

create index fine_actions_fine_at_idx on fine_actions (fine_id, at desc);

-- The evidence of a fine, frozen when the fine is issued: one row per fine, inserted in the same transaction as the fine and never updated
-- or deleted (guard in changeset 2). `rule_summary` is text, not a reference to a rule, so editing the rules later does not change it.
-- `image_paths` are storage keys of camera snapshots - they can show other people's cars - so only operators with violation:read get them.
-- No cascade from fines: a fine with evidence cannot be deleted either.
create table fine_evidence (
  fine_id               uuid primary key references fines (id),
  plate                 text        not null,
  site_id               text        not null,
  site_address          text,
  zone_id               text,
  zone_address          text,
  spot_id               uuid,
  spot_number           text,
  violation_type        text        not null,
  violation_id          uuid        not null,
  rule_summary          text        not null,
  grace_minutes         integer     not null,
  violation_detected_at timestamptz not null,
  grace_expired_at      timestamptz not null,
  issued_at             timestamptz not null,
  detection_ids         jsonb       not null default '[]'::jsonb,
  image_paths           jsonb       not null default '[]'::jsonb,
  min_confidence        numeric(5,4),
  decision_basis        text        not null default 'automatic',
  created_at            timestamptz not null default now(),
  constraint fine_evidence_decision_basis_check check (decision_basis in ('automatic'))
);

-- A request to have a fine reviewed by a person. At most one dispute per fine is open (partial unique index) and while it is open the
-- fine cannot be paid. `message` and `decision_note` are personal free text: shown to the driver and to staff, never logged and never
-- part of an event.
create table fine_disputes (
  id                  uuid primary key default gen_random_uuid(),
  fine_id             uuid        not null references fines (id) on delete cascade,
  plate               text        not null,
  site_id             text        not null,
  zone_id             text,
  opened_by_user_id   uuid,
  channel             text        not null,
  message             text        not null,
  status              text        not null default 'open',
  opened_at           timestamptz not null default now(),
  decided_by_user_id  uuid,
  decided_at          timestamptz,
  decision_note       text,
  constraint fine_disputes_channel_check check (channel in ('driver','operator')),
  constraint fine_disputes_status_check check (status in ('open','upheld','voided','withdrawn')),
  constraint fine_disputes_decided_check check ((status = 'open') = (decided_at is null))
);

create unique index fine_disputes_one_open_uq on fine_disputes (fine_id) where status = 'open';
create index fine_disputes_fine_opened_idx on fine_disputes (fine_id, opened_at desc);
create index fine_disputes_site_opened_idx on fine_disputes (site_id, opened_at desc);
create index fine_disputes_zone_opened_idx on fine_disputes (zone_id, opened_at desc);

-- Permits that allow a plate to park in spots that require a given permit type. site_id null = every site, zone_id null = the whole site.
create table plate_permits (
  id           uuid primary key default gen_random_uuid(),
  plate        text        not null,
  permit_type  text        not null,
  site_id      text,
  zone_id      text,
  valid_from   timestamptz not null default now(),
  valid_to     timestamptz,
  created_by   text        not null,
  created_at   timestamptz not null default now(),
  constraint plate_permits_valid_range check (valid_to is null or valid_to > valid_from)
);

create index plate_permits_plate_idx on plate_permits (plate);
create index plate_permits_site_idx  on plate_permits (site_id);
create index plate_permits_zone_idx  on plate_permits (zone_id);

-- An open or closed breach of a parking rule by one plate in one spot. spot_id and zone_id point into parking-mapping-service (no foreign
-- key), user_id is the driver who registered the plate (plain column, identity owns the account).
-- last_seen_at is updated whenever a detection re-confirms the violation, and drives the stale sweep, resolution_reason says why an open
-- violation was closed without a fine, rule_summary is the plain-language text of the rule that decided it.
create table spot_violations (
  id                uuid primary key default gen_random_uuid(),
  spot_id           uuid        not null,
  site_id           text        not null,
  zone_id           text,
  plate             text        not null,
  user_id           uuid,
  violation_type    text        not null,
  detected_at       timestamptz not null default now(),
  grace_until       timestamptz not null,
  last_seen_at      timestamptz not null default now(),
  resolved_at       timestamptz,
  resolution_reason text,
  fine_issued_at    timestamptz,
  fine_id           uuid,
  image_path        text,
  notified_at       timestamptz,
  rule_summary      text,
  created_at        timestamptz not null default now(),
  constraint spot_violations_violation_type_check
    check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING','WRONG_VEHICLE_TYPE')),
  constraint spot_violations_resolution_reason_check
    check (resolution_reason is null or resolution_reason in ('CORRECTED','LEFT','MANUAL'))
);

-- at most one open violation per plate, spot and type, enforced by the database
create unique index spot_violations_open_uq
  on spot_violations (plate, spot_id, violation_type)
  where resolved_at is null and fine_issued_at is null;
-- the sweeps and the read API only look at active violations
create index spot_violations_active_idx
  on spot_violations (grace_until)
  where resolved_at is null and fine_issued_at is null;
create index spot_violations_stale_idx
  on spot_violations (last_seen_at)
  where resolved_at is null and fine_issued_at is null;
create index spot_violations_site_active_idx
  on spot_violations (site_id, detected_at desc)
  where resolved_at is null and fine_issued_at is null;
create index spot_violations_zone_active_idx
  on spot_violations (zone_id, detected_at desc)
  where resolved_at is null and fine_issued_at is null;
create index spot_violations_plate_detected_idx on spot_violations (plate, detected_at desc);
create index spot_violations_plate_site_idx     on spot_violations (plate, site_id);

-- Transactional outbox: every event is a row written in the same transaction as the state change, a scheduled relay publishes the rows
-- (publisher confirms, retry with back-off) and the clean-up job removes old published rows.
-- `seq` gives a strict insertion order (the relay delivers the events of one aggregate - a fine or a violation - in that order),
-- `request_id` keeps the x-request-id header of the request that caused the event, the row id is the AMQP messageId (idempotency key for
-- consumers) - `payload` is the exact JSON the consumers receive (text, not jsonb: jsonb would reorder the keys).
create table outbox_events (
    id              uuid primary key default gen_random_uuid(),
    -- insertion order; the relay delivers the events of one aggregate in this order
    seq             bigint generated always as identity,
    aggregate_type  text        not null,
    aggregate_id    text        not null,
    exchange        text        not null,
    routing_key     text        not null,
    -- the exact JSON the consumers receive (text, not jsonb: jsonb would reorder the keys)
    payload         text        not null,
    -- X-Request-Id of the request that caused the event, null when there was none
    request_id      text,
    created_at      timestamptz not null default now(),
    published_at    timestamptz,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text
);

-- the relay only looks at unpublished rows: the due ones in insertion order, and per aggregate the oldest unpublished one
create index outbox_events_due_idx on outbox_events (seq) where published_at is null;
create index outbox_events_pending_aggregate_idx on outbox_events (aggregate_id, seq) where published_at is null;
create index outbox_events_published_idx on outbox_events (published_at) where published_at is not null;
-- everything that happened to one aggregate (diagnostics, reconciliation)
create index outbox_events_aggregate_idx on outbox_events (aggregate_id, created_at);

-- changeset parkview:2-baseline-evidence-guard splitStatements:false
-- Database-level guard (the application only ever inserts): any UPDATE or DELETE of an evidence row is rejected, whoever runs it.
-- TRUNCATE is not covered on purpose (test clean-up and operations).
create function reject_fine_evidence_change() returns trigger language plpgsql as $$
begin
  raise exception 'fine_evidence is immutable (% rejected)', tg_op using errcode = 'restrict_violation';
end
$$;

create trigger fine_evidence_immutable
  before update or delete on fine_evidence
  for each row execute function reject_fine_evidence_change();

-- changeset parkview:3-baseline-published-views splitStatements:false
-- The published read views (ADR 0010, section 4): exactly the columns of fines and of fine_evidence. ingestion-service reads them through
-- PostgREST (role service_role): v_fines to authorise the picture of a fine, v_fine_evidence (detection_ids, image_paths) for its
-- retention of snapshots; nobody else reads this schema. The grants are skipped when the role does not exist,
-- so that a missing role does not fail the changelog (the infrastructure creates the roles).
create view v_fines as
select id, plate, site_id, zone_id, camera_id, reason, reason_code, reason_text, amount_sek, issued_at, paid, user_id, image_path,
       created_at, paid_at, paid_method, paid_by, voided_at, void_reason, voided_by
  from fines;

create view v_fine_evidence as
select fine_id, plate, site_id, site_address, zone_id, zone_address, spot_id, spot_number, violation_type, violation_id, rule_summary,
       grace_minutes, violation_detected_at, grace_expired_at, issued_at, detection_ids, image_paths, min_confidence, decision_basis,
       created_at
  from fine_evidence;

do $$
begin
  if exists (select from pg_roles where rolname = 'service_role') then
    execute format('grant usage on schema %I to service_role', current_schema());
    execute format('grant select on %I.v_fines to service_role', current_schema());
    execute format('grant select on %I.v_fine_evidence to service_role', current_schema());
  end if;
end
$$;
