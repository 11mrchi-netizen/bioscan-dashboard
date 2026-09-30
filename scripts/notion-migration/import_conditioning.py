"""
Notion Conditioning Sessions → Field Terminal Importer

Parses Conditioning sessions + Drill definitions from the Notion ZIP,
then inserts into exercise_sessions (type='other', source='notion').

Usage:
  pip install requests
  python import_conditioning.py <zip_path> [--apply]

Env vars:
  SUPABASE_SERVICE_ROLE_KEY
"""

import argparse
import csv
import io
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import unquote

try:
    import requests
except ImportError:
    sys.exit("pip install requests")


SUPABASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co"
FT_USER_ID = "9757c37c-28c2-4d5e-bb3d-8027c853f7d7"

_MONTH_ABBR = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}

# Performance metric columns to capture per drill (everything except name/date/links)
_SKIP_COLS = {"﻿name", "name", "date", "notes", "note", "text", "composition",
              "🥷 drills + accessories days", "🥷 drills", "🥷 cardio & conditioning",
              "rollup"}


# ---------------------------------------------------------------------------
# Date parsing
# ---------------------------------------------------------------------------

def parse_date(raw: str):
    if not raw:
        return None
    raw = raw.strip().lstrip("@").strip()
    m = re.match(r"(\w+)\s+(\d{1,2}),?\s+(\d{4})", raw, re.IGNORECASE)
    if m:
        mon = _MONTH_ABBR.get(m.group(1)[:3].lower())
        if mon:
            return datetime(int(m.group(3)), mon, int(m.group(2)))
    # DD/MM/YYYY
    m2 = re.match(r"(\d{1,2})/(\d{1,2})/(\d{4})", raw)
    if m2:
        return datetime(int(m2.group(3)), int(m2.group(2)), int(m2.group(1)))
    return None


def parse_datetime(raw: str):
    if not raw:
        return None
    raw = raw.strip().lstrip("@").strip()
    m = re.match(
        r"(\w+)\s+(\d{1,2}),?\s+(\d{4})\s+(\d{1,2}):(\d{2})\s*(AM|PM)?",
        raw, re.IGNORECASE
    )
    if m:
        mon = _MONTH_ABBR.get(m.group(1)[:3].lower())
        if not mon:
            return parse_date(raw)
        h = int(m.group(4))
        if m.group(6) and m.group(6).upper() == "PM" and h < 12:
            h += 12
        elif m.group(6) and m.group(6).upper() == "AM" and h == 12:
            h = 0
        try:
            return datetime(int(m.group(3)), mon, int(m.group(2)), h, int(m.group(5)))
        except ValueError:
            pass
    return parse_date(raw)


def dt_to_iso(dt) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S+00:00") if dt else None


# ---------------------------------------------------------------------------
# ZIP helpers
# ---------------------------------------------------------------------------

def read_csv(zf: zipfile.ZipFile, name: str) -> list[dict]:
    content = zf.open(name).read().decode("utf-8-sig", errors="replace")
    return list(csv.DictReader(io.StringIO(content)))


# ---------------------------------------------------------------------------
# Parse drill definitions
# ---------------------------------------------------------------------------

def parse_drill_definitions(zf: zipfile.ZipFile) -> dict[str, dict]:
    entries = [e.filename for e in zf.infolist()
               if "Drills " in e.filename and e.filename.endswith(".csv")
               and "_all" not in e.filename
               and "Drills/" not in e.filename]
    if not entries:
        return {}
    rows = read_csv(zf, entries[0])
    out = {}
    for row in rows:
        name = (row.get("Name") or "").strip()
        if not name:
            continue
        out[name] = {
            "name": name,
            "focus": (row.get("Focus") or "").strip(),
            "type": (row.get("Type") or "").strip(),
            "equipment": (row.get("Equipment") or "").strip(),
            "description": (row.get("Description") or "").strip(),
        }
    return out


