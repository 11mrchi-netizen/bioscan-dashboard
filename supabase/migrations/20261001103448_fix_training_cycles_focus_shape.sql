-- Pre-existing crash bug found during today's testing: scripts/notion-migration/
-- import_training_cycles.py wrote `focus` as a free-form metadata object
-- ({"source":"notion","primary":...,"template":...}), but domain/TrainingCycle.kt's
-- real contract is `focus: List<FocusEntry>` (an array of {quality, weight, role}).
-- TrainingCyclesRepository.loadCycles() throws JsonDecodingException on any row
-- where focus isn't an array, crashing every screen that loads training cycles
-- (Training tab, Daily Readiness). Not discarding the Notion data -- it's
-- preserved by folding it into `notes` (which already holds this script's own
-- activity-count JSON) -- `focus` becomes a valid empty array rather than a
-- guessed FocusQuality/FocusRole mapping, since "max_strength"/"aerobic_maintenance"
-- etc. don't map cleanly onto the existing enum without a real product decision.
update public.training_cycles
set
  notes = jsonb_build_object('activity_counts', notes::jsonb, 'notion_import_focus', focus)::text,
  focus = '[]'::jsonb
where jsonb_typeof(focus) != 'array';
