-- DAV-115: all 24 exercise_sessions rows currently linked from zepp_workout_detail
-- are source='zepp' shadow duplicates created by the 8-hour UTC-vs-local-labeled
-- timestamp bug (see zepp-extract's updated comment). None of them are real,
-- user-confirmed unmatched-by-Health-Connect workouts -- confirmed every one of
-- the 24 synced workouts already exists as a real Health-Connect session, just
-- 8 hours off from where the buggy reconcile looked for it. Safe to delete and
-- let a corrected re-sync re-match them against their real Health-Connect rows.
update zepp_workout_detail set exercise_session_id = null
where exercise_session_id in (select id from exercise_sessions where source = 'zepp');

delete from exercise_sessions where source = 'zepp';
