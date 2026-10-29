-- Repeatable interventions, round two (docs/intervention-protocol-intelligence):
--   1. sleep respiratory rate (resp_rate) is a formal experiment outcome, so an intervention aimed at it can be promoted;
--   2. evidence_ledger accepts source 'event': the nightly ladder/observed state of each declared expectation;
--   3. an optional daily coin-flip draw (interventions.draw_enabled, intervention_draws);
--   4. three reminder kinds: event_rating, event_draw, event_followup.
-- Rollback: drop table public.intervention_draws; alter table public.interventions drop column draw_enabled;
--           delete from public.notification_rules where kind in ('event_rating', 'event_draw', 'event_followup');
--           delete from public.evidence_ledger where source = 'event';
--           restore the three checks (experiments metrics: 20261108_routine_experiments.sql; ledger source:
--           20261101_health_concerns.sql; notification kinds: that file too) after removing rows that use the new values.

-- ---------- 1. respiratory rate as an experiment outcome ----------

alter table public.experiments drop constraint experiments_primary_metric_check;
alter table public.experiments add constraint experiments_primary_metric_check
  check (primary_metric in ('hrv', 'rhr', 'sleep_hours', 'deep_rem_min', 'energy', 'mood', 'stress', 'soreness',
                            'screen_bed_min', 'screen_pm_social', 'outdoor_daylight_min', 'resp_rate'));

alter table public.experiments drop constraint experiments_secondary_metrics_check;
alter table public.experiments add constraint experiments_secondary_metrics_check
  check (cardinality(secondary_metrics) <= 3 and secondary_metrics <@ array['hrv', 'rhr', 'sleep_hours', 'deep_rem_min',
         'energy', 'mood', 'stress', 'soreness', 'screen_bed_min', 'screen_pm_social', 'outdoor_daylight_min', 'resp_rate']::text[]);

-- ---------- 2. ledger rows for events ----------

alter table public.evidence_ledger drop constraint if exists evidence_ledger_source_check;
alter table public.evidence_ledger add constraint evidence_ledger_source_check
  check (source in ('catalogue', 'registered', 'health', 'event'));

-- ---------- 3. the coin-flip draw ----------

alter table public.interventions add column if not exists draw_enabled boolean not null default false;

create table public.intervention_draws (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  -- the local calendar day the draw is for; one draw per intervention and day, never redrawn
  local_date date not null,
  drawn_at timestamptz not null default now(),
  result text not null check (result in ('do', 'skip')),
  -- the event logged from a 'do' draw, when there is one
  event_id uuid references public.intervention_events (id) on delete set null,
  unique (intervention_id, local_date)
);

create index intervention_draws_user_day_idx on public.intervention_draws (user_id, local_date);

alter table public.intervention_draws enable row level security;
create policy intervention_draws_select_own on public.intervention_draws for select to authenticated using (user_id = (select auth.uid()));
create policy intervention_draws_insert_own on public.intervention_draws for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.interventions i where i.id = intervention_id and i.user_id = (select auth.uid())));
-- only the link to the logged event may change afterwards, never the result
create policy intervention_draws_update_own on public.intervention_draws for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy intervention_draws_delete_own on public.intervention_draws for delete to authenticated using (user_id = (select auth.uid()));

create or replace function public.intervention_draws_freeze_result()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.result is distinct from old.result or new.local_date is distinct from old.local_date
     or new.intervention_id is distinct from old.intervention_id or new.drawn_at is distinct from old.drawn_at then
    raise exception 'a draw cannot be changed once made';
  end if;
  return new;
end;
$$;

create trigger intervention_draws_freeze_result before update on public.intervention_draws
  for each row execute function public.intervention_draws_freeze_result();

-- ---------- 4. reminders ----------

alter table public.notification_rules drop constraint if exists notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin', 'fuel_session', 'fuel_event', 'health_checkin',
                           'event_rating', 'event_draw', 'event_followup']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, d.kind, true, d.schedule, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 1, "min_gap_min": 60, "snooze_min": 30}'::jsonb
from (select distinct user_id from public.notification_rules) u
cross join (values
  ('event_rating',   '{"times": ["09:00"]}'::jsonb),
  ('event_draw',     '{"times": ["18:00"]}'::jsonb),
  ('event_followup', '{"times": ["20:00"]}'::jsonb)
) as d (kind, schedule)
where not exists (select 1 from public.notification_rules r where r.user_id = u.user_id and r.kind = d.kind);
