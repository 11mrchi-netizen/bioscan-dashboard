-- Redesign: the hypnogram needs the night's timed sleep stages, which zepp-extract decoded and then reduced to minutes.
--
-- sleep_daily.stage_intervals: [[startOffsetMin, durationMin, mode], ...] in time order, offsets in minutes from the night's
--   first stage (the sleep onset, = sleep_daily.bedtime). mode is the Zepp stage code: 4 light, 5 deep, 7 awake, 8 REM.
--   The band reports each stage as inclusive start..stop minutes, so duration = stop - start + 1.
-- sleep_daily.tz_offset_min: the band's UTC offset in minutes for that night (summary.tz / 60). The overnight heart rate is
--   stored as minutes from LOCAL midnight (wearable_daily.hr_timeseries), so joining it to the night needs this.
--
-- Backfill: every night already pulled has its raw band_data in zepp_raw_extracts, so no device access is needed. Nights whose
-- summary will not decode are skipped (left null); the app falls back to stage minutes for them.
--
-- Rollback: alter table public.sleep_daily drop column stage_intervals, drop column tz_offset_min;

alter table public.sleep_daily add column if not exists stage_intervals jsonb;
alter table public.sleep_daily add column if not exists tz_offset_min integer;

do $$
declare
  r record;
  summ jsonb;
  first_start integer;
  iv jsonb;
begin
  for r in
    select e.query_date, e.raw_body::jsonb as body
    from public.zepp_raw_extracts e
    where e.metric = 'band_data'
  loop
    begin
      summ := convert_from(decode(r.body -> 'data' -> 0 ->> 'summary', 'base64'), 'UTF8')::jsonb;
      if jsonb_typeof(summ -> 'slp' -> 'stage') is distinct from 'array' or jsonb_array_length(summ -> 'slp' -> 'stage') = 0 then
        continue;
      end if;
      select min((q ->> 'start')::integer) into first_start from jsonb_array_elements(summ -> 'slp' -> 'stage') q;
      select jsonb_agg(
               jsonb_build_array((st ->> 'start')::integer - first_start, (st ->> 'stop')::integer - (st ->> 'start')::integer + 1, (st ->> 'mode')::integer)
               order by (st ->> 'start')::integer)
        into iv
        from jsonb_array_elements(summ -> 'slp' -> 'stage') st;
      update public.sleep_daily
         set stage_intervals = iv,
             tz_offset_min = nullif(summ ->> 'tz', '')::integer / 60
       where date = r.query_date;
    exception when others then
      raise notice 'night % skipped: %', r.query_date, sqlerrm;
    end;
  end loop;
end $$;
