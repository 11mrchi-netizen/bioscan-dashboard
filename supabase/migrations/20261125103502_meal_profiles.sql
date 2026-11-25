-- Meal profile (docs/nutrition/meal-profile.md): separate indexes for every meal, never one combined score.
--
--   food_group_rules  shared keyword rules that put an ingredient in one food group (with a typical glycemic index) and
--                     add gut-relevant tags (gluten, lactose, high FODMAP, fried, spicy, ultra-processed, ...). The
--                     first group rule by priority wins; every matching rule adds its tags.
--   meal_profiles     a view, one row per meal, computed on read from meals + meal_items (so an edit is reflected at
--                     once and nothing needs a backfill). security_invoker: each person reads only their own meals.
--
-- Index definitions (all null when the inputs are missing, never guessed):
--   energy_density_kcal_g   food kcal / food grams, beverages left out; needs grams on every food ingredient (>= 30 g).
--   protein_per_100kcal     protein g per 100 kcal (meals of 50 kcal or more).
--   carbs_per_100kcal       carbohydrate g per 100 kcal.
--   fiber_per_1000kcal      fibre g per 1,000 kcal; null when the meal has no fibre value.
--   glycemic_load_est       (carbs - fibre) x GI / 100, GI the kcal-weighted mean of the ingredients' typical GI.
--                           Estimated (typical GI per food group, International Tables of GI, Atkinson 2021).
--   fullness_factor_est     Fullness Factor per 100 g (0.5-5; Nutrition Data's formula from kcal, protein, fibre and fat
--                           per 100 g), only for meals without beverages and with grams. Estimated.
--   macro_mismatch          kcal more than 35% away from 4 x protein + 4 x carbs + 9 x fat + 7 x alcohol (100 kcal or
--                           more): the entry is probably wrong and worth a look.
--
-- Rollback: drop view public.meal_profiles; drop table public.food_group_rules;

create table public.food_group_rules (
  id bigint generated always as identity primary key,
  pattern text not null,              -- case-insensitive Postgres regex, matched against "<food> <query>" of an ingredient
  unless_pattern text,                -- the rule does not apply when this also matches (soy milk is not dairy)
  food_group text,                    -- null: the rule only adds tags
  gi numeric check (gi is null or gi between 0 and 110),
  tags text[] not null default '{}',
  priority integer not null default 100,
  note text
);

alter table public.food_group_rules enable row level security;
create policy food_group_rules_read on public.food_group_rules for select to authenticated using (true);

