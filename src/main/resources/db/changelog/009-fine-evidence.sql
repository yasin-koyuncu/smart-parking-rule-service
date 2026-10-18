-- liquibase formatted sql

-- changeset rule-engine:009-violation-rule-summary
-- Plain-language text of the rule that decided the violation, written when the violation is opened (the moment the rule was
-- evaluated) and frozen into fine_evidence when the fine is issued. Null for violations opened before this change.
alter table public.spot_violations add column if not exists rule_summary text;

-- changeset rule-engine:009-fine-evidence
-- The evidence of a fine, frozen when the fine is issued: one row per fine, inserted in the same transaction as the fine and
-- never updated or deleted (see the trigger below). Old fines have no row: their evidence is "not recorded".
-- `rule_summary` is text, not a reference to zone_rules, so editing the zone rules later does not change it.
-- `image_paths` are storage keys of camera snapshots (detections.image_path); they can show other people's cars, so only
-- operators with violation:read ever get them. No cascade from fines: a fine with evidence cannot be deleted either.
create table if not exists public.fine_evidence (
  fine_id               uuid primary key references public.fines (id),
  plate                 text        not null,
  zone_id               text        not null,
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

alter table public.fine_evidence enable row level security;

-- changeset rule-engine:009-fine-evidence-immutable splitStatements:false
-- Database-level guard (the application only ever inserts): any UPDATE or DELETE of an evidence row is rejected, whoever runs it.
-- TRUNCATE is not covered on purpose (test clean-up and operations).
create or replace function public.reject_fine_evidence_change() returns trigger language plpgsql as $$
begin
  raise exception 'fine_evidence is immutable (% rejected)', tg_op using errcode = 'restrict_violation';
end
$$;

drop trigger if exists fine_evidence_immutable on public.fine_evidence;
create trigger fine_evidence_immutable
  before update or delete on public.fine_evidence
  for each row execute function public.reject_fine_evidence_change();

-- rollback drop table if exists public.fine_evidence;
-- rollback drop function if exists public.reject_fine_evidence_change();
-- rollback alter table public.spot_violations drop column if exists rule_summary;
