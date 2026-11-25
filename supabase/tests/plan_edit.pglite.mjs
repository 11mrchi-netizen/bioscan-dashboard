// Tests of migrations/20261124063309_plan_edit.sql on a real Postgres engine (PGlite), with the repo's own table definitions,
// so the immutability trigger, the unique indexes and row-level security actually fire. Not part of CI (it needs a dev dependency):
//   npm i @electric-sql/pglite   (anywhere)   then   node supabase/tests/plan_edit.pglite.mjs
import { PGlite } from '@electric-sql/pglite'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const mig = (name) => readFileSync(fileURLToPath(new URL(`../migrations/${name}`, import.meta.url)), 'utf8')
const db = new PGlite()
const A = '11111111-1111-1111-1111-111111111111', B = '22222222-2222-2222-2222-222222222222'
let failed = 0
const ok = (cond, what, detail = '') => { console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); if (!cond) failed++ }

await db.exec(`
  create role authenticated; create role anon;
  create schema auth; create table auth.users (id uuid primary key);
  create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
  create table public.exercise_sessions (id bigint primary key);
`)
for (const f of ['20260916035915_create_training_cycles_table.sql', '20261006102330_training_programming_schema.sql', '20261013042315_planned_sessions_zepp_template.sql', '20261124063309_plan_edit.sql']) {
  try { await db.exec(mig(f)) } catch (e) { console.log('LOAD FAILED', f, e.message); process.exit(2) }
}
await db.exec(`grant usage on schema public, auth to authenticated, anon; grant all on all tables in schema public to authenticated; grant usage on all sequences in schema public to authenticated;
  insert into auth.users values ('${A}'), ('${B}');`)

const as = async (user) => { await db.exec(`reset role; set role authenticated; select set_config('request.jwt.claim.sub', '${user}', false)`) }
const root = async () => { await db.exec('reset role') }
const q = async (sql, params = []) => (await db.query(sql, params)).rows
const fails = async (sql, params, re) => { try { await q(sql, params); return null } catch (e) { return re.test(e.message) ? e.message : `WRONG ERROR: ${e.message}` } }
const refused = async (what, sql, params, re) => { const r = await fails(sql, params, re); ok(r !== null && !r.startsWith('WRONG'), what, r ?? 'it was allowed') }

// fixtures (as the table owner): a cycle, a block of 2 sessions, a neighbour block, a finished block, another user's block
await root()
await db.exec(`
  insert into training_cycles (user_id, start_date, end_date, focus) values ('${A}', '2026-11-01', '2027-03-01', '{}');
  insert into training_blocks (id, user_id, name, start_date, end_date, status, cycle_id) overriding system value values
    (1, '${A}', 'Block one', '2026-11-30', '2026-12-13', 'scheduled', 1),
    (2, '${A}', 'Later block', '2027-01-04', '2027-01-17', 'scheduled', 1),
    (3, '${A}', 'Done block', '2026-10-01', '2026-10-28', 'completed', null),
    (4, '${B}', 'Other user block', '2026-11-30', '2026-12-13', 'scheduled', null);
  insert into planned_sessions (id, user_id, block_id, sequence_no, week_index, day_slot, domain, module_ref, title, prescription, original_date, scheduled_date, start_time, duration_min) overriding system value values
    (10, '${A}', 1, 1, 1, 1, 'max_strength', 'strength:x', 'Lower day', '[{"type":"lift","exercise":"Squat","sets":{"min":3,"max":5},"reps":5}]', '2026-11-30', '2026-11-30', '07:00', 60),
    (11, '${A}', 1, 2, 1, 3, 'max_strength', 'strength:x', 'Upper day', '[{"type":"lift","exercise":"Bench","sets":{"min":3,"max":5},"reps":5}]', '2026-12-02', '2026-12-02', '07:00', 60),
    (20, '${A}', 3, 1, 1, 1, 'max_strength', 'strength:x', 'Old', '[]', '2026-10-05', '2026-12-31', '07:00', 60),
    (40, '${B}', 4, 1, 1, 1, 'max_strength', 'strength:x', 'Theirs', '[]', '2026-11-30', '2026-11-30', '07:00', 60);
`)
const TODAY = '2026-11-24'

