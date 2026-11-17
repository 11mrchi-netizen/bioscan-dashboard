-- Analysis hub phase D (docs/analysis-console/PLAN.md): decision records. A decision connects a question to the action
-- taken and keeps the evidence it rested on, so the record stays readable when later results change.
--
--  - Append-only, like evidence_ledger: users insert and read their own rows; a trigger refuses updates; there is no
--    delete policy (rows still go with the account through the cascade). Changing one's mind is a new row that names
--    the one it supersedes.
--  - `evidence` is the snapshot at the time of the decision: verdict, estimate and interval, unit, no-effect zone, n,
--    information, evidence class, engine version, inputs hash, as-of date and a reference to the published result.
--  - adopt and reject need a settled verdict (clear or negligible) in the snapshot; continue_testing and inconclusive
--    are always allowed. The console enforces the same rule (analysis-core/evidenceItems.ts).
--
-- Rollback: drop table public.decisions; drop function public.decisions_guard();

create table public.decisions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- the evidence item: 'q:<catalogue id>', 'reg:<registered question id>', 'exp:<experiment id>', 'its:<intervention id>'
  item_id text not null,
  title text not null,
  decision text not null check (decision in ('adopt', 'reject', 'continue_testing', 'inconclusive')),
  rationale text not null check (length(btrim(rationale)) >= 10),
  -- what change would be practically meaningful, in the person's words
  threshold text,
  -- when, or with what new evidence, to revisit
  review_condition text,
  review_on date,
  evidence_class text not null check (evidence_class in ('association', 'experiment', 'interrupted_time_series')),
  evidence jsonb not null,
  supersedes bigint references public.decisions (id),
  decided_at timestamptz not null default now()
);

create index decisions_user_item_idx on public.decisions (user_id, item_id, decided_at desc);

create or replace function public.decisions_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    raise exception 'decisions are append-only: record a new decision that supersedes this one';
  end if;
  if new.decision in ('adopt', 'reject') and coalesce(new.evidence ->> 'verdict', '') not in ('clear', 'negligible') then
    raise exception 'adopt and reject need a settled verdict (clear or negligible) in the evidence snapshot';
  end if;
  -- the trigger runs as the caller, so row-level security limits this to the caller's own decisions
  if new.supersedes is not null and not exists (
    select 1 from public.decisions d where d.id = new.supersedes and d.user_id = new.user_id and d.item_id = new.item_id
  ) then
    raise exception 'a decision can only supersede an earlier decision on the same question';
  end if;
  return new;
end;
$$;

create trigger decisions_guard before insert or update on public.decisions
  for each row execute function public.decisions_guard();

alter table public.decisions enable row level security;

create policy decisions_select_own on public.decisions for select to authenticated using (user_id = (select auth.uid()));
create policy decisions_insert_own on public.decisions for insert to authenticated with check (user_id = (select auth.uid()));
-- No update or delete policies on purpose.

comment on table public.decisions is 'Analysis hub phase D: append-only decision records with a snapshot of the evidence each rested on.';
