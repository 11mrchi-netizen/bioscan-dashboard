alter table public.supplement_log drop constraint supplement_log_source_check;
alter table public.supplement_log add constraint supplement_log_source_check check (source = any (array['manual','notification','quick_log','scan']));
