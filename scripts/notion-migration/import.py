"""
Notion Workout Archive → Field Terminal Importer

Reads the reconciliation output and applies approved sessions to Supabase.
Defaults to dry-run; pass --apply to write.

Usage:
  python import.py [--manifest <path>] [--mapping-confirmed <path>] [--mapping-review <path>]
                   [--supabase-url <url>] [--service-role-key <key>] [--apply]

Env vars:
  SUPABASE_URL
  SUPABASE_SERVICE_ROLE_KEY
"""

import argparse
import csv
import json
import os
import sys
import uuid
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("requests not found — run: pip install requests")

DEFAULT_OUTPUT_DIR = Path("notion-migration-output")
DEFAULT_SUPABASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co"
FT_USER_ID = "9757c37c-28c2-4d5e-bb3d-8027c853f7d7"


# ---------------------------------------------------------------------------
# Supabase helpers
# ---------------------------------------------------------------------------

def supabase_headers(api_key: str) -> dict:
    return {
        "apikey": api_key,
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Prefer": "return=representation",
    }


def supabase_get(url: str, api_key: str, table: str, select: str = "*", extra: dict = None) -> list:
    params = {"select": select}
    if extra:
        params.update(extra)
    resp = requests.get(f"{url}/rest/v1/{table}", headers=supabase_headers(api_key), params=params)
    resp.raise_for_status()
    return resp.json()


def supabase_post(url: str, api_key: str, table: str, payload: dict, dry_run: bool) -> dict:
    if dry_run:
        return {"dry_run": True, "payload": payload}
    resp = requests.post(
        f"{url}/rest/v1/{table}",
        headers=supabase_headers(api_key),
        json=payload,
    )
    if not resp.ok:
        raise requests.HTTPError(f"{resp.status_code} {resp.text}", response=resp)
    data = resp.json()
    return data[0] if isinstance(data, list) else data


def supabase_patch(url: str, api_key: str, table: str, row_id, payload: dict, dry_run: bool) -> dict:
    if dry_run:
        return {"dry_run": True, "id": row_id, "payload": payload}
    resp = requests.patch(
        f"{url}/rest/v1/{table}",
        headers=supabase_headers(api_key),
        params={"id": f"eq.{row_id}"},
        json=payload,
    )
    resp.raise_for_status()
    data = resp.json()
    return data[0] if isinstance(data, list) else data


# ---------------------------------------------------------------------------
# Exercise mapping
# ---------------------------------------------------------------------------

