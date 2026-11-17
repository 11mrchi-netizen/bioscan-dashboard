-- Food logging: type-to-search across the app's own catalog (barcode products, scanned labels, USDA rows), and
-- remembering a catalog food as the user's choice for a query.
--
-- public.search_foods(q, n): name, Chinese name, aliases and exact barcode; trigram-ranked. security invoker, so the
-- catalog's SELECT-only RLS applies as usual. Called by nutrition-cronometer-lookup's suggest mode.
-- food_match_memory can now point at either a Cronometer food or a catalog food (exactly one).
--
-- Rollback: drop function public.search_foods(text, integer);
--           drop index public.foods_name_trgm_idx; drop index public.foods_name_zh_trgm_idx;
--           alter table public.food_match_memory drop constraint food_match_memory_one_food;
--           alter table public.food_match_memory drop column catalog_food_id;
--           (cronometer_food_id can only be made not null again once no row relies on catalog_food_id)

create index if not exists foods_name_trgm_idx on public.foods using gin (name public.gin_trgm_ops);
create index if not exists foods_name_zh_trgm_idx on public.foods using gin (name_zh public.gin_trgm_ops);

create or replace function public.search_foods(q text, n integer default 8)
returns table (id bigint, name text, name_zh text, brand text, barcode text, source text, kcal_per_100g numeric)
language sql
stable
security invoker
set search_path = public
as $$
  with p as (
    select
      trim(q) as t,
      '%' || replace(replace(replace(trim(q), '\', '\\'), '%', '\%'), '_', '\_') || '%' as pat
  )
  select f.id, f.name, f.name_zh, f.brand, f.barcode, s.name,
    (select fn.amount * 100 / nullif(fn.basis_qty, 0)
       from public.food_nutrients fn where fn.food_id = f.id and fn.nutrient = 'calories' limit 1)
  from public.foods f
  join public.food_sources s on s.id = f.food_source_id
  cross join p
  where length(p.t) >= 2 and (
    f.barcode = p.t
    or f.name ilike p.pat
    or f.name_zh ilike p.pat
    or exists (select 1 from unnest(f.aliases) a where a ilike p.pat)
    or similarity(f.name, p.t) > 0.3
  )
  order by
    (f.barcode = p.t) desc nulls last,
    greatest(similarity(f.name, p.t), similarity(coalesce(f.name_zh, ''), p.t)) desc,
    length(f.name)
  limit least(greatest(coalesce(n, 8), 1), 20);
$$;

revoke all on function public.search_foods(text, integer) from public, anon;
grant execute on function public.search_foods(text, integer) to authenticated, service_role;

alter table public.food_match_memory alter column cronometer_food_id drop not null;
alter table public.food_match_memory add column catalog_food_id bigint references public.foods (id) on delete cascade;
alter table public.food_match_memory add constraint food_match_memory_one_food
  check (num_nonnulls(cronometer_food_id, catalog_food_id) = 1);
