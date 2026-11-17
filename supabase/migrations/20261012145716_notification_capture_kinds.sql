-- Analysis Layer 3 / R2 (2d): two more reminder kinds that feed the capture streams the engines are starved of.
--   wellbeing_checkin  asks for energy / mood / stress / soreness (skipped when today already has any of them)
--   evening_log        nudges the one-message "SAY IT" log for drinks, supplements and recovery (skipped when a message was
--                      already saved today)
-- notification_rules.kind is CHECK-constrained, so the list is widened first; existing users get the two rules seeded (the app
-- only seeds defaults on first run, so without this they would never exist for an account that is already set up).
-- Rollback: delete from public.notification_rules where kind in ('wellbeing_checkin','evening_log');
--           then re-add the original three-value check.

alter table public.notification_rules drop constraint notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, d.kind, true, d.schedule, '{"skip_if_logged": true}'::jsonb, d.limits
from (select distinct user_id from public.notification_rules) u
cross join (values
  ('wellbeing_checkin', '{"times": ["10:00"]}'::jsonb, '{"max_per_day": 1, "min_gap_min": 120, "snooze_min": 60}'::jsonb),
  ('evening_log',       '{"times": ["19:30"]}'::jsonb, '{"max_per_day": 1, "min_gap_min": 120, "snooze_min": 60}'::jsonb)
) as d (kind, schedule, limits)
on conflict (user_id, kind) do nothing;
