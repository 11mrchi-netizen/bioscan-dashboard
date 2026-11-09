alter table training_cycles
  add column if not exists goal_metric text,
  add column if not exists starting_value double precision,
  add column if not exists target_value double precision;

comment on column training_cycles.goal_metric is 'DAV-291: free-text metric id this cycle is training toward (e.g. "5k_time_sec", "squat_1rm_kg"), same open-vocabulary convention as domain/achievement/Achievement.metric. Null for a cycle with no stated numeric goal.';
comment on column training_cycles.starting_value is 'DAV-291: goal_metric value at the cycle start. Null unless goal_metric is set.';
comment on column training_cycles.target_value is 'DAV-291: goal_metric value this cycle is aiming for. Null unless goal_metric is set.';
