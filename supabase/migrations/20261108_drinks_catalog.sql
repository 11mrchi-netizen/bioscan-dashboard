-- Real drinks for the drink log.
--
-- food_nutrients.estimated: a value the source did not measure (Taiwan FDA's table has no caffeine; tea and coffee rows
--   carry a per-100 ml estimate from the matching USDA brew). The app says "estimated" next to it.
-- foods.drink_default: the one catalog drink standing for a whole beverage class ("Coffee" with nothing more said).
-- meal_items.beverage_class: the class a logged drink was saved as (its retention factor), kept on the item so a drink
--   with no catalog food (or a scanned product without a class) still knows what it was.
-- public.search_drinks(q, n): drinks only (a beverage_class, or a food the user has logged as a drink), with nutrients
--   per 100 ml (per-100 g rows converted with the food's own g/ml from its servings), the servings in ml, and whether
--   caffeine is estimated. security invoker: the catalog's read RLS and the user's own meal_items apply.
--
-- Data: scripts/nutrition/import_drinks.py writes the rows (USDA SR Legacy drinks, Taiwan FDA drinks).
--
-- Rollback: drop function public.search_drinks(text, integer);
--           alter table public.meal_items drop column beverage_class;
--           alter table public.foods drop column drink_default;
--           alter table public.food_nutrients drop column estimated;

alter table public.food_nutrients add column if not exists estimated boolean not null default false;
alter table public.foods add column if not exists drink_default boolean not null default false;
alter table public.meal_items add column if not exists beverage_class text;

create index if not exists foods_beverage_class_idx on public.foods (beverage_class) where beverage_class is not null;
create unique index if not exists foods_one_drink_default_per_class on public.foods (beverage_class) where drink_default;

create or replace function public.search_drinks(q text, n integer default 12)
returns table (
  id bigint, name text, name_zh text, brand text, barcode text, source text,
  beverage_class text, beverage_subtype text, drink_default boolean,
  kcal numeric, sugar_g numeric, carbs_g numeric, protein_g numeric, fat_g numeric, sodium_mg numeric,
  caffeine_mg numeric, alcohol_g numeric, water_ml numeric, caffeine_estimated boolean,
  servings jsonb
)
language sql
stable
security invoker
set search_path = public
as $$
  with p as (
    select trim(coalesce(q, '')) as t,
      '%' || replace(replace(replace(trim(coalesce(q, '')), '\', '\\'), '%', '\%'), '_', '\_') || '%' as pat
  ),
  hits as (
    select f.*, s.name as source_name,
      coalesce((select mi.beverage_class from meal_items mi where mi.food_id = f.id and mi.is_beverage
                order by mi.created_at desc limit 1), f.beverage_class) as cls,
      exists (select 1 from meal_items mi where mi.food_id = f.id and mi.is_beverage) as mine
    from foods f
    join food_sources s on s.id = f.food_source_id
    cross join p
    where (f.beverage_class is not null or exists (select 1 from meal_items mi where mi.food_id = f.id and mi.is_beverage))
      and (
        (length(p.t) = 0 and f.drink_default)
        or (length(p.t) >= 2 and (
          f.barcode = p.t
          or f.name ilike p.pat
          or f.name_zh ilike p.pat
          or f.brand ilike p.pat
          or exists (select 1 from unnest(f.aliases) a where a ilike p.pat)
          or similarity(f.name, p.t) > 0.25
        ))
      )
  ),
  dens as (
    select h.id, coalesce((select avg(sv.grams / sv.ml) from food_servings sv
                           where sv.food_id = h.id and sv.ml > 0 and sv.grams > 0), 1.0) as g_per_ml
    from hits h
  ),
  per as (
    select fn.food_id, fn.nutrient, bool_or(fn.estimated) as est,
      max(case when fn.basis_unit = 'ml' then fn.amount * 100 / nullif(fn.basis_qty, 0)
               else fn.amount * 100 / nullif(fn.basis_qty, 0) * d.g_per_ml end) as v
    from food_nutrients fn join dens d on d.id = fn.food_id
    group by fn.food_id, fn.nutrient
  )
  select h.id, h.name, h.name_zh, h.brand, h.barcode, h.source_name, h.cls, h.beverage_subtype, h.drink_default,
    (select v from per where food_id = h.id and nutrient = 'calories'),
    (select v from per where food_id = h.id and nutrient = 'sugar'),
    (select v from per where food_id = h.id and nutrient = 'carbs'),
    (select v from per where food_id = h.id and nutrient = 'protein'),
    (select v from per where food_id = h.id and nutrient = 'fat'),
    (select v from per where food_id = h.id and nutrient = 'sodium'),
    (select v from per where food_id = h.id and nutrient = 'caffeine'),
    (select v from per where food_id = h.id and nutrient = 'alcohol'),
    (select v from per where food_id = h.id and nutrient = 'water'),
    coalesce((select est from per where food_id = h.id and nutrient = 'caffeine'), false),
    coalesce((select jsonb_agg(jsonb_build_object('name', sv.serving_name, 'ml', round(coalesce(sv.ml, sv.grams / (select g_per_ml from dens where dens.id = h.id)))) order by coalesce(sv.ml, sv.grams))
              from food_servings sv where sv.food_id = h.id and (sv.ml > 0 or sv.grams > 0)), '[]'::jsonb)
  from hits h cross join p
  order by
    (h.barcode = p.t) desc nulls last,
    h.mine desc,
    h.drink_default desc,
    greatest(similarity(h.name, p.t), similarity(coalesce(h.name_zh, ''), p.t), similarity(coalesce(h.brand, ''), p.t)) desc,
    length(h.name)
  limit least(greatest(coalesce(n, 12), 1), 30);
$$;

revoke all on function public.search_drinks(text, integer) from public, anon;
grant execute on function public.search_drinks(text, integer) to authenticated, service_role;
