-- liquibase formatted sql

-- The schema of rule-service (schema `rules`, role `svc_rule`, ADR 0010). The schema, the role and the grants to the Supabase roles are
-- created by the infrastructure, not here, names are unqualified and resolve through the default schema of Liquibase and the search_path
-- of the role.
--
-- Every row belongs to a site (`site_id NOT NULL`) and only optionally to a zone (`zone_id` nullable, ADR 0009). Permits are the exception:
-- `site_id` null = valid at every site, `zone_id` null = valid in the whole site. No foreign key leaves this schema: the ids of spots, zones,
-- sites, cameras and users are plain columns, the service that writes a row checks them where it matters. Deleting a spot or zone in
-- parking-mapping-service therefore never touches the data here, rule-service reacts to `map.updated` (see MapEventListener).
-- Inside this schema the foreign keys restrict: nothing here is deleted by a cascade, only by the retention job.

-- changeset parkview:1-baseline
-- Fines. A fine ends either paid or voided, never both (refunds are a separate flow). paid_at, paid_method, paid_by and voided_* are set
-- together with paid and voided by the commands of Fine, the checks below say so. The retention job later clears plate, user_id, image_path
-- and void_reason of closed fines, which is why void_reason may be null on a voided fine. session_id is the parking session of
-- session-service that was active at the place when the violation was detected (null when there was none; no foreign key across schemas).
create table fines (
  id           uuid primary key default gen_random_uuid(),
  plate        text          not null,
  site_id      text          not null,
  zone_id      text,
  reason       text          not null,
  amount_sek   numeric(10,2) not null,
  issued_at    timestamptz   not null default now(),
  paid         boolean       not null default false,
  user_id      uuid,
  image_path   text,
  session_id   uuid,
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
    check (voided_at is null or not paid),
  constraint fines_amount_check
    check (amount_sek >= 0),
  constraint fines_paid_consistent_check
    check (paid = (paid_at is not null) and paid = (paid_method is not null)),
  constraint fines_voided_consistent_check
    check ((voided_at is null and void_reason is null and voided_by is null) or (voided_at is not null and voided_by is not null))
);

-- the read API lists fines per plate, per site and per zone, newest first, paged: one index per access path
create index fines_plate_issued_idx on fines (plate, issued_at desc);
create index fines_site_issued_idx  on fines (site_id, issued_at desc);
create index fines_zone_issued_idx  on fines (zone_id, issued_at desc);

-- Audit trail of fine commands (void, manual and online payment, disputes, payments that need a review), newest first per fine.
-- `reason` is operator free text and is only ever returned to staff holding violation:read, never logged, the retention job clears it.
create table fine_actions (
  id        uuid primary key default gen_random_uuid(),
  fine_id   uuid        not null references fines (id) on delete restrict,
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

-- The evidence of a fine, frozen when the fine is issued: one row per fine and one per violation, inserted in the same transaction as the
-- fine and never deleted. It is never updated either, with one exception: the retention job may replace plate, image_paths and
-- detection_ids (guard in changeset 2). `rule_summary` is text, not a reference to a rule, so editing the rules later does not change it.
-- `image_paths` are storage keys of camera snapshots, they can show the cars of other people, so only operators with violation:read get them.
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
  violation_id          uuid        not null unique,
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
  constraint fine_evidence_decision_basis_check check (decision_basis in ('automatic')),
  constraint fine_evidence_violation_type_check
    check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING','WRONG_VEHICLE_TYPE'))
);

-- ingestion-service asks through the view v_fine_evidence which detections and pictures are evidence (jsonb containment)
create index fine_evidence_detection_ids_idx on fine_evidence using gin (detection_ids jsonb_path_ops);
create index fine_evidence_image_paths_idx   on fine_evidence using gin (image_paths jsonb_path_ops);

