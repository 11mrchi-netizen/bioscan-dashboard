// Tests of migrations/*_meal_profiles.sql on a real Postgres engine (PGlite): the food-group rules, the indexes and
// row-level security through the security_invoker view. Not part of CI (it needs a dev dependency):
//   npm i @electric-sql/pglite   (anywhere)   then   node supabase/tests/meal_profiles.pglite.mjs
import { PGlite } from '@electric-sql/pglite'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const dir = fileURLToPath(new URL('../migrations/', import.meta.url))
const file = readdirSync(dir).find((f) => f.endsWith('_meal_profiles.sql'))
const db = new PGlite()
const A = '11111111-1111-1111-1111-111111111111', B = '22222222-2222-2222-2222-222222222222'
let failed = 0
const ok = (cond, what, detail = '') => { console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); if (!cond) failed++ }

await db.exec(`
  create role authenticated; create role anon;
  create schema auth; create table auth.users (id uuid primary key);
  create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
  create table public.meals (id bigint primary key, user_id uuid not null, logged_at timestamptz not null, description text,
    calories numeric, protein_g numeric, fat_g numeric, carbs_g numeric, fiber_g numeric);
  create table public.meal_items (id bigint primary key, meal_id bigint not null references public.meals(id), user_id uuid not null,
    description text, quantity numeric, quantity_unit text, calories numeric, is_beverage boolean, alcohol_g numeric,
    caffeine_mg numeric, cronometer_items jsonb);
  alter table public.meals enable row level security; alter table public.meal_items enable row level security;
  create policy m_own on public.meals for select to authenticated using (user_id = auth.uid());
  create policy mi_own on public.meal_items for select to authenticated using (user_id = auth.uid());
`)
try { await db.exec(readFileSync(dir + file, 'utf8')) } catch (e) { console.log('LOAD FAILED', file, e.message); process.exit(2) }
await db.exec(`grant usage on schema public, auth to authenticated; grant select on all tables in schema public to authenticated;`)

const ci = (...xs) => JSON.stringify(xs.map(([food, grams, kcal]) => ({ food, query: food.toLowerCase(), grams, kcal })))
await db.exec(`
  insert into meals values
    (1, '${A}', '2026-11-25 12:55', 'Chicken, rice and spinach', 450, 45, 8, 45, 3),
    (2, '${A}', '2026-11-25 13:30', 'Chicken and mushroom cream pasta', 620, 30, 25, 70, 2),
    (3, '${A}', '2026-11-25 15:00', 'Teccino chicory herbal coffee', 0, 0, 0, 80, null),
    (4, '${A}', '2026-11-25 16:00', 'Banana bread slice', 300, 4, 12, 40, 1),
    (5, '${A}', '2026-11-25 10:00', 'Soy milk latte', 120, 7, 4, 12, 0),
    (6, '${A}', '2026-11-25 10:30', 'Espresso', 3, 0, 0, 0, null),
    (7, '${B}', '2026-11-25 12:00', 'Other person''s cake', 400, 5, 20, 50, 1);
  insert into meal_items values
    (11, 1, '${A}', 'Chicken, rice and spinach', null, null, 450, false, 0, 0, '${ci(['Chicken breast, cooked', 110, 181], ['Rice, white, cooked', 150, 195], ['Spinach, raw', 60, 14])}'),
    (12, 2, '${A}', 'Pasta', null, null, 620, false, 0, 0, '${ci(['Pasta, with cream sauce', 430, 620])}'),
    (13, 3, '${A}', 'Teccino', 10, 'g', 0, true, 0, 0, null),
    (15, 5, '${A}', 'Soy milk latte', 250, 'ml', 120, true, 0, 60, null),
    (16, 6, '${A}', 'Espresso', 30, 'ml', 3, true, 0, 63, null),
    (17, 7, '${B}', 'Cake', 100, 'g', 400, false, 0, 0, null);
`)

await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub', '${A}', false)`)
const rows = Object.fromEntries((await db.query('select * from meal_profiles order by meal_id')).rows.map((r) => [r.meal_id, r]))
const n = (v) => (v === null || v === undefined ? null : Number(v))

ok(Object.keys(rows).length === 6, 'a person sees only their own meals', Object.keys(rows).join(','))
const m1 = rows[1]
ok(n(m1.energy_density_kcal_g) === Math.round((390 / 320) * 100) / 100, 'energy density = ingredient kcal / grams', m1.energy_density_kcal_g)
ok(n(m1.protein_per_100kcal) === 10, 'protein per 100 kcal', m1.protein_per_100kcal)
ok(n(m1.carbs_per_100kcal) === 10, 'carbs per 100 kcal', m1.carbs_per_100kcal)
ok(n(m1.fiber_per_1000kcal) === 6.7, 'fibre per 1000 kcal', m1.fiber_per_1000kcal)
ok(['poultry', 'refined_grains', 'vegetables'].every((g) => m1.food_groups.includes(g)), 'food groups from the ingredients', m1.food_groups)
ok(n(m1.gi_est) >= 66 && n(m1.gi_est) <= 70, 'GI weighted by ingredient energy', m1.gi_est)
ok(n(m1.glycemic_load_est) === Math.round(42 * n(m1.gi_est) / 100), 'glycemic load = (carbs - fibre) x GI / 100', m1.glycemic_load_est)
ok(n(m1.fullness_factor_est) > 1.5 && n(m1.fullness_factor_est) < 3.5, 'fullness factor in range for a lean plate', m1.fullness_factor_est)
ok(m1.macro_mismatch === false, 'consistent macros are not flagged')

const m2 = rows[2]
ok(m2.tags.includes('gluten') && m2.tags.includes('lactose'), 'cream pasta: gluten and lactose', m2.tags)
ok(m2.food_groups.includes('refined_grains'), 'pasta is a refined grain', m2.food_groups)
ok(m2.tags.includes('high_fodmap') === false, 'no false high-FODMAP tag', m2.tags)

const m3 = rows[3]
ok(m3.macro_mismatch === true, '0 kcal with 80 g carbs is flagged')
ok(m3.tags.includes('high_fodmap'), 'chicory is high FODMAP (inulin)', m3.tags)
ok(n(m3.energy_density_kcal_g) === null && n(m3.protein_per_100kcal) === null, 'no indexes on a 0 kcal entry')

const m4 = rows[4]
ok(m4.food_groups.length === 1 && m4.food_groups[0] === 'sweets', 'a meal without items is read from its description', m4.food_groups)
ok(m4.tags.includes('gluten') && m4.tags.includes('ultra_processed'), 'banana bread: gluten, ultra-processed', m4.tags)
ok(n(m4.ultra_processed_pct) === 100, 'all its energy is ultra-processed', m4.ultra_processed_pct)
ok(n(m4.energy_density_kcal_g) === null, 'no grams, no energy density')

const m5 = rows[5]
ok(!m5.tags.includes('lactose'), 'soy milk is not lactose', m5.tags)
ok(m5.tags.includes('caffeine'), 'a latte carries caffeine', m5.tags)
ok(n(m5.energy_density_kcal_g) === null && n(m5.fullness_factor_est) === null, 'drinks get no energy density or fullness')

const m6 = rows[6]
ok(m6.food_groups.includes('coffee_tea') && m6.tags.includes('caffeine'), 'espresso: coffee, caffeine', `${m6.food_groups} ${m6.tags}`)
ok(m6.macro_mismatch === false, 'a 3 kcal coffee is not flagged')

console.log(failed ? `\n${failed} FAILED` : '\nall passed')
process.exit(failed ? 1 : 0)