// ---- the trigger still stands
await as(A)
await refused('the immutability trigger still refuses a direct prescription change', `update planned_sessions set prescription = '[]' where id = 10`, [], /immutable/)
await refused('and a direct original_date change', `update planned_sessions set original_date = '2026-12-01' where id = 10`, [], /immutable/)

// ---- edit: move and fine-tune
let r = (await q(`select * from edit_planned_session(10, $1, $2::jsonb)`, [TODAY, JSON.stringify({ scheduled_date: '2026-12-03', start_time: '18:30', duration_min: 75, note: '  stay light  ' })]))[0]
ok(String(r.scheduled_date).startsWith('2026-12-03') || r.scheduled_date?.toISOString?.().startsWith('2026-12-03'), 'a session is moved')
ok(String(r.start_time).startsWith('18:30') && r.duration_min === 75 && r.note === 'stay light', 'its time, length and note are set (note trimmed)')
const orig = (await q(`select to_char(original_date, 'YYYY-MM-DD') as o, prescription, revision, title from planned_sessions where id = 10`))[0]
ok(orig.o === '2026-11-30' && orig.revision === 1 && orig.title === 'Lower day' && orig.prescription[0].exercise === 'Squat', 'original_date, prescription and title stay')
await root()
let dev = await q(`select kind, source, to_char(from_date, 'YYYY-MM-DD') as f, to_char(to_date, 'YYYY-MM-DD') as t, detail from planned_session_deviations where planned_session_id = 10 order by id`)
ok(dev.length === 2 && dev[0].kind === 'moved' && dev[0].f === '2026-11-30' && dev[0].t === '2026-12-03' && dev[1].kind === 'modified' && dev[1].detail.duration_min.to === 75, 'a move and a modification are recorded as deviations', JSON.stringify(dev.map((d) => d.kind)))
await as(A)
await refused('a date before today is refused', `select * from edit_planned_session(11, $1, '{"scheduled_date":"2026-11-20"}')`, [TODAY], /today or later/)
await refused('a key that is not editable is refused', `select * from edit_planned_session(11, $1, '{"title":"x"}')`, [TODAY], /cannot be changed here/)
await refused('a length out of range is refused', `select * from edit_planned_session(11, $1, '{"duration_min":2}')`, [TODAY], /5 to 600/)
await refused('no change is refused', `select * from edit_planned_session(11, $1, '{"duration_min":60}')`, [TODAY], /nothing changes/)
await refused('a missing day is refused', `select * from edit_planned_session(11, null, '{"duration_min":50}')`, [], /day is required/)
await refused('a session of the past is refused', `select * from edit_planned_session(20, $1, '{"duration_min":50}')`, ['2027-01-05'], /not planned|before today|has ended/)
await refused("another user's session is not found", `select * from edit_planned_session(40, $1, '{"duration_min":50}')`, [TODAY], /no such session/)
await refused('a session of a finished block is refused', `select * from edit_planned_session(20, $1, '{"duration_min":50}')`, [TODAY], /block has ended/)
await root(); await db.exec(`set role anon`)
await refused('anon has no execute permission', `select * from edit_planned_session(11, '${TODAY}', '{"duration_min":50}')`, [], /permission denied/)
await as(A)

// ---- revise: a new revision
r = (await q(`select * from revise_planned_session(11, $1, $2::jsonb)`, [TODAY, JSON.stringify([{ type: 'lift', exercise: 'Bench', sets: { min: 3, max: 4 }, reps: 6 }])]))[0]
ok(r.revision === 2 && r.status === 'planned' && r.sequence_no === 2 && r.title === 'Upper day', 'a prescription change is a new revision of the same session')
const rows = await q(`select id, revision, status, to_char(original_date, 'YYYY-MM-DD') as o from planned_sessions where block_id = 1 and sequence_no = 2 order by revision`)
ok(rows.length === 2 && rows[0].status === 'superseded' && rows[1].status === 'planned' && rows[0].o === rows[1].o, 'the old row is set aside, the new one is planned, original_date carries over', JSON.stringify(rows))
const newId = r.id
await refused('the same prescription again is refused', `select * from revise_planned_session($1, $2, $3::jsonb)`, [newId, TODAY, JSON.stringify([{ type: 'lift', exercise: 'Bench', sets: { min: 3, max: 4 }, reps: 6 }])], /nothing changes/)
await refused('the set-aside revision cannot be revised', `select * from revise_planned_session(11, $1, '[]'::jsonb)`, [TODAY], /superseded, not planned/)
r = (await q(`select * from revise_planned_session($1, $2, '[]'::jsonb)`, [newId, TODAY]))[0]
ok(r.revision === 3, 'a second change is revision 3', String(r.revision))
await refused('a prescription must be an array', `select * from revise_planned_session($1, $2, '{"a":1}'::jsonb)`, [r.id, TODAY], /array/)

