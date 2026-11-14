create extension if not exists pg_trgm;

create index if not exists exercise_library_name_trgm_idx
  on public.exercise_library using gin (name gin_trgm_ops);

-- Runs as the calling role (no security definer), so it's bound by
-- exercise_library's own select policy (any authenticated user) -- not a
-- privilege escalation path.
create or replace function public.match_exercise_library(search_query text, match_limit int default 5)
returns table (id text, name text, similarity real)
language sql stable
as $$
  select id, name, similarity(name, search_query) as similarity
  from public.exercise_library
  where name % search_query
  order by similarity desc
  limit match_limit;
$$;