insert into public.food_group_rules (priority, food_group, gi, tags, pattern, unless_pattern) values
  -- composites and specific foods first
  (10, 'protein_powder', null, '{}', '(whey|pea|casein|soy|plant|egg white)[ ,]+protein|protein (powder|shake|isolate)|\misolate\M|蛋白', null),
  (11, 'sweets', 62, '{ultra_processed}', 'protein bar|cereal bar|granola bar|energy bar', null),
  (12, 'sweets', 62, '{ultra_processed}', 'cake|cookie|biscuit|brownie|muffin|donut|doughnut|pastr|croissant|cream puff|swiss roll|chocolate|candy|sweets|ice cream|gelato|pudding|dessert|\mtart\M|\mpie\M|waffle|pancake|cheesecake|mochi|banana bread|custard|tiramisu|macaron|jelly|marshmallow|scone|digestive', null),
  (13, 'snacks_salty', 70, '{ultra_processed}', 'cracker|\mchips\M|crisps|pretzel|popcorn|rice cake', null),
  (14, 'sugary_drinks', 60, '{}', 'soda|\mcola\M|juice|lemonade|soft drink|sweetened|bubble tea|\mboba\M|milk tea|sports drink|gatorade|energy drink|smoothie|ovaltine|果汁|汽水|奶茶', null),
  (15, 'alcohol', null, '{alcohol}', '\mbeer\M|\mwine\M|whisk|vodka|\mgin\M|\mrum\M|\msake\M|soju|cocktail|liquor|spirits|baijiu|kaoliang|champagne|cider', null),
  (16, 'coffee_tea', null, '{}', 'coffee|espresso|latte|cappuccino|americano|\mtea\M|matcha|chicory|teccino|咖啡', null),
  (17, 'plant_milk', 45, '{}', '(soy|almond|oat|coconut|rice|pea)[ ,]*(milk|drink|beverage)', null),
  (19, 'water_electrolytes', null, '{}', 'electrolyte|rehydration|\mwater\M|sparkling|mineral water|\mors\M', null),
  (18, 'processed_meat', null, '{ultra_processed}', 'sausage|bacon|\mham\M|salami|hot ?dog|pepperoni|jerky|luncheon|\mspam\M|nugget', null),
  (20, 'whole_grains', 66, '{}', 'brown rice|rice, brown|wild rice|red rice|black rice|multigrain rice', null),
  (21, 'refined_grains', 50, '{}', 'pasta|spaghetti|macaroni|penne|lasagn|noodle|ramen|udon|soba|vermicelli|sushi|nigiri|\mmaki\M', null),
  (22, 'whole_grains', 55, '{}', '\moat|rolled oats|\mrye\M|rye flakes|barley|quinoa|buckwheat|whole ?wheat|wholemeal|whole ?grain|\mbran\M|muesli|granola|millet|sorghum|spelt|farro|bulgur', null),
  (23, 'refined_grains', 72, '{}', '\mrice\M|bread|bagel|\mbuns?\M|tortilla|wrap|couscous|dumpling|\mbao\M|congee|porridge|flour|baguette|toast|pizza|pita|naan|cereal|cornflakes', null),
  (30, 'starchy_veg', 63, '{}', 'sweet potato|\myam\M|taro|pumpkin|squash|cassava', null),
  (31, 'starchy_veg', 78, '{}', 'potato|fries|\mcorn\M|maize|polenta', null),
  (40, 'poultry', null, '{}', 'chicken|turkey|\mduck\M', null),
  (41, 'meat', null, '{}', '\mbeef|steak|\mpork\M|lamb|mutton|\mveal\M|venison|brisket|ribs|\mpig\M|pork belly', null),
  (42, 'fish_seafood', null, '{}', '\mfish|salmon|tuna|\mcod\M|mackerel|sardine|shrimp|prawn|squid|tilapia|seafood|\mcrab|oyster|\mclam|mussel|anchov|scallop|octopus|trout|seabass|sea bass', null),
  (39, 'eggs', null, '{}', '\meggs?\M|omelet|omelette|frittata', null),
  (50, 'cheese', null, '{dairy}', 'cheese|cheddar|mozzarella|parmesan|\mfeta\M|halloumi|brie|gouda', null),
  (51, 'dairy', 35, '{dairy}', '\mmilk|yogh?urt|kefir|\mcream\M|skyr|quark|ricotta|cottage|優格|優酪|牛奶|鮮奶', '(soy|oat|almond|coconut|rice|pea|plant)[ ,]*(milk|drink|beverage)|lactose[- ]free|dairy[- ]free|vegan'),
  (60, 'legumes', 30, '{}', '\mbeans?\M|lentil|chickpea|hummus|\mtofu\M|tempeh|edamame|natto|\mpeas\M|split pea|\mdal\M|\mdhal\M|soybean', null),
  (70, 'nuts_seeds', 15, '{}', '\mnuts?\M|almond|walnut|cashew|peanut|pistachio|\mseeds?\M|\mchia|flax|sesame|tahini|pecan|hazelnut|macadamia', null),
  (80, 'fruit', 45, '{}', 'banana|\mapples?\M|\mpears?\M|orange|berr|grape|mango|pineapple|\mkiwi|melon|peach|\mplum|cherr|guava|papaya|dragon ?fruit|lychee|\mdates?\M|\mfigs?\M|raisin|fruit|apricot|persimmon|pomegranate', null),
  (81, 'vegetables', 15, '{}', 'spinach|broccoli|cabbage|lettuce|\mkale\M|carrot|onion|garlic|pepper|tomato|cucumber|mushroom|zucchini|eggplant|aubergine|cauliflower|bok choy|\mchoy\M|salad|vegetable|veggie|greens|celery|asparagus|\mleek|sprout|seaweed|kimchi|bamboo|beet|radish|okra|avocado', null),
  (90, 'sugars', 65, '{}', '\msugar\M|honey|syrup|\mjam\M|marmalade|nutella', null),
  (91, 'fats_oils', null, '{}', '\moil\M|\mbutter\M|ghee|\mlard\M|mayo|margarine', null),
  (92, 'sauces', null, '{}', 'sauce|dressing|ketchup|mustard|gravy|pesto|soy sauce|condiment', null),
  -- tag-only rules
  (200, null, null, '{gluten}', 'wheat|flour|bread|pasta|spaghetti|macaroni|penne|lasagn|\mramen|udon|bagel|\mbuns?\M|tortilla|wrap|couscous|dumpling|\mbao\M|cake|cookie|biscuit|pastr|croissant|cracker|muffin|pizza|\mrye\M|barley|spelt|seitan|toast|waffle|pancake|swiss roll|cream puff|banana bread|\mbeer\M|bulgur|farro|naan|pita|cereal', null),
  (201, null, null, '{lactose}', '\mmilk\M|yogh?urt|\mcream\M|ice cream|kefir|custard|ricotta|cottage|latte|cappuccino|milk tea|whey (protein )?concentrate|cream puff|cheesecake|優格|優酪|牛奶|鮮奶', '(soy|oat|almond|coconut|rice|pea|plant)[ ,]*(milk|drink|beverage)|lactose[- ]free|dairy[- ]free|vegan'),
  (202, null, null, '{high_fodmap}', 'onion|garlic|\mleek|\mbeans?\M|lentil|chickpea|hummus|\mapples?\M|\mpears?\M|mango|watermelon|honey|cauliflower|mushroom|asparagus|\mrye\M|wheat|inulin|chicory|teccino|sorbitol|xylitol|mannitol|maltitol|agave|\mfigs?\M|\mdates?\M|cherr|plum|apricot|cashew|pistachio|split pea', null),
  (203, null, null, '{fried}', 'fried|\mfries\M|tempura|crispy|katsu|nugget|karaage|donut|doughnut|\mchips\M|crisps|schnitzel|spring roll', null),
  (204, null, null, '{spicy}', 'spicy|chil[il]|curry|hot sauce|sriracha|jalape|\mmala\M|kimchi|szechuan|sichuan|habanero|wasabi|gochujang', null),
  (205, null, null, '{sweetener}', 'sugar[- ]?free|\mdiet\M|zero sugar|no sugar|sorbitol|xylitol|erythritol|maltitol|sucralose|aspartame|stevia|acesulfame', null),
  (206, null, null, '{caffeine}', 'coffee|espresso|latte|cappuccino|americano|matcha|black tea|green tea|oolong|\mcola\M|energy drink|pre-?workout', 'herbal|chicory|teccino|decaf|caffeine[- ]free|barley tea|rooibos|chamomile'),
  (207, null, null, '{ultra_processed}', '\minstant\M|frozen meal|ready meal|microwave|\msoda\M|energy drink|protein bar|candy', null);

