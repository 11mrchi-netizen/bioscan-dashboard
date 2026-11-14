ALTER TABLE exercise_sessions DROP CONSTRAINT exercise_sessions_type_check;
ALTER TABLE exercise_sessions ADD CONSTRAINT exercise_sessions_type_check
  CHECK (type IN ('run','walk','hike','ride','swim','strength','yoga','other','conditioning'));