-- A request to have a fine reviewed by a person. At most one dispute per fine is open (partial unique index) and while it is open the
-- fine cannot be paid. `message` and `decision_note` are personal free text: shown to the driver and to staff, never logged and never
-- part of an event, the retention job clears them.
create table fine_disputes (
  id                  uuid primary key default gen_random_uuid(),
  fine_id             uuid        not null references fines (id) on delete restrict,
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
-- last_seen_at is updated whenever a detection re-confirms the violation and drives the stale sweep, resolution_reason says why an open
-- violation was closed without a fine, rule_summary is the plain-language text of the rule that decided it. A violation is fined at most
-- once: session_id is the active parking session of session-service at the place when it was detected (null when there was none; no foreign
-- key across schemas); fine_id and fine_issued_at are set together (Violation.markFined) and one fine belongs to one violation. The retention job deletes
-- resolved violations that never became a fine.
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
  session_id        uuid,
  notified_at       timestamptz,
  rule_summary      text,
  created_at        timestamptz not null default now(),
  constraint spot_violations_violation_type_check
    check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING','WRONG_VEHICLE_TYPE')),
  constraint spot_violations_resolution_reason_check
    check (resolution_reason is null or resolution_reason in ('CORRECTED','LEFT','MANUAL')),
  constraint spot_violations_fine_fk foreign key (fine_id) references fines (id),
  constraint spot_violations_fine_paired_check check ((fine_id is null) = (fine_issued_at is null))
);

-- one fine per violation
create unique index spot_violations_fine_uq on spot_violations (fine_id) where fine_id is not null;

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
-- the retention job deletes resolved violations without a fine, oldest first
create index spot_violations_resolved_idx
  on spot_violations (resolved_at)
  where resolved_at is not null and fine_issued_at is null;

-- Transactional outbox: every event is a row written in the same transaction as the state change, a scheduled relay publishes the rows
-- (publisher confirms, retry with back-off) and the clean-up job removes old published rows.
-- `seq` gives a strict insertion order (the relay delivers the events of one aggregate - a fine or a violation - in that order),
-- `request_id` keeps the x-request-id header of the request that caused the event, the row id is the AMQP messageId (idempotency key for
-- consumers), `payload` is the exact JSON the consumers receive (text, not jsonb: jsonb would reorder the keys).
create table outbox_events (
    id              uuid primary key default gen_random_uuid(),
    -- insertion order; the relay delivers the events of one aggregate in this order
    seq             bigint generated always as identity unique,
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

-- changeset parkview:2-baseline-evidence-guard splitStatements:false
-- Database-level guard (the application only ever inserts): any DELETE of an evidence row is rejected, whoever runs it, and so is any UPDATE
-- except the one of the retention job: a transaction that switched the setting parkview.retention to on (set_config with is_local true) may
-- replace plate, image_paths and detection_ids, every other column must stay as it is. TRUNCATE is not covered on purpose (test clean-up and operations).
create function reject_fine_evidence_change() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE'
     and current_setting('parkview.retention', true) = 'on'
     and (to_jsonb(new) - 'plate' - 'image_paths' - 'detection_ids') = (to_jsonb(old) - 'plate' - 'image_paths' - 'detection_ids') then
    return new;
  end if;
  raise exception 'fine_evidence is immutable (% rejected)', tg_op using errcode = 'restrict_violation';
end
$$;

create trigger fine_evidence_immutable
  before update or delete on fine_evidence
  for each row execute function reject_fine_evidence_change();

-- changeset parkview:3-published-views runOnChange:true splitStatements:false
-- The published read views (ADR 0010, section 4), re-created whenever this changeset changes. Only the columns that the readers use, nothing
-- else: ingestion-service reads them through PostgREST (role service_role). v_fines gives it the picture and the site of a fine to authorise a
-- request for the picture, v_fine_evidence the detections and pictures that are evidence of a fine, so that its retention keeps them.
-- Nobody else reads this schema. The grants are skipped when the role does not exist, so that a missing role does not fail the changelog
-- (the infrastructure creates the roles).
drop view if exists v_fines;
drop view if exists v_fine_evidence;

create view v_fines as
select id, site_id, image_path
  from fines;

create view v_fine_evidence as
select detection_ids, image_paths
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
