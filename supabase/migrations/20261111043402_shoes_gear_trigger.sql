-- A workout's Zepp shoe code, filled from its stored sport_history summary when the extractor did not send one
-- (an older deploy, or a re-decode that rewrites the row). Looks only at the summaries of the days around the workout.
--
-- Rollback: drop trigger zepp_workout_detail_gear on public.zepp_workout_detail; drop function public.zepp_workout_gear_fill();

create or replace function public.zepp_workout_gear_fill() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.gear_id is null and new.zepp_track_id is not null then
    select max(nullif(s->>'gearId', '')) into new.gear_id
    from public.zepp_raw_extracts r, jsonb_array_elements(coalesce(r.raw_body->'data'->'summary', '[]'::jsonb)) s
    where r.user_id = new.user_id and r.metric = 'sport_history'
      and (new.start_time is null or r.query_date::date between (new.start_time::date - 1) and (new.start_time::date + 1))
      and s->>'trackid' = new.zepp_track_id;
  end if;
  return new;
end $$;

drop trigger if exists zepp_workout_detail_gear on public.zepp_workout_detail;
create trigger zepp_workout_detail_gear before insert or update on public.zepp_workout_detail
  for each row execute function public.zepp_workout_gear_fill();
