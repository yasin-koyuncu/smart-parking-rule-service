-- liquibase formatted sql

-- changeset rule-engine:008-fine-void-and-manual-payment
-- Operators with fine:manage can void a fine and mark it as paid by hand; every such command and every online payment
-- leaves a row in fine_actions (audit trail, newest first per fine). `fines` is shared with the Supabase schema, so
-- everything is add-if-missing and the two constraints are dropped and re-created.
-- paid_at / paid_method / paid_by are NULL on fines paid before this change (the payment event did not record them).
alter table public.fines add column if not exists voided_at   timestamptz;
alter table public.fines add column if not exists void_reason text;
alter table public.fines add column if not exists voided_by   uuid;
alter table public.fines add column if not exists paid_at     timestamptz;
alter table public.fines add column if not exists paid_method text;
alter table public.fines add column if not exists paid_by     uuid;

alter table public.fines drop constraint if exists fines_paid_method_check;
alter table public.fines add constraint fines_paid_method_check
  check (paid_method is null or paid_method in ('online','cash','transfer','other'));
-- a fine is either paid or voided, never both (refunds are a separate flow)
alter table public.fines drop constraint if exists fines_not_paid_and_voided_check;
alter table public.fines add constraint fines_not_paid_and_voided_check
  check (voided_at is null or not paid);

-- changeset rule-engine:008-fine-actions
-- Audit trail of fine commands. Row level security is enabled without policies (as for plate_permits): the table must
-- not be readable through Supabase PostgREST, the service connects as the table owner. `reason` is operator free text
-- and is only ever returned to staff holding violation:read, never logged.
create table if not exists public.fine_actions (
  id        uuid primary key default gen_random_uuid(),
  fine_id   uuid not null references public.fines (id) on delete cascade,
  at        timestamptz not null default now(),
  actor_id  uuid,
  action    text not null,
  reason    text,
  details   jsonb not null default '{}'::jsonb
);
alter table public.fine_actions add column if not exists at       timestamptz not null default now();
alter table public.fine_actions add column if not exists actor_id uuid;
alter table public.fine_actions add column if not exists reason   text;
alter table public.fine_actions add column if not exists details  jsonb not null default '{}'::jsonb;

alter table public.fine_actions drop constraint if exists fine_actions_action_check;
alter table public.fine_actions add constraint fine_actions_action_check
  check (action in ('void','mark_paid','paid_online'));

create index if not exists fine_actions_fine_at_idx on public.fine_actions (fine_id, at desc);

alter table public.fine_actions enable row level security;

-- rollback drop table if exists public.fine_actions;
-- rollback alter table public.fines drop constraint if exists fines_not_paid_and_voided_check;
-- rollback alter table public.fines drop constraint if exists fines_paid_method_check;
-- rollback alter table public.fines drop column if exists paid_by, drop column if exists paid_method, drop column if exists paid_at, drop column if exists voided_by, drop column if exists void_reason, drop column if exists voided_at;