// ---- set aside and restore
r = (await q(`select * from set_aside_planned_session(10, $1, true)`, [TODAY]))[0]
ok(r.status === 'superseded', 'a session is set aside (kept, not deleted)')
ok((await q(`select count(*)::int as n from planned_sessions where id = 10`))[0].n === 1, 'the row is still there')
await refused('a set-aside session cannot be changed', `select * from edit_planned_session(10, $1, '{"duration_min":50}')`, [TODAY], /superseded, not planned/)
r = (await q(`select * from set_aside_planned_session(10, $1, false)`, [TODAY]))[0]
ok(r.status === 'planned', 'and brought back')
await refused('an old revision cannot come back while a newer one exists', `select * from set_aside_planned_session(11, $1, false)`, [TODAY], /newer version|not superseded|superseded, not/)

// ---- shift a block
await refused('a block cannot move into the past', `select * from shift_training_block(1, $1, '2026-11-20')`, [TODAY], /today or later/)
await refused('no change is refused for a block', `select * from shift_training_block(1, $1, '2026-11-30')`, [TODAY], /nothing changes/)
await refused('a block that overlaps another is refused', `select * from shift_training_block(1, $1, '2026-12-28')`, [TODAY], /overlap the block "Later block"/)
await refused('a block outside its cycle is refused', `select * from shift_training_block(2, $1, '2027-02-20')`, [TODAY], /outside its training cycle/)
await refused('a finished block cannot be moved', `select * from shift_training_block(3, $1, '2026-12-05')`, [TODAY], /completed cannot be moved/)
await refused("another user's block is not found", `select * from shift_training_block(4, $1, '2026-12-05')`, [TODAY], /no such block/)
r = (await q(`select * from shift_training_block(1, $1, '2026-12-07')`, [TODAY]))[0]
ok(String(r.start_date).includes('2026') && r.choices.start_shift_days === 7, 'a block is moved a week and the shift is recorded in its choices', JSON.stringify(r.choices))
const after = await q(`select id, to_char(scheduled_date, 'YYYY-MM-DD') as s, to_char(original_date, 'YYYY-MM-DD') as o, status from planned_sessions where block_id = 1 order by id`)
ok(after.every((x) => (new Date(x.s) - new Date(x.o)) / 864e5 >= 0) && after.find((x) => x.id === 10).s === '2026-12-10' && after.find((x) => x.id === 11).s === '2026-12-09', 'every session of the block moved with it, every revision', JSON.stringify(after.map((x) => `${x.id}:${x.s}`)))
const blk = (await q(`select to_char(start_date,'YYYY-MM-DD') as s, to_char(end_date,'YYYY-MM-DD') as e from training_blocks where id = 1`))[0]
ok(blk.s === '2026-12-07' && blk.e === '2026-12-20', 'its end moved by the same days', JSON.stringify(blk))
r = (await q(`select * from shift_training_block(1, $1, '2026-12-14')`, [TODAY]))[0]
ok(r.choices.start_shift_days === 14, 'a second shift adds to the record')
await root(); await db.exec(`update planned_sessions set status = 'confirmed' where id = 10`); await as(A)
await refused('a block with a confirmed session has begun', `select * from shift_training_block(1, $1, '2026-12-21')`, [TODAY], /has begun/)
await root(); await db.exec(`update planned_sessions set status = 'planned', exercise_session_id = null where id = 10; update training_blocks set status = 'active' where id = 1`); await as(A)
await refused('an active block cannot be moved', `select * from shift_training_block(1, $1, '2026-12-21')`, [TODAY], /active cannot be moved/)

console.log(failed ? `\n${failed} check(s) failed` : '\nall checks passed')
process.exit(failed ? 1 : 0)
