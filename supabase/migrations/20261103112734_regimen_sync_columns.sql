create or replace function public.sync_supplement_columns() returns trigger
language plpgsql set search_path = public as $$
begin
  if tg_op = 'INSERT' and new.schedule is null and cardinality(new.slots) = 0
     or tg_op = 'UPDATE' and (old.time_of_day is distinct from new.time_of_day or old.every_n_days is distinct from new.every_n_days)
        and old.slots is not distinct from new.slots and old.schedule is not distinct from new.schedule then
    new.slots := case when new.time_of_day = 'as-needed' then '{}'::text[] else array[new.time_of_day] end;
    new.schedule := case
                      when new.time_of_day = 'as-needed' then '{"type":"as_needed"}'::jsonb
                      when new.every_n_days is not null then jsonb_build_object('type', 'every_n_days', 'n', new.every_n_days)
                      else '{"type":"daily"}'::jsonb
                    end;
  end if;
  return new;
end
$$;

drop trigger if exists supplements_sync_columns on public.supplements;
create trigger supplements_sync_columns
  before insert or update on public.supplements
  for each row execute function public.sync_supplement_columns();
