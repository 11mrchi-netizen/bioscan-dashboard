-- Scoring system (docs/scoring-system/README.md, FUEL › gut): the evening check-in asks how the gut was that day.
--
--   gut_comfort   0 (rough) to 4 (comfortable); null = not answered.
--   gut_symptoms  the symptoms ticked that day; an empty array = none (NOTHING ELSE marks it), null = not answered.
--
-- Bowel movement times are not asked here: each stool_log entry already carries its occurred_at, which the stool form
-- fills with the time of logging.
--
-- Additive and nullable: existing rows and older app versions are unaffected.
-- Rollback: alter table public.evening_checkins drop column gut_symptoms, drop column gut_comfort;

alter table public.evening_checkins
  add column gut_comfort smallint check (gut_comfort is null or gut_comfort between 0 and 4),
  add column gut_symptoms text[] check (
    gut_symptoms is null
    or gut_symptoms <@ array['bloating', 'cramps', 'reflux', 'urgency', 'gas', 'nausea']::text[]
  );
