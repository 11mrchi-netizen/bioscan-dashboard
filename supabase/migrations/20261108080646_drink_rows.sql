-- Drinks by id (the usual drinks, a scanned product) in the same shape as search_drinks, and search_drinks rebuilt on
-- it. A barcode now finds its product even when the product has no beverage_class yet (a label just scanned): the app
-- then asks which kind of drink it is.
--
-- public.drink_rows(ids): one row per id, in the order given; per-100 ml nutrients, servings in ml, caffeine estimated.
-- public.search_drinks(q, n): unchanged signature and result; ranks the ids, then reads them through drink_rows.
--
-- Rollback: re-run 20261108_drinks_catalog.sql's search_drinks; drop function public.drink_rows(bigint[]);

create or replace function public.drink_rows(ids bigint[])
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
  with wanted as (select w.id, w.ord from unnest(ids) with ordinality as w(id, ord)),
  hits as (
    select f.*, s.name as source_name, w.ord,
      coalesce((select mi.beverage_class from meal_items mi where mi.food_id = f.id and mi.is_beverage and mi.beverage_class is not null
                order by mi.created_at desc limit 1), f.beverage_class) as cls,
      coalesce((select avg(sv.grams / sv.ml) from food_servings sv where sv.food_id = f.id and sv.ml > 0 and sv.grams > 0), 1.0) as g_per_ml
    from wanted w
    join foods f on f.id = w.id
    join food_sources s on s.id = f.food_source_id
  ),
  per as (
    select fn.food_id, fn.nutrient, bool_or(fn.estimated) as est,
      max(case when fn.basis_unit = 'ml' then fn.amount * 100 / nullif(fn.basis_qty, 0)
               else fn.amount * 100 / nullif(fn.basis_qty, 0) * h.g_per_ml end) as v
    from food_nutrients fn join hits h on h.id = fn.food_id
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
    coalesce((select jsonb_agg(jsonb_build_object('name', sv.serving_name, 'ml', round(coalesce(sv.ml, sv.grams / h.g_per_ml))) order by coalesce(sv.ml, sv.grams / h.g_per_ml))
              from food_servings sv where sv.food_id = h.id and (sv.ml > 0 or sv.grams > 0)), '[]'::jsonb)
  from hits h
  order by h.ord;
$$;

revoke all on function public.drink_rows(bigint[]) from public, anon;
grant execute on function public.drink_rows(bigint[]) to authenticated, service_role;

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
  ranked as (
    select f.id, row_number() over (order by
      (f.barcode = p.t) desc nulls last,
      m.mine desc,
      f.drink_default desc,
      greatest(similarity(f.name, p.t), similarity(coalesce(f.name_zh, ''), p.t), similarity(coalesce(f.brand, ''), p.t)) desc,
      length(f.name)) as rn
    from foods f
    cross join p
    left join lateral (select exists (select 1 from meal_items mi where mi.food_id = f.id and mi.is_beverage) as mine) m on true
    where (
        (length(p.t) = 0 and f.drink_default)
        or f.barcode = p.t
        or (length(p.t) >= 2 and (f.beverage_class is not null or m.mine) and (
          f.name ilike p.pat
          or f.name_zh ilike p.pat
          or f.brand ilike p.pat
          or exists (select 1 from unnest(f.aliases) a where a ilike p.pat)
          or similarity(f.name, p.t) > 0.25
        ))
      )
  )
  select * from public.drink_rows(array(
    select id from ranked where rn <= least(greatest(coalesce(n, 12), 1), 30) order by rn));
$$;
