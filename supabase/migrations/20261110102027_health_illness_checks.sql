-- Health: illnesses and infections as their own kind of concern, the symptom check, and the symptoms they need
-- (docs/health-concerns/README.md, "Illness, infections and the symptom check").
--
-- Rollback: delete from public.health_vocab where vocab_version = '1.1.0' (after removing signals that use them);
--           alter table public.health_observations drop column assessment, drop constraint
--             health_observations_concern_scope_check, add constraint health_observations_check
--             check ((scope = 'sweep') = (concern_id is null)); restore health_observations_scope_check without 'check'
--             (after removing check rows); alter table public.health_concerns drop column illness_type, drop column category;

-- 1. What a concern is: an injury, an infection or another illness. Null = read from kind (anatomical = injury,
--    systemic = illness). illness_type is the kind of illness in the app's words (cold, flu_like, covid, stomach_bug,
--    sinus, ear, throat, chest, urinary, skin, allergy, migraine, other).
alter table public.health_concerns add column if not exists category text
  check (category is null or category in ('injury', 'infection', 'illness'));
alter table public.health_concerns add column if not exists illness_type text
  check (illness_type is null or illness_type ~ '^[a-z_]{1,40}$');

-- 2. A symptom check: a small ache or discomfort logged through the questionnaire. It belongs to no concern and says
--    nothing about the others (unlike a sweep, it does not make them symptom-free), so it makes no episode and caps no
--    score. Its signals can be given to a concern later (TRACK AS ILLNESS / INJURY), once, as any signal.
alter table public.health_observations drop constraint if exists health_observations_scope_check;
alter table public.health_observations add constraint health_observations_scope_check
  check (scope in ('concern', 'sweep', 'session', 'check'));
alter table public.health_observations drop constraint if exists health_observations_check;
alter table public.health_observations add constraint health_observations_concern_scope_check
  check ((scope in ('sweep', 'check')) = (concern_id is null));
-- The questionnaire's answers and what it concluded: {"version", "area", "answers", "flags", "patterns", "outcome"}.
-- Append-only like the rest of the row (the guard trigger compares every column).
alter table public.health_observations add column if not exists assessment jsonb
  check (assessment is null or jsonb_typeof(assessment) = 'object');

-- 3. Vocabulary 1.1.0: the symptoms of infections and of the small things (health-vocab.json is the copy).
insert into public.health_vocab (code, kind, label, parent, sided, vocab_version) values
  ('sym.sneezing', 'symptom', 'Sneezing', 'respiratory', false, '1.1.0'),
  ('sym.sinus_pressure', 'symptom', 'Sinus pressure', 'respiratory', false, '1.1.0'),
  ('sym.loss_smell', 'symptom', 'Loss of taste or smell', 'respiratory', false, '1.1.0'),
  ('sym.chest_tightness', 'symptom', 'Chest tightness', 'respiratory', false, '1.1.0'),
  ('sym.breathless', 'symptom', 'Short of breath', 'respiratory', false, '1.1.0'),
  ('sym.earache', 'symptom', 'Earache', 'head', false, '1.1.0'),
  ('sym.eye_irritation', 'symptom', 'Itchy or red eyes', 'head', false, '1.1.0'),
  ('sym.chills', 'symptom', 'Chills', 'general', false, '1.1.0'),
  ('sym.heartburn', 'symptom', 'Heartburn', 'digestive', false, '1.1.0'),
  ('sym.bloating', 'symptom', 'Bloating', 'digestive', false, '1.1.0'),
  ('sym.diarrhoea', 'symptom', 'Diarrhoea', 'digestive', false, '1.1.0'),
  ('sym.constipation', 'symptom', 'Constipation', 'digestive', false, '1.1.0'),
  ('sym.stomach_cramps', 'symptom', 'Stomach cramps', 'digestive', false, '1.1.0'),
  ('sym.vomiting', 'symptom', 'Vomiting', 'digestive', false, '1.1.0'),
  ('sym.rash', 'symptom', 'Rash or itch', 'skin', false, '1.1.0'),
  ('sym.urinary', 'symptom', 'Burning when peeing', 'urinary', false, '1.1.0')
on conflict (code) do nothing;
