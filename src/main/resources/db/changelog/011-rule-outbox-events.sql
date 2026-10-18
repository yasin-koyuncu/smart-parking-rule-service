-- liquibase formatted sql

-- changeset rule-engine:011-rule-outbox-events
-- Transactional outbox of rule-service: every event is a row written in the same transaction as the state change; a scheduled
-- relay publishes the rows (publisher confirms, retry with back-off) and the clean-up job removes old published rows.
-- Named rule_outbox_events because payment-service already owns public.outbox_events in the same database (two relays must
-- never read each other's rows).
-- `seq` gives a strict insertion order (the relay delivers the events of one aggregate - a fine or a violation - in that
-- order); `request_id` keeps the x-request-id header of the request that caused the event; the row id is the AMQP messageId
-- (idempotency key for consumers); `payload` is the exact JSON the consumers receive (text, not jsonb: jsonb would reorder the keys).
create table if not exists public.rule_outbox_events (
  id              uuid primary key default gen_random_uuid(),
  seq             bigint generated always as identity,
  aggregate_type  text        not null,
  aggregate_id    text        not null,
  exchange        text        not null,
  routing_key     text        not null,
  payload         text        not null,
  request_id      text,
  created_at      timestamptz not null default now(),
  published_at    timestamptz,
  attempts        integer     not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_error      text
);

-- the relay only looks at unpublished rows: the due ones in insertion order, and per aggregate the oldest unpublished one
-- (nothing may overtake it)
create index if not exists rule_outbox_events_due_idx
  on public.rule_outbox_events (seq)
  where published_at is null;
create index if not exists rule_outbox_events_aggregate_idx
  on public.rule_outbox_events (aggregate_id, seq)
  where published_at is null;
create index if not exists rule_outbox_events_published_idx
  on public.rule_outbox_events (published_at)
  where published_at is not null;

alter table public.rule_outbox_events enable row level security;

-- rollback drop table if exists public.rule_outbox_events;
