alter table public.wellbeing_daily
  add column if not exists morning_erection_quality smallint,
  add column if not exists arousal_level smallint;

-- Backfill from arousal_daily: insert a row if the date has no wellbeing_daily
-- entry yet, otherwise just set the two new columns on the existing row.
insert into public.wellbeing_daily (user_id, date, morning_erection_quality, arousal_level)
select a.user_id, a.date, a.morning_erection_quality, a.arousal_level
from public.arousal_daily a
on conflict (user_id, date) do update
  set morning_erection_quality = excluded.morning_erection_quality,
      arousal_level = excluded.arousal_level;
