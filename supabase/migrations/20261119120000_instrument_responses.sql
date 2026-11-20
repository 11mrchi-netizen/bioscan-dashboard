-- Risk profile R2 (docs/risk-profile/01-plan.md): answers to the validated questionnaires (PHQ-9, GAD-7, WHO-5, UCLA-3,
-- the Copenhagen Burnout Inventory's work scale, working hours), defined in supabase/functions/_shared/instruments/.
-- One row per completed questionnaire, with the answers in item order and the score, band and flags the app worked out
-- from the definitions version it names. Append-only: a mistake is deleted and answered again, never edited.
-- answered_at is a real instant (when it was saved); local_date is the person's calendar date at that moment.
-- 2. notification_rules: a 'questionnaire_due' reminder, Sunday 10:00, sent only when one is due.
-- Rollback: drop table public.instrument_responses; delete from public.notification_rules where kind = 'questionnaire_due';
--           restore notification_rules_kind_check without it (20261110044303_routine_practices.sql).

create table public.instrument_responses (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  instrument text not null check (instrument in ('phq9', 'gad7', 'who5', 'ucla3', 'cbi_work', 'work_hours')),
  defs_version text not null,
  answered_at timestamptz not null default now(),
  local_date date not null,
  answers jsonb not null check (jsonb_typeof(answers) = 'array' and jsonb_array_length(answers) between 1 and 20),
  score numeric not null,
  band text,
  flags text[] not null default '{}',
  source text not null default 'phone' check (source in ('phone', 'console')),
  created_at timestamptz not null default now()
);

create index instrument_responses_user_idx on public.instrument_responses (user_id, instrument, local_date desc);

alter table public.instrument_responses enable row level security;

create policy instrument_responses_select_own on public.instrument_responses for select to authenticated using (user_id = (select auth.uid()));
create policy instrument_responses_insert_own on public.instrument_responses for insert to authenticated with check (user_id = (select auth.uid()));
create policy instrument_responses_delete_own on public.instrument_responses for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.instrument_responses is 'Questionnaire answers (risk profile R2). Append-only; answered_at is a real instant, local_date the local calendar date.';

alter table public.notification_rules drop constraint if exists notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin', 'fuel_session', 'fuel_event', 'health_checkin',
                           'event_rating', 'event_draw', 'event_followup', 'practice_due', 'questionnaire_due']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, 'questionnaire_due', true, '{"times": ["10:00"], "days": [7]}'::jsonb, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 1, "min_gap_min": 60, "snooze_min": 60}'::jsonb
from (select distinct user_id from public.notification_rules) u
on conflict (user_id, kind) do nothing;