# ---------------------------------------------------------------------------
# Parse drill logs
# ---------------------------------------------------------------------------

def parse_drill_logs(zf: zipfile.ZipFile) -> dict[str, list[dict]]:
    """Returns {drill_name: [{date_key: 'YYYY-MM-DD', performance: {k:v}, notes: str}]}"""
    log_entries = [e.filename for e in zf.infolist()
                   if "Drills/" in e.filename and e.filename.endswith(".csv")
                   and "_all" not in e.filename]
    logs: dict[str, list[dict]] = {}
    for path in log_entries:
        drill_name = path.split("Drills/")[1].split("/")[0]
        rows = read_csv(zf, path)
        entries = []
        for row in rows:
            date_raw = row.get("Date") or row.get("date") or ""
            dt = parse_datetime(date_raw)
            if not dt:
                continue
            date_key = dt.strftime("%Y-%m-%d")
            performance = {}
            notes_parts = []
            for k, v in row.items():
                k_clean = k.strip()
                v_clean = (v or "").strip()
                if not v_clean:
                    continue
                k_lower = k_clean.lower()
                if k_lower in _SKIP_COLS:
                    if k_lower in ("notes", "note", "text", "composition") and v_clean:
                        notes_parts.append(v_clean)
                    continue
                performance[k_clean] = v_clean
            entries.append({
                "date_key": date_key,
                "performance": performance,
                "notes": "; ".join(notes_parts),
            })
        if entries:
            logs[drill_name] = entries
    return logs


# ---------------------------------------------------------------------------
# Parse conditioning sessions
# ---------------------------------------------------------------------------

def parse_conditioning_sessions(zf: zipfile.ZipFile) -> list[dict]:
    entries = [e.filename for e in zf.infolist()
               if "Cardio" in e.filename and e.filename.endswith(".csv")
               and "_all" not in e.filename]
    if not entries:
        return []
    rows = read_csv(zf, entries[0])

    # Drill columns = any column that isn't a known metadata column
    meta_cols = {"name", "date", "template", "intensity", "notes", "date.roller"}
    all_cols = list(rows[0].keys()) if rows else []
    drill_cols = [c for c in all_cols if c.lower() not in meta_cols]

    sessions = []
    for row in rows:
        r = dict(row)
        date_raw = r.get("Date") or r.get("date") or ""
        dt = parse_datetime(r.get("Date.Roller") or date_raw)
        if not dt:
            continue
        date_key = dt.strftime("%Y-%m-%d")
        drills_done = []
        for col in drill_cols:
            val = (r.get(col) or "").strip()
            if val:
                drills_done.append(col)
        sessions.append({
            "date_key": date_key,
            "start_dt": dt,
            "template": (r.get("Template") or "").strip(),
            "intensity": (r.get("Intensity") or "").strip(),
            "notes": (r.get("Notes") or "").strip(),
            "drills_done": drills_done,
        })
    return sessions


# ---------------------------------------------------------------------------
# Normalise drill descriptions with Claude
# ---------------------------------------------------------------------------



# ---------------------------------------------------------------------------
# Supabase helpers
# ---------------------------------------------------------------------------

def supabase_headers(key: str) -> dict:
    return {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "Prefer": "return=representation",
    }


def supabase_post(key: str, table: str, payload: dict, dry_run: bool) -> dict:
    if dry_run:
        return {"dry_run": True}
    resp = requests.post(
        f"{SUPABASE_URL}/rest/v1/{table}",
        headers=supabase_headers(key),
        json=payload,
    )
    if not resp.ok:
        raise requests.HTTPError(f"{resp.status_code} {resp.text}", response=resp)
    data = resp.json()
    return data[0] if isinstance(data, list) else data


def already_imported(key: str, source_notion_cluster: str) -> bool:
    resp = requests.get(
        f"{SUPABASE_URL}/rest/v1/exercise_sessions",
        headers=supabase_headers(key),
        params={
            "select": "id",
            "details->>source_notion_cluster": f"eq.{source_notion_cluster}",
            "limit": "1",
        },
    )
    return resp.ok and len(resp.json()) > 0


