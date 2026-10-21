-- Fuel planning reminders (docs/fuel-planning/README.md, phase 7): two notification kinds tied to sessions and races rather
-- than to clock times (domain/notifications/FuelPrompts.kt). The worker reads the planned sessions, calendar training and
-- events; the rules carry no times.
--   fuel_session  3 h before a hard, long or race session of an hour or more, and 30 min after it ends
--   fuel_event    race week for an A or B race over 90 minutes: loading, the day before, race morning
-- notification_rules.kind is CHECK-constrained, so the list is widened; existing users get both rules seeded (the app's
-- own seeding inserts missing kinds too, but only once the constraint allows them).
--
-- Rollback: delete from public.notification_rules where kind in ('fuel_session', 'fuel_event');
--           restore notification_rules_kind_check without them (20261019_body_checkins.sql).

alter table public.notification_rules drop constraint if exists notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin', 'fuel_session', 'fuel_event']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, d.kind, true, '{}'::jsonb, '{"skip_if_logged": false}'::jsonb, d.limits
from (select distinct user_id from public.notification_rules) u
cross join (values
  ('fuel_session', '{"max_per_day": 4, "min_gap_min": 60, "snooze_min": 30}'::jsonb),
  ('fuel_event',   '{"max_per_day": 3, "min_gap_min": 30, "snooze_min": 30}'::jsonb)
) as d (kind, limits)
on conflict (user_id, kind) do nothing;
