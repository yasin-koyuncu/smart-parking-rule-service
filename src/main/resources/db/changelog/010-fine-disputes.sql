-- liquibase formatted sql

-- changeset rule-engine:010-fine-disputes
-- A request to have a fine reviewed by a person. At most one dispute per fine is open (partial unique index), and while it is
-- open the fine cannot be paid. `message` and `decision_note` are personal free text: shown to the driver and to staff,
-- never logged and never part of an event. Row level security is on without policies (not reachable through PostgREST).
create table if not exists public.fine_disputes (
  id                  uuid primary key default gen_random_uuid(),
  fine_id             uuid        not null references public.fines (id) on delete cascade,
  plate               text        not null,
  zone_id             text        not null,
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

create unique index if not exists fine_disputes_one_open_uq
  on public.fine_disputes (fine_id)
  where status = 'open';
create index if not exists fine_disputes_fine_opened_idx on public.fine_disputes (fine_id, opened_at desc);
create index if not exists fine_disputes_zone_opened_idx on public.fine_disputes (zone_id, opened_at desc);

alter table public.fine_disputes enable row level security;

-- changeset rule-engine:010-fine-actions-dispute-types
-- The audit trail also records the dispute lifecycle.
alter table public.fine_actions drop constraint if exists fine_actions_action_check;
alter table public.fine_actions add constraint fine_actions_action_check
  check (action in ('void','mark_paid','paid_online','dispute_opened','dispute_upheld','dispute_voided','dispute_withdrawn'));

-- rollback drop table if exists public.fine_disputes;
-- rollback alter table public.fine_actions drop constraint if exists fine_actions_action_check;
-- rollback alter table public.fine_actions add constraint fine_actions_action_check check (action in ('void','mark_paid','paid_online'));
