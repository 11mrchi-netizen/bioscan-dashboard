-- The minute heart rate stored by zepp-extract before the decoder fix (zepp-extract/minuteHr.ts) kept the band's
-- "no reading" bytes as heart rates: every minute the band was off the wrist was stored at 254 or 255 bpm, and the Heart
-- tab charted them. This drops every minute outside 30..220 bpm from wearable_daily.hr_timeseries (the range the decoder
-- now keeps) and empties a day with no plausible minute left (null, as the decoder writes it). Runs once; a later
-- extraction writes the cleaned series anyway.
--
-- The column was created outside the migrations, so its type (json or jsonb) is read from the catalogue and the update
-- casts back to it.
--
-- Rollback: none needed and none possible (the dropped minutes were markers, not readings); re-extracting a day from
--           Zepp rebuilds its series.

do $$
declare
  col_type text;
begin
  select data_type into col_type
  from information_schema.columns
  where table_schema = 'public' and table_name = 'wearable_daily' and column_name = 'hr_timeseries';

  if col_type is null or col_type not in ('json', 'jsonb') then
    raise notice 'wearable_daily.hr_timeseries is %, nothing to clean', coalesce(col_type, 'missing');
    return;
  end if;

  execute format($q$
    update public.wearable_daily w
    set hr_timeseries = cleaned.series::%1$s
    from (
      select d.id,
             (select jsonb_agg(p order by ord)
              from jsonb_array_elements(d.hr_timeseries::jsonb) with ordinality as e(p, ord)
              where jsonb_typeof(p -> 'bpm') = 'number'
                and (p ->> 'bpm')::numeric between 30 and 220) as series
      from public.wearable_daily d
      where d.hr_timeseries is not null
        and jsonb_typeof(d.hr_timeseries::jsonb) = 'array'
        and exists (
          select 1 from jsonb_array_elements(d.hr_timeseries::jsonb) as x(p)
          where coalesce(jsonb_typeof(p -> 'bpm'), '') <> 'number' or (p ->> 'bpm')::numeric not between 30 and 220)
    ) cleaned
    where w.id = cleaned.id
  $q$, col_type);
end $$;