# ---------------------------------------------------------------------------
# Build session payload
# ---------------------------------------------------------------------------

def build_session(session: dict, drill_logs: dict, definitions: dict) -> dict:
    start_dt = session["start_dt"]
    # Estimate duration: ~45 min per drill (rough placeholder)
    est_minutes = max(45, len(session["drills_done"]) * 45)
    end_dt = start_dt + timedelta(minutes=est_minutes)

    drills_detail = []
    for drill_name in session["drills_done"]:
        defn = definitions.get(drill_name, {})
        log_entries = drill_logs.get(drill_name, [])
        log = next((e for e in log_entries if e["date_key"] == session["date_key"]), None)

        entry = {
            "name": drill_name,
            "type": defn.get("type", ""),
            "focus": defn.get("focus", "HIC"),
            "description": defn.get("description", ""),
        }
        if log:
            if log["performance"]:
                entry["performance"] = log["performance"]
            if log["notes"]:
                entry["notes"] = log["notes"]
        drills_detail.append(entry)

    # Stable idempotency key from date + template + drill list
    cluster_key = f"conditioning_{session['date_key']}_{'_'.join(sorted(session['drills_done']))}"

    details = {
        "modality": "HIC",
        "template": session["template"],
        "drills": drills_detail,
        "source_notion_cluster": cluster_key,
    }
    if session["notes"]:
        details["notes"] = session["notes"]
    if session["intensity"]:
        details["intensity"] = session["intensity"]

    return {
        "user_id": FT_USER_ID,
        "type": "other",
        "start_time": dt_to_iso(start_dt),
        "end_time": dt_to_iso(end_dt),
        "source": "notion",
        "details": details,
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser()
    p.add_argument("zip_path")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--service-role-key", default=os.environ.get("SUPABASE_SERVICE_ROLE_KEY", ""))
    args = p.parse_args()

    if not args.service_role_key:
        sys.exit("SUPABASE_SERVICE_ROLE_KEY required")

    dry_run = not args.apply
    print(f"{'DRY-RUN' if dry_run else 'APPLY'} mode\n")

    zf = zipfile.ZipFile(args.zip_path)

    print("[1/4] Parsing drill definitions...")
    definitions = parse_drill_definitions(zf)
    print(f"  {len(definitions)} drills found")

    print("[2/4] Parsing conditioning sessions and drill logs...")
    sessions = parse_conditioning_sessions(zf)
    drill_logs = parse_drill_logs(zf)
    print(f"  {len(sessions)} conditioning sessions")
    print(f"  {len(drill_logs)} drill logs parsed")

    print("[3/4] Importing to Supabase...")
    created = skipped = errors = 0
    for session in sessions:
        payload = build_session(session, drill_logs, definitions)
        cluster_key = payload["details"]["source_notion_cluster"]

        if already_imported(args.service_role_key, cluster_key):
            skipped += 1
            continue

        try:
            supabase_post(args.service_role_key, "exercise_sessions", payload, dry_run)
            created += 1
            if dry_run and created <= 3:
                drills = [d["name"] for d in payload["details"]["drills"]]
                print(f"  [DRY] {session['date_key']} {session['template']} — {drills}")
        except Exception as e:
            print(f"  ERROR {session['date_key']}: {e}")
            errors += 1

    print(f"""
==============================
{'DRY RUN ' if dry_run else ''}Complete
  Created:  {created}
  Skipped:  {skipped} (already imported)
  Errors:   {errors}
""")
    if dry_run:
        print("Pass --apply to write to Supabase.")

    # Save normalised drill catalogue for reference
    out = Path("notion-migration-output/drill_catalogue.json")
    out.parent.mkdir(exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(list(definitions.values()), f, indent=2, ensure_ascii=False)
    print(f"Drill catalogue saved to {out}")


if __name__ == "__main__":
    main()
