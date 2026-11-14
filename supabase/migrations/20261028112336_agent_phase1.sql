-- Personal health agent, phase 1 (docs/personal-health-agent/README.md): the assistant's settings and its audit trail.
--
--  - agent_settings: one row per person. The assistant is off until the person turns it on in Setup; opt_in lists the
--    groups shared beyond the default (agent-core/scope.ts OPT_IN_GROUPS). Routine and location data have no switch:
--    they are never shared.
--  - agent_turns: one row per question (WHY? on a card, later ASK): the question and the screen context, the tool calls
--    and the results they returned (references only), the model, tokens and latency, the guard results, the reply as
--    shown, and the person's optional 1-5 rating. unique(user_id, client_turn_id) makes a retry return the stored reply.
--    The Edge Function writes with the caller's JWT; people read, rate and delete their own turns.
--
-- Rollback: drop table public.agent_turns; drop table public.agent_settings;

create table public.agent_settings (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  enabled boolean not null default false,
  opt_in text[] not null default '{}'
    check (opt_in <@ array['notes', 'sexual_health', 'health_concerns', 'digestive']::text[]),
  updated_at timestamptz not null default now()
);

alter table public.agent_settings enable row level security;

create policy agent_settings_select_own on public.agent_settings for select to authenticated using (user_id = (select auth.uid()));
create policy agent_settings_insert_own on public.agent_settings for insert to authenticated with check (user_id = (select auth.uid()));
create policy agent_settings_update_own on public.agent_settings for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

comment on table public.agent_settings is 'Personal health agent: on/off and the opt-in data groups (off by default).';

create table public.agent_turns (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  client_turn_id text not null check (length(client_turn_id) between 8 and 100),
  mode text not null check (mode in ('explain', 'ask', 'coach')),
  question text not null check (length(question) between 1 and 1000),
  context jsonb not null default '{}'::jsonb,
  model text,
  calls jsonb not null default '[]'::jsonb,
  refs jsonb not null default '[]'::jsonb,
  violations jsonb not null default '[]'::jsonb,
  regenerated boolean not null default false,
  triage text check (triage in ('urgent', 'clinician')),
  shown text not null,
  steps smallint not null default 0,
  tokens_prompt integer not null default 0,
  tokens_completion integer not null default 0,
  latency_ms integer,
  error text,
  rating smallint check (rating between 1 and 5),
  created_at timestamptz not null default now(),
  unique (user_id, client_turn_id)
);

create index agent_turns_user_created_idx on public.agent_turns (user_id, created_at desc);

alter table public.agent_turns enable row level security;

create policy agent_turns_select_own on public.agent_turns for select to authenticated using (user_id = (select auth.uid()));
create policy agent_turns_insert_own on public.agent_turns for insert to authenticated with check (user_id = (select auth.uid()));
-- Updates are for the rating; the function never rewrites a turn.
create policy agent_turns_update_own on public.agent_turns for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy agent_turns_delete_own on public.agent_turns for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.agent_turns is 'Personal health agent: one row per question, with tool calls, guard results and the reply as shown.';