def load_exercise_mapping(confirmed_path: Path, review_path: Path, library: list) -> dict:
    """
    Returns {notion_name: ft_id} for all resolved exercises.
    review_path entries need ft_id filled in — accepts both slug IDs and display names.
    """
    # Build name→id lookup from live library
    name_to_id = {ex["name"].lower(): ex["id"] for ex in library}
    id_set = {ex["id"] for ex in library}

    mapping = {}

    with open(confirmed_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("ft_id"):
                mapping[row["notion_name"]] = row["ft_id"]

    with open(review_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ft_id = (row.get("ft_id") or "").strip()
            if not ft_id:
                continue
            notion_name = row["notion_name"]
            # Accept slug ID directly
            if ft_id in id_set:
                mapping[notion_name] = ft_id
                continue
            # Accept display name — resolve to slug
            resolved = name_to_id.get(ft_id.lower())
            if resolved:
                mapping[notion_name] = resolved
            else:
                # Try underscore slug conversion
                slug = ft_id.replace(" ", "_")
                if slug in id_set:
                    mapping[notion_name] = slug
                else:
                    print(f"  WARNING: could not resolve ft_id '{ft_id}' for '{notion_name}' — skipping")

    return mapping


# ---------------------------------------------------------------------------
# Session builders
# ---------------------------------------------------------------------------

def build_details_jsonb(entry: dict, ex_mapping: dict) -> dict:
    """Build the details jsonb for a strength session."""
    exercises_by_name: dict[str, dict] = {}

    for s in entry["sets"]:
        notion_ex = s["exercise_notion"]
        ft_id = ex_mapping.get(notion_ex) or s.get("exercise_ft_id")
        ft_name = s.get("exercise_ft_name") or notion_ex

        if notion_ex not in exercises_by_name:
            exercises_by_name[notion_ex] = {
                "name": ft_name,
                "exercise_id": ft_id,
                "sets": [],
            }

        set_entry = {"reps": s["reps"], "weight_kg": s["weight_kg"]}
        if s.get("notes"):
            set_entry["notes"] = s["notes"]
        exercises_by_name[notion_ex]["sets"].append(set_entry)

    return {"exercises": list(exercises_by_name.values())}


def build_new_session(entry: dict, ex_mapping: dict) -> dict:
    """Build a CREATE_HISTORICAL exercise_sessions row."""
    meta = entry["session_metadata"]
    source_date = entry["source_date"]  # "2024-07-01"

    # Use noon UTC as a stable placeholder for sessions without an exact start time
    start_iso = f"{source_date}T12:00:00+00:00"
    end_min = meta.get("duration_min") or 60
    end_h, end_m = divmod(12 * 60 + int(end_min), 60)
    end_iso = f"{source_date}T{end_h:02d}:{end_m:02d}:00+00:00"

    details = build_details_jsonb(entry, ex_mapping)
    details["source_notion_cluster"] = entry["source_record"]
    details["source_notion_name"] = entry["source_name"]
    if meta.get("calories"):
        details["calories"] = meta["calories"]
    if meta.get("intensity"):
        details["intensity"] = meta["intensity"]

    return {
        "user_id": FT_USER_ID,
        "type": "strength",
        "start_time": start_iso,
        "end_time": end_iso,
        "source": "notion",
        "details": details,
    }


def enrich_existing_session(existing: dict, entry: dict, ex_mapping: dict) -> dict:
    """
    Merge Notion set data into an existing session's details jsonb.
    Wearable metrics (heart_rate, calories from device, etc.) are preserved.
    Notion exercises are appended; existing exercises from the FT record are kept.
    """
    details = existing.get("details") or {}
    existing_exercises = {ex["name"]: ex for ex in details.get("exercises", [])}

    for s in entry["sets"]:
        notion_ex = s["exercise_notion"]
        ft_id = ex_mapping.get(notion_ex) or s.get("exercise_ft_id")
        ft_name = s.get("exercise_ft_name") or notion_ex

        if ft_name not in existing_exercises:
            existing_exercises[ft_name] = {"name": ft_name, "exercise_id": ft_id, "sets": []}

        set_entry = {"reps": s["reps"], "weight_kg": s["weight_kg"]}
        if s.get("notes"):
            set_entry["notes"] = s["notes"]
        existing_exercises[ft_name]["sets"].append(set_entry)

    details["exercises"] = list(existing_exercises.values())
    details["source_notion_cluster"] = entry["source_record"]
    details["source_notion_name"] = entry["source_name"]
    return details


# ---------------------------------------------------------------------------
# Idempotency check
# ---------------------------------------------------------------------------

def already_imported(existing_sessions: list, source_record: str) -> bool:
    """Return True if a session with this notion cluster ID is already in FT."""
    for s in existing_sessions:
        d = s.get("details") or {}
        if d.get("source_notion_cluster") == source_record:
            return True
    return False


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(description="Import approved Notion sessions into Supabase.")
    p.add_argument("--manifest", default=str(DEFAULT_OUTPUT_DIR / "migration_manifest.json"))
    p.add_argument("--mapping-confirmed", default=str(DEFAULT_OUTPUT_DIR / "exercise_mapping.csv"))
    p.add_argument("--mapping-review", default=str(DEFAULT_OUTPUT_DIR / "exercise_mapping_review.csv"))
    p.add_argument("--supabase-url", default=os.environ.get("SUPABASE_URL", DEFAULT_SUPABASE_URL))
    p.add_argument("--service-role-key", default=os.environ.get("SUPABASE_SERVICE_ROLE_KEY", ""))
    p.add_argument("--apply", action="store_true", help="Actually write to Supabase (default: dry-run)")
    args = p.parse_args()

    if not args.service_role_key:
        sys.exit("--service-role-key is required (or set SUPABASE_SERVICE_ROLE_KEY)")

    dry_run = not args.apply
    if dry_run:
        print("DRY-RUN mode — no Supabase writes. Pass --apply to write.\n")
    else:
        print("APPLY mode — writing to Supabase.\n")

    api_key = args.service_role_key
    url = args.supabase_url

    # Load manifest
    with open(args.manifest, encoding="utf-8") as f:
        manifest = json.load(f)

    print(f"Manifest: {len(manifest)} entries")

    # Load exercise library
    print("Fetching exercise_library...")
    library = supabase_get(url, api_key, "exercise_library", select="id,name")
    print(f"  {len(library)} exercises")

    # Load exercise mapping
    ex_mapping = load_exercise_mapping(
        Path(args.mapping_confirmed), Path(args.mapping_review), library
    )
    print(f"  {len(ex_mapping)} exercises mapped")

    # Load existing strength sessions (for idempotency + ENRICH)
    print("Fetching existing strength sessions...")
    all_sessions = supabase_get(url, api_key, "exercise_sessions", select="id,start_time,end_time,details,type")
    ft_strength = {s["id"]: s for s in all_sessions if s.get("type") == "strength"}
    print(f"  {len(ft_strength)} strength sessions")

    # Process manifest
    created = 0
    enriched = 0
    skipped_dup = 0
    skipped_unresolved = 0
    errors = []

    for entry in manifest:
        action = entry["action"]
        source_record = entry["source_record"]

        # Idempotency: skip if already imported
        if already_imported(list(ft_strength.values()), source_record):
            skipped_dup += 1
            continue

        # Reclassify POSSIBLE_DUPLICATE → ENRICH_EXISTING (user confirmed all should enrich)
        if action == "POSSIBLE_DUPLICATE":
            action = "ENRICH_EXISTING"

        if action == "CREATE_HISTORICAL":
            payload = build_new_session(entry, ex_mapping)
            try:
                result = supabase_post(url, api_key, "exercise_sessions", payload, dry_run)
                created += 1
                if dry_run and created <= 3:
                    print(f"  [DRY] CREATE {entry['source_date']} '{entry['source_name']}' — {entry['set_count']} sets")
            except Exception as e:
                errors.append({"entry": entry["source_record"], "error": str(e)})
                if len(errors) == 1:
                    print(f"\nFirst error payload:\n{json.dumps(payload, indent=2, default=str)[:800]}\n")

        elif action == "ENRICH_EXISTING":
            dest_id = entry.get("destination_id")
            if not dest_id or dest_id not in ft_strength:
                skipped_unresolved += 1
                continue
            existing = ft_strength[dest_id]
            new_details = enrich_existing_session(existing, entry, ex_mapping)
            try:
                supabase_patch(url, api_key, "exercise_sessions", dest_id, {"details": new_details}, dry_run)
                enriched += 1
                if dry_run and enriched <= 3:
                    print(f"  [DRY] ENRICH session {dest_id} ({entry['source_date']}) — {entry['set_count']} sets")
            except Exception as e:
                errors.append({"entry": entry["source_record"], "error": str(e)})

    print(f"""
==============================
Import {'(DRY RUN) ' if dry_run else ''}complete
  Created (historical):  {created}
  Enriched (existing):   {enriched}
  Skipped (idempotent):  {skipped_dup}
  Skipped (no dest id):  {skipped_unresolved}
  Errors:                {len(errors)}
""")

    if errors:
        print("Errors:")
        for e in errors:
            print(f"  {e['entry']}: {e['error']}")

    if dry_run:
        print("Run with --apply to write to Supabase.")


if __name__ == "__main__":
    main()
