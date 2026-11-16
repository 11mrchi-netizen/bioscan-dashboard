-- Sex tracking: `encounters` becomes the one record per partnered event. Until now partnered sex was logged twice over:
-- `encounters` (a meetup, with a partner, a time and a place) and `sexual_activity_daily` activity_type 'intercourse'
-- (one row per day, per-round detail in jsonb, the partner buried in the jsonb, no time).
--
--  - New columns hold what the day row could not: the place, whether the encounter was sexual, its format (1 on 1,
--    threesome, ...), rounds and orgasms, and the tag lists (positions, toys, kinks, protection, ejaculation location).
--    The vocabularies live in the app (domain/Intimacy.kt).
--  - A group encounter is one row per partner (person_id stays a single column), so per-partner detail such as
--    protection stays per partner.
--  - The intercourse day rows are COPIED: one encounter per (day row, partner), rounds = that partner's instances.
--    The source rows are left in place; the app stops reading them. Delete them once the copy has been checked:
--      delete from public.sexual_activity_daily where activity_type = 'intercourse';
--  - Re-runnable: columns are added if missing, and a copied row is keyed by sheet_encounter_id 'sa:<row id>:<partner id>'.
--
-- Rollback:
--   delete from public.encounters where sheet_encounter_id like 'sa:%';
--   alter table public.encounters drop column place_id, drop column sexual, drop column format, drop column rounds, drop column orgasm_count,
--     drop column orgasm_intensity, drop column positions, drop column toys, drop column kinks, drop column protection,
--     drop column ejaculation_location;

alter table public.encounters
  add column if not exists place_id uuid references public.places (id) on delete set null,
  add column if not exists sexual boolean not null default false,
  add column if not exists format text,
  add column if not exists rounds smallint check (rounds >= 0),
  add column if not exists orgasm_count smallint check (orgasm_count >= 0),
  add column if not exists orgasm_intensity smallint check (orgasm_intensity between 0 and 10),
  add column if not exists positions text[],
  add column if not exists toys text[],
  add column if not exists kinks text[],
  add column if not exists protection text[],
  add column if not exists ejaculation_location text[];

comment on column public.encounters.sexual is 'True when the encounter included sex; false for a date or meetup without.';
comment on column public.encounters.orgasm_intensity is 'Strongest orgasm of the encounter, 0-10.';

insert into public.encounters
  (user_id, sheet_encounter_id, person_id, date, status, sexual, rounds, orgasm_count, orgasm_intensity, notes)
select
  g.user_id,
  'sa:' || g.sa_id || ':' || coalesce(g.partner, 'none'),
  -- left join: a partner deleted since the row was logged copies as no partner instead of failing the foreign key
  p.id,
  g.date,
  'logged',
  true,
  g.rounds,
  g.orgasms,
  g.intensity,
  nullif(concat_ws(E'\n', g.instance_notes, nullif(g.day_notes, '')), '')
from (
  select
    s.id as sa_id, s.user_id, s.date, s.notes as day_notes,
    e.value ->> 'partner_id' as partner,
    count(*) as rounds,
    count(*) filter (where (e.value ->> 'orgasm')::boolean) as orgasms,
    max((e.value ->> 'orgasmIntensity')::smallint) as intensity,
    string_agg(nullif(e.value ->> 'notes', ''), E'\n' order by e.ordinality) as instance_notes
  from public.sexual_activity_daily s
  cross join lateral jsonb_array_elements(s.instances) with ordinality as e (value, ordinality)
  where s.activity_type = 'intercourse'
  group by s.id, s.user_id, s.date, s.notes, e.value ->> 'partner_id'
) g
left join public.people p on p.id = g.partner::bigint
where not exists (
  select 1 from public.encounters x
  where x.user_id = g.user_id
    and x.sheet_encounter_id = 'sa:' || g.sa_id || ':' || coalesce(g.partner, 'none')
);
