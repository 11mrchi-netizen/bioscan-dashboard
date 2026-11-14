-- Analysis Layer 3 / R2: audit + idempotency for the `log-agent` Edge Function.
-- One row per utterance the person typed: what they said, what the model returned, what was proposed. The function reads and
-- writes it with the CALLER's JWT (RLS own-rows), so it needs no service role. client_turn_id makes a retried request return
-- the stored proposals instead of calling the model again. committed_at/committed_count are filled by the app after the
-- person confirmed and the rows were written through the normal repositories.
-- Rollback: drop table public.log_agent_runs;

create table public.log_agent_runs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  client_turn_id text not null,
  utterance text not null,
  model text,
  latency_ms integer,
  raw_output jsonb,
  proposals jsonb not null default '[]'::jsonb,
  unparsed jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  committed_at timestamptz,
  committed_count integer,
  unique (user_id, client_turn_id)
);

alter table public.log_agent_runs enable row level security;

create policy log_agent_runs_select_own on public.log_agent_runs for select to authenticated using (user_id = (select auth.uid()));
create policy log_agent_runs_insert_own on public.log_agent_runs for insert to authenticated with check (user_id = (select auth.uid()));
create policy log_agent_runs_update_own on public.log_agent_runs for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