-- One row per meal. A meal's ingredients are its items' database matches (meal_items.cronometer_items); an item without
-- that breakdown counts as one ingredient (its description), and a meal without items as one (the meal's description).
create view public.meal_profiles with (security_invoker = true) as
with ing as (
  select mi.meal_id, coalesce(mi.is_beverage, false) as bev,
         lower(concat_ws(' ', x.v->>'food', x.v->>'query')) as txt,
         case when jsonb_typeof(x.v->'grams') = 'number' and (x.v->>'grams')::numeric > 0 then (x.v->>'grams')::numeric end as grams,
         case when jsonb_typeof(x.v->'kcal') = 'number' then (x.v->>'kcal')::numeric end as kcal
  from public.meal_items mi
  cross join lateral jsonb_array_elements(mi.cronometer_items) as x(v)
  where jsonb_typeof(mi.cronometer_items) = 'array'
  union all
  select mi.meal_id, coalesce(mi.is_beverage, false),
         lower(coalesce(mi.description, '')),
         case when mi.quantity_unit = 'g' and mi.quantity > 0 then mi.quantity end,
         mi.calories
  from public.meal_items mi
  where jsonb_typeof(mi.cronometer_items) is distinct from 'array' or jsonb_array_length(mi.cronometer_items) = 0
  union all
  select m.id, false, lower(coalesce(m.description, '')), null, m.calories
  from public.meals m
  where not exists (select 1 from public.meal_items mi where mi.meal_id = m.id)
), tagged as (
  select ing.*, g.food_group, g.gi,
         coalesce((select array_agg(distinct t) from public.food_group_rules r cross join unnest(r.tags) as t
                   where ing.txt ~* r.pattern and (r.unless_pattern is null or ing.txt !~* r.unless_pattern)), '{}') as tags
  from ing
  left join lateral (
    select r.food_group, r.gi from public.food_group_rules r
    where r.food_group is not null and ing.txt ~* r.pattern and (r.unless_pattern is null or ing.txt !~* r.unless_pattern)
    order by r.priority, r.id limit 1
  ) g on true
), per_meal as (
  select meal_id,
         bool_or(bev) as has_beverage,
         bool_and(bev or grams is not null) as food_grams_complete,
         sum(grams) filter (where not bev) as food_grams,
         sum(kcal) filter (where not bev) as food_kcal,
         sum(coalesce(kcal, 0) * gi) filter (where gi is not null and coalesce(kcal, 0) > 0) / nullif(sum(kcal) filter (where gi is not null and coalesce(kcal, 0) > 0), 0) as gi_est,
         array_agg(distinct coalesce(food_group, 'other')) as food_groups,
         sum(coalesce(kcal, 0)) filter (where food_group in ('sweets', 'snacks_salty', 'sugary_drinks', 'processed_meat')) as upf_kcal,
         sum(coalesce(kcal, 0)) as ing_kcal
  from tagged
  group by meal_id
), meal_tags as (
  select meal_id, array_agg(distinct t) as tags_raw from tagged cross join unnest(tagged.tags) as t group by meal_id
), extras as (
  select mi.meal_id, sum(coalesce(mi.alcohol_g, 0)) as alcohol_g, sum(coalesce(mi.caffeine_mg, 0)) as caffeine_mg
  from public.meal_items mi group by mi.meal_id
)
select m.id as meal_id, m.user_id, m.logged_at,
       m.calories, m.protein_g, m.carbs_g, m.fat_g, m.fiber_g,
       round(p.food_grams, 0) as food_grams,
       case when p.food_grams_complete and p.food_grams >= 30 and p.food_kcal > 0
            then round(p.food_kcal / p.food_grams, 2) end as energy_density_kcal_g,
       case when m.calories >= 50 and m.protein_g is not null then round(100 * m.protein_g / m.calories, 1) end as protein_per_100kcal,
       case when m.calories >= 50 and m.carbs_g is not null then round(100 * m.carbs_g / m.calories, 1) end as carbs_per_100kcal,
       case when m.calories >= 50 and m.fiber_g is not null then round(1000 * m.fiber_g / m.calories, 1) end as fiber_per_1000kcal,
       case when m.carbs_g is null then null
            when m.carbs_g - coalesce(m.fiber_g, 0) < 5 then round(greatest(m.carbs_g - coalesce(m.fiber_g, 0), 0) * coalesce(p.gi_est, 50) / 100, 0)
            when p.gi_est is not null then round((m.carbs_g - coalesce(m.fiber_g, 0)) * p.gi_est / 100, 0) end as glycemic_load_est,
       round(p.gi_est, 0) as gi_est,
       case when not p.has_beverage and p.food_grams_complete and p.food_grams >= 30 and m.calories > 0 then round(greatest(0.5, least(5.0,
              41.7 / power(greatest(100 * m.calories / p.food_grams, 30), 0.7)
              + 0.05 * least(100 * coalesce(m.protein_g, 0) / p.food_grams, 30)
              + 6.17e-4 * power(least(100 * coalesce(m.fiber_g, 0) / p.food_grams, 12), 3)
              - 7.25e-6 * power(least(100 * coalesce(m.fat_g, 0) / p.food_grams, 50), 3)
              + 0.617))::numeric, 1) end as fullness_factor_est,
       case when p.ing_kcal > 0 then round(100 * coalesce(p.upf_kcal, 0) / p.ing_kcal, 0) end as ultra_processed_pct,
       p.food_groups,
       array(select distinct t from unnest(coalesce(mt.tags_raw, '{}')
               || case when coalesce(e.caffeine_mg, 0) > 0 then array['caffeine'] else '{}' end
               || case when coalesce(e.alcohol_g, 0) > 0 then array['alcohol'] else '{}' end
               || case when coalesce(p.upf_kcal, 0) > 0 then array['ultra_processed'] else '{}' end
               || case when coalesce(m.fat_g, 0) >= 30 then array['high_fat'] else '{}' end) as t order by t) as tags,
       coalesce((m.calories >= 100 and abs(m.calories - (4 * coalesce(m.protein_g, 0) + 4 * coalesce(m.carbs_g, 0) + 9 * coalesce(m.fat_g, 0) + 7 * coalesce(e.alcohol_g, 0))) > 0.35 * m.calories)
         or (coalesce(m.calories, 0) < 20 and 4 * coalesce(m.protein_g, 0) + 4 * coalesce(m.carbs_g, 0) + 9 * coalesce(m.fat_g, 0) > 100), false) as macro_mismatch
from public.meals m
left join per_meal p on p.meal_id = m.id
left join meal_tags mt on mt.meal_id = m.id
left join extras e on e.meal_id = m.id;

grant select on public.meal_profiles to authenticated;
