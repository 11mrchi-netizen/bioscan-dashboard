# 07 — Planned-route and pre-activity preview data model

**Linear:** [DAV-147](https://linear.app/biodashboard/issue/DAV-147) (absorbs [DAV-132](https://linear.app/biodashboard/issue/DAV-132)'s storage/checksum scope — see the milestone plan's Phase 2 note on why these were consolidated into one table rather than two overlapping designs) · **Status:** schema live

## Table: `planned_routes`

One row per (user, Calendar event) — `unique(user_id, calendar_event_id)`, so re-syncing the same
upcoming event upserts rather than duplicates. Standard per-project RLS (own-rows select/insert/
update/delete, matching `exercise_sessions`'s exact policy shape).

- **Calendar/Drive identity**: `calendar_id`, `calendar_event_id`, `event_title`, `event_start_time`
  — everything DAV-145's sync and DAV-146's Drive resolution need to identify what this preview is
  for, without re-querying Calendar.
- **Source**: `drive_file_id` (the real Calendar-attachment-resolved file, DAV-146's primary path) and
  `drive_source_url` (the description-URL fallback, kept alongside rather than overwritten — DAV-146's
  own text: "prefer explicit attachments... keep fuzzy matching as an optional fallback").
- **Cache/versioning** (DAV-151): `gpx_checksum`, `gpx_storage_path`, `parser_version`,
  `terrain_analysis_version`, `is_stale`, `last_error`. A caller compares the live Drive file's
  checksum against the stored one to decide whether recomputation is needed at all; `is_stale`/
  `last_error` let a failed refresh keep showing the last real preview rather than nothing, per
  DAV-151's own "network/offline failures leave the last valid preview available" requirement.
- **Course demand only**: `distance_m`, `elevation_gain_m`, `elevation_loss_m`, `mountain_index`,
  `km_effort`, `grade_distribution` (jsonb — the grade-band breakdown), `confidence_tier`,
  `computed_at`. Deliberately **no performance metrics** (no VAM, no GAP, no durability) — per the
  milestone plan, a preview only ever shows what the course demands, never a performance number,
  since nothing has happened yet to perform.
- **`exercise_session_id`** (nullable FK, `on delete set null`): a plain reference to "which real
  session this was for," once one exists — never a target for comparison math. DAV-150
  (planned-vs-observed reconciliation) was dropped explicitly during this milestone's planning: the
  road actually run can legitimately differ from what was previewed, so this column exists only so a
  consumer can say "this run had a preview," not "here's how it compared."

## Why one table, not two

The original ticket split (DAV-132: GPX storage/checksum/event-linkage; DAV-147: planned-route
preview model) assumed Phase 1 also needed GPX storage. It doesn't (doc 01) — GPX-file storage is a
Phase-2-only concern, and Phase 2's own preview model already needs every field DAV-132 asked for
(checksum, parser version, event linkage). Building both would have meant two tables pointing at
mostly the same data with an arbitrary line between them.

## What's deliberately not here

- No trackpoints stored. A cached GPX (via `gpx_storage_path`, DAV-151) or a live re-download is
  reparsed on demand into `domain/trail`'s trackpoint model — the same "recompute, don't persist
  derived time series" principle `SessionDetailRepository.loadTimeSeries()` already uses for Health
  Connect data.
- No performance/effort metrics, and no reconciliation columns — both explicitly out of scope per the
  milestone plan.
