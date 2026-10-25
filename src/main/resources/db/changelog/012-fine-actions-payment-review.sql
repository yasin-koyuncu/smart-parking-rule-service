-- liquibase formatted sql

-- changeset rule-engine:012-fine-actions-payment-review
-- A payment that completes for a fine that is already paid or voided, or for another amount than the fine, is recorded in the
-- audit trail so that a person can refund or correct it (details: paymentId, causes, amounts). Idempotent like 008 and 010: the
-- constraint is dropped and re-created with the full list of actions.
alter table public.fine_actions drop constraint if exists fine_actions_action_check;
alter table public.fine_actions add constraint fine_actions_action_check
  check (action in ('void','mark_paid','paid_online','dispute_opened','dispute_upheld','dispute_voided','dispute_withdrawn','payment_needs_review'));

-- rollback alter table public.fine_actions drop constraint if exists fine_actions_action_check;
-- rollback alter table public.fine_actions add constraint fine_actions_action_check check (action in ('void','mark_paid','paid_online','dispute_opened','dispute_upheld','dispute_voided','dispute_withdrawn'));
