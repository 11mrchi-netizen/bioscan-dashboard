alter table masturbation_log
  add column watched_porn boolean,
  add column load_size smallint check (load_size between 1 and 5),
  add column orgasm_intensity smallint check (orgasm_intensity between 0 and 10);
