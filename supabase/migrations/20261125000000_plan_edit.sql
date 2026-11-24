-- Editing the plan before it starts (web redesign follow-up). The rules live here, not in the page, so no client can break them.
--
-- planned_sessions has a trigger (planned_sessions_immutable) that refuses a change to sequence_no, original_date, prescription or
-- block_id: "create a new revision". So:
--   * moving a session changes scheduled_date only (original_date stays the day it was first planned for);
--   * changing a session's prescription is a NEW REVISION: a copy with revision + 1, the old row set aside (status 'superseded');
--   * removing a session sets it aside (status 'superseded'), never deletes it, so the phone takes its Zepp template off the watch
--     (ZeppRepository.pushUpcoming) and every reader that already skips superseded rows (PlannedDayRepository, loadUpcoming,
--     loadPlannedOutcomes, loadBlockForEnding) leaves it out; a deleted row would orphan its template;
--   * moving a block's start moves the block and all its sessions together, in one transaction.
-- Only a session that is still 'planned', not linked to a recording, dated today or later, in a block that has not ended; the day
-- is passed in (p_today, the person's local date): the server's current_date is UTC, a day behind in the morning for UTC+8.
-- A changed session reaches the watch the next time the phone sends (it replaces a template whose hash changed); nothing is pushed
-- to a calendar by this schema (gcal_* stay as they are).
--
-- The trigger is not touched. security invoker: row-level security applies as the caller; user_id is also checked.
-- Safe to run again. Rollback: drop the four functions and alter table public.planned_sessions drop column note.

alter table public.planned_sessions add column if not exists note text;
alter table public.planned_sessions drop constraint if exists planned_sessions_note_len;
alter table public.planned_sessions add constraint planned_sessions_note_len check (note is null or length(note) <= 500);

-- the common guard: the session may still be changed. Returns the row, locked.
create or replace function public.planned_session_open(p_id bigint, p_today date, p_statuses text[] default array['planned'])
returns public.planned_sessions language plpgsql security invoker set search_path = public as $$
declare s public.planned_sessions; b public.training_blocks;
begin
  if p_today is null then raise exception 'planned_sessions: the day is required'; end if;
  select * into s from public.planned_sessions where id = p_id and user_id = auth.uid() for update;
  if not found then raise exception 'planned_sessions: no such session'; end if;
  if not (s.status = any (p_statuses)) then raise exception 'planned_sessions: this session is %, not %', s.status, array_to_string(p_statuses, ' or '); end if;
  if s.exercise_session_id is not null then raise exception 'planned_sessions: this session is linked to a recording'; end if;
  if s.scheduled_date < p_today then raise exception 'planned_sessions: a session dated before today can no longer be changed'; end if;
  select * into b from public.training_blocks where id = s.block_id;
  if b.status in ('completed', 'abandoned') then raise exception 'planned_sessions: its block has ended'; end if;
  return s;
end $$;

-- move and fine-tune: scheduled_date, start_time, duration_min, note
create or replace function public.edit_planned_session(p_id bigint, p_today date, p_changes jsonb)
returns public.planned_sessions language plpgsql security invoker set search_path = public as $$
declare s public.planned_sessions; n public.planned_sessions; k text;
  v_date date; v_time time; v_min int; v_note text; detail jsonb := '{}';
begin
  if p_changes is null or jsonb_typeof(p_changes) <> 'object' then raise exception 'planned_sessions: changes must be an object'; end if;
  for k in select jsonb_object_keys(p_changes) loop
    if k not in ('scheduled_date', 'start_time', 'duration_min', 'note') then raise exception 'planned_sessions: % cannot be changed here', k; end if;
  end loop;
  s := public.planned_session_open(p_id, p_today);
  v_date := s.scheduled_date; v_time := s.start_time; v_min := s.duration_min; v_note := s.note;
  if p_changes ? 'scheduled_date' then
    v_date := (p_changes ->> 'scheduled_date')::date;
    if v_date is null or v_date < p_today then raise exception 'planned_sessions: the new date must be today or later'; end if;
  end if;
  if p_changes ? 'start_time' then v_time := nullif(p_changes ->> 'start_time', '')::time; end if;
  if p_changes ? 'duration_min' then
    v_min := nullif(p_changes ->> 'duration_min', '')::int;
    if v_min is not null and (v_min < 5 or v_min > 600) then raise exception 'planned_sessions: the length must be 5 to 600 minutes'; end if;
  end if;
  if p_changes ? 'note' then v_note := nullif(btrim(p_changes ->> 'note'), ''); end if;
  if v_date is not distinct from s.scheduled_date and v_time is not distinct from s.start_time and v_min is not distinct from s.duration_min and v_note is not distinct from s.note then
    raise exception 'planned_sessions: nothing changes';
  end if;
  update public.planned_sessions set scheduled_date = v_date, start_time = v_time, duration_min = v_min, note = v_note where id = s.id returning * into n;
  if v_date is distinct from s.scheduled_date then
    insert into public.planned_session_deviations (user_id, planned_session_id, kind, source, from_date, to_date) values (s.user_id, s.id, 'moved', 'app', s.scheduled_date, v_date);
  end if;
  if v_time is distinct from s.start_time then detail := detail || jsonb_build_object('start_time', jsonb_build_object('from', s.start_time, 'to', v_time)); end if;
  if v_min is distinct from s.duration_min then detail := detail || jsonb_build_object('duration_min', jsonb_build_object('from', s.duration_min, 'to', v_min)); end if;
  if v_note is distinct from s.note then detail := detail || jsonb_build_object('note', true); end if;
  if detail <> '{}'::jsonb then
    insert into public.planned_session_deviations (user_id, planned_session_id, kind, source, detail) values (s.user_id, s.id, 'modified', 'app', detail);
  end if;
  return n;
end $$;

-- a new prescription is a new revision: the copy is planned, the old row is set aside, in one transaction
create or replace function public.revise_planned_session(p_id bigint, p_today date, p_prescription jsonb)
returns public.planned_sessions language plpgsql security invoker set search_path = public as $$
declare s public.planned_sessions; n public.planned_sessions; rev int;
begin
  if p_prescription is null or jsonb_typeof(p_prescription) <> 'array' then raise exception 'planned_sessions: a prescription is an array'; end if;
  s := public.planned_session_open(p_id, p_today);
  if p_prescription = s.prescription then raise exception 'planned_sessions: nothing changes'; end if;
  select coalesce(max(revision), 0) + 1 into rev from public.planned_sessions where block_id = s.block_id and sequence_no = s.sequence_no;
  insert into public.planned_sessions (user_id, block_id, revision, sequence_no, week_index, day_slot, slot_in_day, domain, work_kind, module_ref, title,
      prescription, prescription_version, original_date, scheduled_date, start_time, duration_min, status, note)
    values (s.user_id, s.block_id, rev, s.sequence_no, s.week_index, s.day_slot, s.slot_in_day, s.domain, s.work_kind, s.module_ref, s.title,
      p_prescription, s.prescription_version, s.original_date, s.scheduled_date, s.start_time, s.duration_min, 'planned', s.note)
    returning * into n;
  update public.planned_sessions set status = 'superseded' where id = s.id;
  insert into public.planned_session_deviations (user_id, planned_session_id, kind, source, detail)
    values (s.user_id, n.id, 'modified', 'app', jsonb_build_object('prescription', 'revised', 'from_revision', s.revision, 'to_revision', rev));
  return n;
end $$;

-- take a session out of the plan (kept, never deleted), or bring it back
create or replace function public.set_aside_planned_session(p_id bigint, p_today date, p_aside boolean)
returns public.planned_sessions language plpgsql security invoker set search_path = public as $$
declare s public.planned_sessions; n public.planned_sessions;
begin
  if p_aside then
    s := public.planned_session_open(p_id, p_today, array['planned']);
    update public.planned_sessions set status = 'superseded' where id = s.id returning * into n;
    insert into public.planned_session_deviations (user_id, planned_session_id, kind, source, detail) values (s.user_id, s.id, 'skipped', 'app', jsonb_build_object('set_aside', true));
  else
    s := public.planned_session_open(p_id, p_today, array['superseded']);
    if exists (select 1 from public.planned_sessions o where o.block_id = s.block_id and o.sequence_no = s.sequence_no and o.id <> s.id and (o.revision > s.revision or o.status <> 'superseded')) then
      raise exception 'planned_sessions: a newer version of this session exists, so this one cannot come back';
    end if;
    update public.planned_sessions set status = 'planned' where id = s.id returning * into n;
    insert into public.planned_session_deviations (user_id, planned_session_id, kind, source, detail) values (s.user_id, s.id, 'modified', 'app', jsonb_build_object('restored', true));
  end if;
  return n;
end $$;

-- move the start of a block that has not begun: the block, its dates and all its sessions, together
create or replace function public.shift_training_block(p_block_id bigint, p_today date, p_new_start date)
returns public.training_blocks language plpgsql security invoker set search_path = public as $$
declare b public.training_blocks; n public.training_blocks; c public.training_cycles; delta int; new_end date; clash text; shifted int;
begin
  if p_today is null or p_new_start is null then raise exception 'training_blocks: the day and the new start are required'; end if;
  select * into b from public.training_blocks where id = p_block_id and user_id = auth.uid() for update;
  if not found then raise exception 'training_blocks: no such block'; end if;
  if b.status not in ('draft', 'scheduled') then raise exception 'training_blocks: a block that is % cannot be moved', b.status; end if;
  if p_new_start < p_today then raise exception 'training_blocks: the new start must be today or later'; end if;
  delta := p_new_start - b.start_date;
  if delta = 0 then raise exception 'training_blocks: nothing changes'; end if;
  if exists (select 1 from public.planned_sessions s where s.block_id = b.id and s.status <> 'superseded'
             and (s.exercise_session_id is not null or s.status in ('confirmed', 'skipped') or s.scheduled_date < p_today)) then
    raise exception 'training_blocks: it has begun (a session is linked, confirmed or already past)';
  end if;
  new_end := b.end_date + delta;
  select o.name into clash from public.training_blocks o
    where o.user_id = auth.uid() and o.id <> b.id and o.status in ('scheduled', 'active')
      and daterange(o.start_date, o.end_date, '[]') && daterange(p_new_start, new_end, '[]') limit 1;
  if clash is not null then raise exception 'training_blocks: it would overlap the block "%"', clash; end if;
  if b.cycle_id is not null then
    select * into c from public.training_cycles where id = b.cycle_id;
    if found and (p_new_start < c.start_date or (c.end_date is not null and new_end > c.end_date)) then
      raise exception 'training_blocks: the new dates fall outside its training cycle (% to %)', c.start_date, coalesce(c.end_date::text, 'open');
    end if;
  end if;
  update public.training_blocks set start_date = p_new_start, end_date = new_end,
      choices = jsonb_set(coalesce(choices, '{}'::jsonb), '{start_shift_days}', to_jsonb(coalesce((choices ->> 'start_shift_days')::int, 0) + delta)),
      updated_at = now()
    where id = b.id returning * into n;
  update public.planned_sessions set scheduled_date = scheduled_date + delta where block_id = b.id;
  get diagnostics shifted = row_count;
  return n;
end $$;

revoke all on function public.planned_session_open(bigint, date, text[]) from public, anon;
revoke all on function public.edit_planned_session(bigint, date, jsonb) from public, anon;
revoke all on function public.revise_planned_session(bigint, date, jsonb) from public, anon;
revoke all on function public.set_aside_planned_session(bigint, date, boolean) from public, anon;
revoke all on function public.shift_training_block(bigint, date, date) from public, anon;
grant execute on function public.planned_session_open(bigint, date, text[]) to authenticated;
grant execute on function public.edit_planned_session(bigint, date, jsonb) to authenticated;
grant execute on function public.revise_planned_session(bigint, date, jsonb) to authenticated;
grant execute on function public.set_aside_planned_session(bigint, date, boolean) to authenticated;
grant execute on function public.shift_training_block(bigint, date, date) to authenticated;
