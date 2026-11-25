-- Sex tracking, follow-up to 20261114_encounters_unified.sql (same day, before any row carried the new columns):
--
--  - encounters.format becomes text[]: an encounter can be several formats at once (a gangbang that is also a blowbang).
--  - encounters.kinks is dropped: activities and kink are one list, stored in encounters.activities.
--  - people.activities: the spreadsheet import left the same activity under two casings ("Group Sex" / "Group sex",
--    "Rimming (Received)" / "Rimming (received)", "Other Kinks" / "Other kinks") and "Cum Play" in title case. They are
--    folded to sentence case, in place, keeping each person's order. No activity is removed from a profile.
--
-- Re-runnable. Rollback (the people rename is not undone: the old casing carried no information):
--   alter table public.encounters alter column format type text using format[1];
--   alter table public.encounters add column kinks text[];

do $$
begin
  if (select data_type from information_schema.columns
      where table_schema = 'public' and table_name = 'encounters' and column_name = 'format') = 'text' then
    alter table public.encounters alter column format type text[]
      using case when format is null then null else array[format] end;
  end if;
end $$;

alter table public.encounters drop column if exists kinks;

update public.people p
set activities = (
  select array_agg(x.label order by x.first_at)
  from (
    select m.label, min(u.ord) as first_at
    from unnest(p.activities) with ordinality as u (label, ord)
    cross join lateral (
      select case lower(u.label)
        when 'group sex' then 'Group sex'
        when 'rimming (received)' then 'Rimming (received)'
        when 'other kinks' then 'Other kinks'
        when 'cum play' then 'Cum play'
        else u.label
      end as label
    ) m
    group by m.label
  ) x
)
where p.activities && array['Group Sex', 'Rimming (Received)', 'Other Kinks', 'Cum Play'];
