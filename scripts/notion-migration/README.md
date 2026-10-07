# Notion Workout Archive Migration — Reconciliation Tool

Dry-run analysis tool. **Reads only. No Supabase writes.**

## Quick start

```bash
pip install requests
python reconcile.py <path/to/notion-export.zip> --service-role-key <key>
```

The service role key is required to read `exercise_library` and `exercise_sessions`
(both tables have RLS that blocks the anon key).
Get it from: **Supabase dashboard → Project Settings → API → service_role**.

Pass via env var to avoid it appearing in shell history:
```bash
SUPABASE_SERVICE_ROLE_KEY=<key> python reconcile.py <zip>
```

## What it produces

All output in `./notion-migration-output/` (or `--output-dir`):

| File | Contents |
|------|----------|
| `exercise_mapping.csv` | Notion exercise → FT `exercise_library.id` (confirmed) |
| `exercise_mapping_review.csv` | Ambiguous/unmatched exercises needing manual review |
| `session_reconciliation.csv` | Every cluster classified (ENRICH / CREATE / POSSIBLE_DUPLICATE) |
| `session_match_review.csv` | Low-confidence session matches needing manual review |
| `conditioning_review.csv` | All 204 conditioning sessions |
| `conflicts.csv` | Dates with multiple clusters (session ambiguity) |
| `migration_manifest.json` | Complete manifest — one entry per cluster with all set data |
| `migration_summary.json` | High-level statistics |

**Human review of the above files is required before any Supabase write.**

## What the tool parses

From the Notion export ZIP:
- **210 parseable Strength Clusters** (10 are empty pages, correctly skipped)
- **2,419 individual set records** linked back to their parent clusters
- **204 conditioning sessions**
- **27 Notion exercises** (mapped to `exercise_library` when service role key is provided)

Notion history covers **2024-01-17 → 2026-02-09**, predating full wearable coverage.

## Session classifications

| Classification | Meaning | Action |
|---|---|---|
| `CREATE_HISTORICAL` | No matching FT session | Create new historical strength session |
| `ENRICH_EXISTING` | Confident match to existing FT session | Add set data; preserve wearable metrics |
| `POSSIBLE_DUPLICATE` | Plausible match, low confidence | Manual review required |

## Reconciliation rules

- Matching uses date + ±2h time window + exercise overlap
- Never creates new exercises — every Notion exercise maps to an existing `exercise_library` entry
- Ambiguous mappings go to `exercise_mapping_review.csv`, not auto-created
- Provenance preserved: every migrated record carries `source=notion` + source file path
- Idempotency enforced by stable source key: `(source, source_archive, source_record_id)`

## After review

When reports are reviewed and approved:
- Exercise mapping: fill in `ft_id` for review items
- Session classification: override any `POSSIBLE_DUPLICATE` entries
- Only then run the (not-yet-built) importer against the approved manifest

See: `docs/user-profile-milestone/01-canonical-contracts-audit.md` for the FT strength session
schema (`exercise_sessions.details` jsonb) that CREATE_HISTORICAL sessions will populate.
