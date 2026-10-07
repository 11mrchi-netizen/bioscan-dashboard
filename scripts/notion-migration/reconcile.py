"""
Notion Workout Archive → Field Terminal Reconciliation (dry-run)

Reads the Notion export ZIP, queries Supabase for the current exercise library
and strength sessions, then produces a set of CSV/JSON review files.

NO writes to Supabase are made. All output is in --output-dir for human review.

Usage:
  python reconcile.py <notion_zip_path> [--output-dir <dir>] [--supabase-url <url>] [--anon-key <key>]

Env vars (override CLI args):
  SUPABASE_URL
  SUPABASE_ANON_KEY   (or SUPABASE_SERVICE_ROLE_KEY for full access)
"""

import argparse
import csv
import json
import os
import re
import sys
import zipfile
from datetime import datetime, date, timedelta
from pathlib import Path
from typing import Optional
from urllib.parse import unquote
import io

try:
    import requests
except ImportError:
    sys.exit("requests not found — run: pip install requests")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

NOTION_ZIP_ROOT = "Workout/Individual Databases"
CLUSTERS_CSV_GLOB = "Strength Clusters"
CONDITIONING_CSV_GLOB = "Cardio & Conditioning"

# Seed alias table: Notion name variant → canonical Notion name
# (canonical = the directory name in the ZIP)
NOTION_ALIASES = {
    "PUW":    "Pull Up (Weighted)",
    "ICP":    "Inclined Chest Press (ICP)",
    "BSS":    "Bulgarian Split Squat (BSS)",
    "SLD":    "Stiff-Leg Deadlift",   # review: may not be in lib
    "Lounge": "Lounge",               # flagged: likely Lunge
    "C&P":    "Clean & Press",
}

# Seed mapping: Notion canonical name → likely FT exercise_library name(s)
# Format: {notion_name: [candidate_ft_name, ...]}
# Populated here as hints only; live matching against the real library wins.
SEED_HINTS = {
    "Pull Up (Weighted)":          ["Weighted Pull-up", "Pull-Up", "Pullup"],
    "Clean & Press":               ["Clean and Press", "Clean & Press"],
    "Squat":                       ["Barbell Squat", "Back Squat", "Squat"],
    "Chest Press":                 ["Barbell Bench Press", "Bench Press", "Chest Press"],
    "Deadlift":                    ["Deadlift", "Barbell Deadlift"],
    "Inclined Chest Press (ICP)":  ["Incline Barbell Bench Press", "Incline Bench Press"],
    "Military Press":              ["Military Press", "Overhead Press", "Barbell Overhead Press"],
    "Lounge":                      ["Lunge", "Barbell Lunge", "Dumbbell Lunge"],
    "Row":                         ["Barbell Row", "Bent Over Barbell Row", "Bent-Over Barbell Row"],
    "Bulgarian Split Squat (BSS)": ["Bulgarian Split Squat", "Dumbbell Bulgarian Split Squat"],
    "Gorilla Row":                 ["Gorilla Row", "Dumbbell Gorilla Row"],
    "Forearm Curls":               ["Wrist Roller", "Barbell Wrist Curl", "Forearm Curl"],
    "Calf Raises":                 ["Standing Calf Raises", "Calf Raise"],
    "Russian Twists":              ["Russian Twist", "Weighted Russian Twist"],
    "Cossack Squat":               ["Cossack Squat"],
    "Dead Hang":                   ["Dead Hang", "Pullup Bar Dead Hang"],
    "Kettlebell Halo":             ["Kettlebell Halo"],
    "Around the World":            ["Around the World"],
    "Face pull":                   ["Face Pull", "Cable Face Pull"],
    "Side Woodchopper":            ["Cable Woodchoppers", "Woodchop"],
    "Iron Cross":                  ["Iron Cross"],
    "Hyperextension":              ["Hyperextensions", "Back Extension"],
    "ATG Split Squat":             ["ATG Split Squat", "Split Squat"],
    "Sissy Squat":                 ["Sissy Squat"],
    "Good Morning":                ["Good Morning", "Barbell Good Morning"],
    "Tibial Raises":               ["Tibial Raise"],
    "Farmer Carry":                ["Farmer's Walk", "Farmer Carry"],
    "Stiff-Leg Deadlift":          ["Stiff-Leg Deadlift", "Romanian Deadlift", "SLDL"],
}


# ---------------------------------------------------------------------------
# ZIP helpers
# ---------------------------------------------------------------------------

def open_zip(path: str) -> zipfile.ZipFile:
    return zipfile.ZipFile(path, "r")


def read_entry(zf: zipfile.ZipFile, name: str) -> str:
    with zf.open(name) as f:
        return f.read().decode("utf-8", errors="replace")


def find_entries(zf: zipfile.ZipFile, pattern: str) -> list[str]:
    """Return all ZipFile entry names matching a substring pattern."""
    return [e.filename for e in zf.infolist() if pattern in e.filename]


def find_entries_re(zf: zipfile.ZipFile, regex: str) -> list[str]:
    rx = re.compile(regex)
    return [e.filename for e in zf.infolist() if rx.search(e.filename)]


# ---------------------------------------------------------------------------
# Date parsing
# ---------------------------------------------------------------------------

_MONTH_ABBR = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}

def parse_notion_date(raw: str) -> Optional[date]:
    """Parse Notion date strings like 'Mar 24, 2025' or 'February 9, 2026 7:40 AM (GMT+8)'."""
    if not raw:
        return None
    raw = raw.strip()
    # Try "Month Day, Year [time]"
    m = re.match(
        r"(\w+)\s+(\d{1,2}),?\s+(\d{4})",
        raw, re.IGNORECASE
    )
    if m:
        mon_str, day_str, year_str = m.groups()
        mon = _MONTH_ABBR.get(mon_str[:3].lower())
        if mon:
            return date(int(year_str), mon, int(day_str))
    return None


def parse_notion_datetime(raw: str) -> Optional[datetime]:
    """Parse Notion start-time strings like 'July 1, 2024 7:36 AM (GMT+8) → 8:24 AM'."""
    if not raw:
        return None
    raw = raw.strip()
    # Remove timezone and range suffix
    raw = re.sub(r"\s*\(GMT[^\)]*\).*", "", raw)
    raw = re.sub(r"\s*→.*", "", raw).strip()
    # "July 1, 2024 7:36 AM"
    m = re.match(
        r"(\w+)\s+(\d{1,2}),?\s+(\d{4})\s+(\d{1,2}):(\d{2})\s*(AM|PM)?",
        raw, re.IGNORECASE
    )
    if m:
        mon_str, day_str, year_str, h_str, min_str, ampm = m.groups()
        mon = _MONTH_ABBR.get(mon_str[:3].lower())
        if mon:
            h = int(h_str)
            if ampm and ampm.upper() == "PM" and h < 12:
                h += 12
            elif ampm and ampm.upper() == "AM" and h == 12:
                h = 0
            try:
                return datetime(int(year_str), mon, int(day_str), h, int(min_str))
            except ValueError:
                pass
    d = parse_notion_date(raw)
    if d:
        return datetime(d.year, d.month, d.day)
    return None


def parse_duration_minutes(raw: str) -> Optional[int]:
    """Parse cluster duration (may be int minutes, may be missing/0)."""
    if not raw:
        return None
    try:
        v = int(float(raw.strip()))
        return v if v > 0 else None
    except (ValueError, TypeError):
        return None


# ---------------------------------------------------------------------------
# Cluster .md parsing
# ---------------------------------------------------------------------------

def extract_filename_id(filename: str) -> str:
    """Extract the Notion page UUID from a filename like 'Name abc123.md'."""
    m = re.search(r"([0-9a-f]{32})(\.md)?$", filename, re.IGNORECASE)
    return m.group(1) if m else ""


def parse_cluster_md(content: str, filename: str) -> dict:
    """Parse a Strength Cluster .md file into a structured dict."""
    lines = content.splitlines()
    name = lines[0].lstrip("#").strip() if lines else "Unknown"
    cluster_id = extract_filename_id(filename)

    result = {
        "cluster_id":       cluster_id,
        "cluster_name":     name,
        "source_file":      filename,
        "start_time_raw":   None,
        "start_dt":         None,
        "date":             None,
        "duration_min":     None,
        "calories":         None,
        "session_intensity":None,
        "template":         None,
        "exercise_links":   {},  # {notion_exercise_name: [{set_num, set_id, set_path}]}
    }

    for line in lines[1:]:
        line = line.strip()
        if line.startswith("Start Time:"):
            raw = line[len("Start Time:"):].strip()
            result["start_time_raw"] = raw
            dt = parse_notion_datetime(raw)
            result["start_dt"] = dt
            result["date"] = dt.date() if dt else None
        elif line.startswith("Duration:"):
            result["duration_min"] = parse_duration_minutes(line[len("Duration:"):].strip())
        elif line.startswith("Calories Burned:"):
            raw = line[len("Calories Burned:"):].strip()
            try:
                result["calories"] = float(raw.replace(",", ""))
            except ValueError:
                pass
        elif line.startswith("Session Intensity:"):
            raw = line[len("Session Intensity:"):].strip().rstrip("%")
            try:
                result["session_intensity"] = float(raw) / 100.0
            except ValueError:
                pass
        elif line.startswith("Template:"):
            result["template"] = line[len("Template:"):].strip()
        elif ":" in line and not line.startswith("🗓️"):
            # Exercise link lines like "PUW: 1 (...), 2 (...)"
            colon_idx = line.index(":")
            ex_key = line[:colon_idx].strip()
            links_raw = line[colon_idx+1:].strip()
            # Skip if it looks like a Notion property (starts with emoji or is known non-exercise)
            if ex_key in ("Start Time", "Duration", "Calories Burned", "Session Intensity",
                          "Template", "Wanna Deadlift?"):
                continue
            # Parse individual links: "1 (../path/to/set.md), 2 (...)"
            link_pattern = re.findall(r"(\d+)\s+\(([^)]+)\)", links_raw)
            if link_pattern:
                # Resolve canonical exercise name from the path
                ex_canonical = _resolve_exercise_name_from_links(ex_key, link_pattern)
                result["exercise_links"][ex_canonical] = [
                    {
                        "set_num": int(s),
                        "set_id":  extract_filename_id(unquote(p)),
                        "set_path": unquote(p).lstrip("./").lstrip("../"),
                    }
                    for s, p in link_pattern
                ]

    return result


def _resolve_exercise_name_from_links(ex_key: str, link_pattern: list) -> str:
    """Derive canonical Notion exercise name from the cluster key or link path."""
    # The link path contains the real exercise directory, e.g.
    # "../Exercises/Pull%20Up%20(Weighted)/PUW%20Log/1%20abc.md"
    # Use that path to extract the directory name.
    if link_pattern:
        path = unquote(link_pattern[0][1])
        m = re.search(r"Exercises/([^/]+)/", path)
        if m:
            return m.group(1)
    # Fall back to the cluster column key with alias resolution
    return NOTION_ALIASES.get(ex_key, ex_key)


# ---------------------------------------------------------------------------
# Set .md parsing
# ---------------------------------------------------------------------------

def parse_set_md(content: str, filename: str) -> dict:
    """Parse an individual set .md file."""
    lines = content.splitlines()
    set_num_str = lines[0].lstrip("#").strip() if lines else "1"
    try:
        set_num = int(set_num_str)
    except ValueError:
        set_num = 1

    set_id = extract_filename_id(filename)
    exercise_dir = _extract_exercise_dir(filename)

    result = {
        "set_id":       set_id,
        "set_num":      set_num,
        "exercise_dir": exercise_dir,
        "source_file":  filename,
        "date":         None,
        "reps":         None,
        "weight_kg":    None,
        "volume":       None,
        "new_pr_1rm":   None,
        "cluster_id":   None,
        "cluster_name": None,
        "notes":        None,
    }

    for line in lines[1:]:
        line = line.strip()
        if "🦾 Strength Clusters:" in line:
            # URL-decode first so "%2002adee..." becomes " 02adee..." — prevents
            # %20's trailing chars from corrupting the 32-char hex extraction
            decoded = unquote(line)
            m = re.search(r"([0-9a-f]{32})", decoded, re.IGNORECASE)
            if m:
                result["cluster_id"] = m.group(1)
            nm = re.match(r".*🦾 Strength Clusters:\s*(.+?)\s+\(", decoded)
            if nm:
                result["cluster_name"] = nm.group(1).strip()
            continue

        # Flexible key: value parsing — handles "Date :" (trailing space before colon)
        fm = re.match(r"^([^:()\[\]]+?)\s*:\s*(.*)$", line)
        if not fm:
            continue
        key, val = fm.group(1).strip(), fm.group(2).strip()
        key_lower = key.lower()

        if key_lower == "date":
            result["date"] = parse_notion_date(val)
        elif key_lower == "reps":
            try:
                result["reps"] = int(val)
            except ValueError:
                pass
        elif key_lower == "working weight":
            try:
                result["weight_kg"] = float(val)
            except ValueError:
                pass
        elif key_lower == "volume":
            try:
                result["volume"] = float(val)
            except ValueError:
                pass
        elif key_lower == "new pr?":
            try:
                result["new_pr_1rm"] = float(val)
            except ValueError:
                pass
        elif key_lower == "notes":
            result["notes"] = val or None

    return result


def _extract_exercise_dir(filename: str) -> str:
    """Extract 'Pull Up (Weighted)' from a path like '.../Exercises/Pull Up (Weighted)/PUW Log/...'"""
    m = re.search(r"Exercises/([^/]+)/", filename)
    return m.group(1) if m else ""


# ---------------------------------------------------------------------------
# Exercise log CSV parsing (main flat CSVs — for notes/weight_ref enrichment)
# ---------------------------------------------------------------------------

def parse_exercise_log_csv(content: str, exercise_dir: str) -> list[dict]:
    """Parse a main exercise log CSV into a list of set dicts."""
    reader = csv.DictReader(io.StringIO(content))
    rows = []
    for row in reader:
        d = parse_notion_date(row.get("Date", ""))
        try:
            set_num = int(float(row.get("Set N.", 0)))
        except (ValueError, TypeError):
            set_num = 0
        try:
            weight = float(row.get("Working Weight", 0) or 0)
        except (ValueError, TypeError):
            weight = 0.0
        try:
            reps = int(float(row.get("Reps", 0) or 0))
        except (ValueError, TypeError):
            reps = 0
        try:
            volume = float(row.get("Volume", 0) or 0)
        except (ValueError, TypeError):
            volume = 0.0
        try:
            si_raw = row.get("Session Intensity", "").strip().rstrip("%")
            session_intensity = float(si_raw) / 100.0 if si_raw else None
        except (ValueError, TypeError):
            session_intensity = None
        try:
            pr_raw = row.get("New PR?", "").strip()
            new_pr_1rm = float(pr_raw) if pr_raw else None
        except (ValueError, TypeError):
            new_pr_1rm = None

        rows.append({
            "exercise_dir":      exercise_dir,
            "date":              d,
            "set_num":           set_num,
            "weight_kg":         weight,
            "reps":              reps,
            "volume":            volume,
            "notes":             row.get("Notes", "").strip() or None,
            "session_intensity": session_intensity,
            "new_pr_1rm":        new_pr_1rm,
            "weight_reference":  row.get("Weight Reference", "").strip() or None,
        })
    return rows


# ---------------------------------------------------------------------------
# Conditioning CSV parsing
# ---------------------------------------------------------------------------

def parse_conditioning_csv(content: str) -> list[dict]:
    reader = csv.DictReader(io.StringIO(content))
    rows = []
    for row in reader:
        d = parse_notion_date(row.get("Date", ""))
        # Drill columns: anything that isn't a known metadata column
        meta_cols = {"Name", "Date", "Template", "Intensity", "Notes", "Date.Roller"}
        drill_links = {}
        for col, val in row.items():
            if col not in meta_cols and val and val.strip():
                drill_links[col] = val.strip()
        rows.append({
            "name":        row.get("Name", "").strip(),
            "date":        d,
            "template":    row.get("Template", "").strip() or None,
            "intensity":   row.get("Intensity", "").strip() or None,
            "notes":       row.get("Notes", "").strip() or None,
            "drill_links": drill_links,
        })
    return rows


# ---------------------------------------------------------------------------
# Supabase queries
# ---------------------------------------------------------------------------

def supabase_get(url: str, key: str, table: str, select: str = "*",
                 filters: dict = None) -> list[dict]:
    """Simple Supabase REST GET with pagination."""
    headers = {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Prefer": "count=none",
    }
    rows = []
    offset = 0
    limit = 1000
    while True:
        params = {"select": select, "limit": limit, "offset": offset}
        if filters:
            params.update(filters)
        r = requests.get(f"{url}/rest/v1/{table}", headers=headers, params=params)
        if r.status_code == 400:
            raise ValueError(f"Supabase 400 on {table}: {r.text[:200]}")
        r.raise_for_status()
        batch = r.json()
        if not batch:
            break
        rows.extend(batch)
        if len(batch) < limit:
            break
        offset += limit
    return rows


# ---------------------------------------------------------------------------
# Exercise mapping
# ---------------------------------------------------------------------------

def normalize_name(name: str) -> str:
    """Lowercase, remove punctuation/parens/extra spaces for fuzzy matching."""
    name = name.lower()
    name = re.sub(r"[().,'\-]", " ", name)
    name = re.sub(r"\s+", " ", name).strip()
    return name


def token_overlap(a: str, b: str) -> float:
    """Fraction of tokens in `a` that appear in `b`."""
    ta = set(normalize_name(a).split())
    tb = set(normalize_name(b).split())
    if not ta:
        return 0.0
    return len(ta & tb) / len(ta)


def map_exercises(notion_exercises: list[str],
                  ft_library: list[dict]) -> tuple[dict, list[dict]]:
    """
    Map each Notion exercise name to a Field Terminal exercise_library entry.

    Returns:
        confirmed: {notion_name: ft_exercise_dict}
        review:    list of dicts for exercise_mapping_review.csv
    """
    confirmed = {}
    review = []

    norm_lib = [(ex, normalize_name(ex["name"])) for ex in ft_library]

    for notion_name in notion_exercises:
        norm_notion = normalize_name(notion_name)
        hints = [normalize_name(h) for h in SEED_HINTS.get(notion_name, [])]

        # Level 1: exact match
        exact = [ex for ex, norm in norm_lib if norm == norm_notion]
        if len(exact) == 1:
            confirmed[notion_name] = exact[0]
            continue

        # Level 2: hint match (exact normalized)
        hint_matches = [ex for ex, norm in norm_lib if norm in hints]
        if len(hint_matches) == 1:
            confirmed[notion_name] = hint_matches[0]
            continue

        # Level 3: token overlap — collect candidates above threshold
        candidates = []
        for ex, norm in norm_lib:
            score = token_overlap(notion_name, ex["name"])
            if score >= 0.6:
                candidates.append((score, ex))
        candidates.sort(key=lambda x: -x[0])

        if len(candidates) == 1 and candidates[0][0] >= 0.8:
            confirmed[notion_name] = candidates[0][1]
            continue

        # Level 4: needs review
        review.append({
            "notion_name":        notion_name,
            "hint_candidates":    ", ".join(h for h in SEED_HINTS.get(notion_name, [])),
            "top_ft_candidates":  "; ".join(
                f"{ex['name']} ({ex['id']}) [{sc:.2f}]"
                for sc, ex in candidates[:5]
            ),
            "confidence":         candidates[0][0] if candidates else 0.0,
            "reason":             "multiple_candidates" if len(candidates) > 1 else "no_match",
        })

    return confirmed, review


# ---------------------------------------------------------------------------
# Session reconciliation
# ---------------------------------------------------------------------------

def ft_session_date(sess: dict) -> Optional[date]:
    raw = sess.get("start_time") or ""
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).date()
    except (ValueError, AttributeError):
        return None


def ft_session_exercises(sess: dict) -> set[str]:
    """Extract exercise names from exercise_sessions.details jsonb."""
    details = sess.get("details") or {}
    if isinstance(details, str):
        try:
            details = json.loads(details)
        except (json.JSONDecodeError, TypeError):
            return set()
    exs = details.get("exercises", [])
    return {e.get("name", "").lower() for e in exs if e.get("name")}


def reconcile_sessions(clusters: list[dict],
                       ft_sessions: list[dict],
                       exercise_mapping: dict,
                       time_window_hours: float = 2.0) -> list[dict]:
    """
    Classify each Notion cluster against the current FT sessions.

    Classifications:
        ENRICH_EXISTING  — confident match
        CREATE_HISTORICAL — no FT session found
        POSSIBLE_DUPLICATE — plausible match, low confidence
    """
    # Build FT session index by date
    ft_by_date: dict[date, list[dict]] = {}
    for sess in ft_sessions:
        d = ft_session_date(sess)
        if d:
            ft_by_date.setdefault(d, []).append(sess)

    results = []
    for cluster in clusters:
        c_date = cluster.get("date")
        c_dt   = cluster.get("start_dt")
        c_exs  = set()
        for ex_name, sets in cluster.get("exercise_links", {}).items():
            ft_ex = exercise_mapping.get(ex_name)
            if ft_ex:
                c_exs.add(ft_ex["name"].lower())
            else:
                c_exs.add(ex_name.lower())

        candidates = ft_by_date.get(c_date, [])

        best_match = None
        best_score = 0.0
        best_reason = ""

        for ft_sess in candidates:
            # Time proximity score
            ft_dt_raw = ft_sess.get("start_time") or ""
            time_score = 0.5
            if c_dt and ft_dt_raw:
                try:
                    ft_dt = datetime.fromisoformat(ft_dt_raw.replace("Z", "+00:00")).replace(tzinfo=None)
                    delta_h = abs((c_dt - ft_dt).total_seconds()) / 3600
                    if delta_h <= time_window_hours:
                        time_score = 1.0 - (delta_h / time_window_hours) * 0.5
                    else:
                        continue  # outside window
                except (ValueError, AttributeError):
                    pass

            # Exercise overlap score
            ft_exs = ft_session_exercises(ft_sess)
            overlap = 0.0
            if c_exs and ft_exs:
                overlap = len(c_exs & ft_exs) / max(len(c_exs), len(ft_exs))
            elif ft_sess.get("type") == "strength":
                overlap = 0.3  # same type, no exercise detail to compare

            score = (time_score * 0.5) + (overlap * 0.5)
            if score > best_score:
                best_score = score
                best_match = ft_sess
                best_reason = (
                    f"time_score={time_score:.2f} exercise_overlap={overlap:.2f}"
                )

        if best_match is None:
            classification = "CREATE_HISTORICAL"
            confidence = 0.0
            reason = "no_ft_session_on_date"
            ft_id = None
        elif best_score >= 0.7:
            classification = "ENRICH_EXISTING"
            confidence = best_score
            reason = best_reason
            ft_id = best_match.get("id")
        else:
            classification = "POSSIBLE_DUPLICATE"
            confidence = best_score
            reason = f"low_confidence: {best_reason}"
            ft_id = best_match.get("id")

        results.append({
            "cluster_id":           cluster["cluster_id"],
            "cluster_name":         cluster["cluster_name"],
            "cluster_date":         c_date.isoformat() if c_date else "",
            "cluster_start_dt":     c_dt.isoformat() if c_dt else "",
            "cluster_template":     cluster.get("template", ""),
            "cluster_intensity":    cluster.get("session_intensity", ""),
            "cluster_exercises":    "; ".join(sorted(cluster.get("exercise_links", {}).keys())),
            "cluster_set_count":    sum(len(v) for v in cluster.get("exercise_links", {}).values()),
            "ft_session_id":        ft_id or "",
            "ft_started_at":        best_match.get("start_time", "") if best_match else "",
            "time_delta_h":         (
                f"{abs((c_dt - datetime.fromisoformat(best_match['start_time'].replace('Z', '+00:00')).replace(tzinfo=None)).total_seconds()/3600):.2f}"
                if best_match and c_dt and best_match.get("start_time") else ""
            ),
            "exercise_overlap":     (
                f"{len(c_exs & ft_session_exercises(best_match)) / max(len(c_exs),len(ft_session_exercises(best_match))):.2f}"
                if best_match and c_exs and ft_session_exercises(best_match) else ""
            ),
            "confidence":           f"{confidence:.3f}",
            "classification":       classification,
            "reason":               reason,
        })

    return results


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("zip_path", help="Path to the Notion export ZIP")
    p.add_argument("--output-dir", default="./notion-migration-output",
                   help="Directory for output reports (default: ./notion-migration-output)")
    p.add_argument("--supabase-url", default=os.environ.get("SUPABASE_URL",
                   "https://ugfrglbcoivkprjqvjzz.supabase.co"))
    p.add_argument("--service-role-key",
                   default=os.environ.get("SUPABASE_SERVICE_ROLE_KEY", ""),
                   help="Supabase service role key (bypasses RLS). "
                        "Get it from: Supabase dashboard → Project Settings → API → service_role key. "
                        "Env: SUPABASE_SERVICE_ROLE_KEY")
    p.add_argument("--anon-key", default=os.environ.get("SUPABASE_ANON_KEY",
                   "sb_publishable_GyandyvuVbF0RxZLnugz4A_GlNIstPc"))
    return p.parse_args()


def write_csv(path: Path, rows: list[dict]):
    if not rows:
        path.write_text("(no data)\n")
        return
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys())
        w.writeheader()
        w.writerows(rows)


def write_json(path: Path, data):
    path.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")


def main():
    args = parse_args()
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"[1/9] Opening ZIP: {args.zip_path}")
    zf = open_zip(args.zip_path)

    # ------------------------------------------------------------------
    # Step 2: Parse cluster .md files
    # ------------------------------------------------------------------
    print("[2/9] Parsing cluster .md files...")
    cluster_md_paths = find_entries_re(zf, r"Strength Clusters/[^/]+\.md$")
    clusters = []
    for path in cluster_md_paths:
        content = read_entry(zf, path)
        c = parse_cluster_md(content, path)
        if c["date"] or c["start_time_raw"]:
            clusters.append(c)
    print(f"  {len(clusters)} clusters parsed (of {len(cluster_md_paths)} .md files)")

    # ------------------------------------------------------------------
    # Step 3: Parse individual set .md files
    # ------------------------------------------------------------------
    print("[3/9] Parsing individual set .md files...")
    set_md_paths = find_entries_re(zf, r"Exercises/.+Log/.+\.md$")
    all_sets = []
    for path in set_md_paths:
        content = read_entry(zf, path)
        s = parse_set_md(content, path)
        if s["date"] and s["reps"] is not None:
            all_sets.append(s)
    print(f"  {len(all_sets)} set records parsed")

    # Build set lookup: cluster_id → list of set dicts
    sets_by_cluster: dict[str, list[dict]] = {}
    for s in all_sets:
        cid = s.get("cluster_id")
        if cid:
            sets_by_cluster.setdefault(cid, []).append(s)

    # Also build: (exercise_dir, date, set_num) → set dict for CSV enrichment
    set_key_index: dict[tuple, dict] = {}
    for s in all_sets:
        k = (s["exercise_dir"], s["date"], s["set_num"])
        set_key_index[k] = s

    # ------------------------------------------------------------------
    # Step 4: Parse main exercise log CSVs (for notes/weight_ref enrichment)
    # ------------------------------------------------------------------
    print("[4/9] Parsing main exercise log CSVs...")
    exercise_log_paths = find_entries_re(zf, r"Exercises/[^/]+/[^/]+ Log [0-9a-f]+\.csv$")
    # Also find non-standard log names
    exercise_log_paths += find_entries_re(zf, r"Exercises/[^/]+/[A-Z]+ Log [0-9a-f]+\.csv$")
    exercise_log_paths = list(set(p for p in exercise_log_paths if "_all.csv" not in p))

    csv_sets_by_key: dict[tuple, dict] = {}
    notion_exercise_dirs: list[str] = []

    for path in exercise_log_paths:
        ex_dir_m = re.search(r"Exercises/([^/]+)/", path)
        if not ex_dir_m:
            continue
        ex_dir = ex_dir_m.group(1)
        if ex_dir not in notion_exercise_dirs:
            notion_exercise_dirs.append(ex_dir)
        content = read_entry(zf, path)
        csv_rows = parse_exercise_log_csv(content, ex_dir)
        for row in csv_rows:
            k = (ex_dir, row["date"], row["set_num"])
            csv_sets_by_key[k] = row

    print(f"  {len(exercise_log_paths)} exercise log CSVs — {len(notion_exercise_dirs)} exercises — {len(csv_sets_by_key)} set rows")

    # ------------------------------------------------------------------
    # Step 5: Parse conditioning CSV
    # ------------------------------------------------------------------
    print("[5/9] Parsing conditioning CSV...")
    cond_paths = find_entries_re(zf, r"Cardio.*Conditioning[^/]*\.csv$")
    conditioning_sessions = []
    for path in cond_paths:
        if "_all" in path:
            continue
        content = read_entry(zf, path)
        conditioning_sessions += parse_conditioning_csv(content)
    print(f"  {len(conditioning_sessions)} conditioning sessions")

    zf.close()

    # ------------------------------------------------------------------
    # Step 6: Query Supabase
    # ------------------------------------------------------------------
    print("[6/9] Querying Supabase...")
    # Prefer service role key (bypasses RLS); fall back to anon key
    api_key = args.service_role_key or args.anon_key
    if not args.service_role_key:
        print("  NOTE: no --service-role-key provided. The anon key cannot bypass RLS.")
        print("  Get it from: Supabase dashboard → Project Settings → API → service_role")
        print("  Pass it with: --service-role-key <key>  or  SUPABASE_SERVICE_ROLE_KEY=<key>")

    try:
        ft_library = supabase_get(
            args.supabase_url, api_key, "exercise_library",
            select="id,name,category,primary_muscles,secondary_muscles,equipment"
        )
        print(f"  exercise_library: {len(ft_library)} exercises")
        if len(ft_library) == 0 and not args.service_role_key:
            print("  (0 rows — RLS likely blocking anon access. Provide --service-role-key for exercise mapping.)")
    except Exception as e:
        print(f"  WARNING: could not fetch exercise_library: {e}")
        ft_library = []

    try:
        # Fetch strength sessions; filter type client-side to avoid param name clash
        ft_sessions_raw = supabase_get(
            args.supabase_url, api_key, "exercise_sessions",
            select="id,start_time,end_time,details,source,type",
        )
        ft_strength = [s for s in ft_sessions_raw if s.get("type") == "strength"]
        ft_all = ft_sessions_raw
        print(f"  exercise_sessions (all): {len(ft_all)}, strength: {len(ft_strength)}")
    except Exception as e:
        print(f"  WARNING: could not fetch exercise_sessions: {e}")
        ft_strength = []
        ft_all = []

    # ------------------------------------------------------------------
    # Step 7: Map exercises
    # ------------------------------------------------------------------
    print("[7/9] Mapping Notion exercises to FT exercise_library...")
    exercise_mapping, exercise_review = map_exercises(notion_exercise_dirs, ft_library)

    confirmed_count = len(exercise_mapping)
    review_count    = len(exercise_review)
    print(f"  {confirmed_count} confirmed mappings, {review_count} need review")

    # Build exercise_mapping.csv rows
    mapping_rows = []
    for notion_name, ft_ex in sorted(exercise_mapping.items()):
        mapping_rows.append({
            "notion_name":    notion_name,
            "ft_id":          ft_ex["id"],
            "ft_name":        ft_ex["name"],
            "ft_category":    ft_ex.get("category", ""),
            "ft_equipment":   ft_ex.get("equipment", ""),
            "match_level":    "confirmed",
        })

    # ------------------------------------------------------------------
    # Step 8: Session reconciliation
    # ------------------------------------------------------------------
    print("[8/9] Reconciling sessions...")
    reconciliation = reconcile_sessions(clusters, ft_strength, exercise_mapping)

    # Stats
    counts = {}
    for r in reconciliation:
        counts[r["classification"]] = counts.get(r["classification"], 0) + 1

    print(f"  ENRICH_EXISTING:  {counts.get('ENRICH_EXISTING', 0)}")
    print(f"  CREATE_HISTORICAL:{counts.get('CREATE_HISTORICAL', 0)}")
    print(f"  POSSIBLE_DUPLICATE:{counts.get('POSSIBLE_DUPLICATE', 0)}")

    # ------------------------------------------------------------------
    # Step 9: Generate reports
    # ------------------------------------------------------------------
    print("[9/9] Writing reports...")

    write_csv(out_dir / "exercise_mapping.csv", mapping_rows)
    write_csv(out_dir / "exercise_mapping_review.csv", exercise_review)
    write_csv(out_dir / "session_reconciliation.csv", reconciliation)

    session_match_review = [r for r in reconciliation if r["classification"] == "POSSIBLE_DUPLICATE"]
    write_csv(out_dir / "session_match_review.csv", session_match_review)

    # Conditioning review
    cond_review = []
    for c in conditioning_sessions:
        unresolved = {k: v for k, v in c["drill_links"].items() if v}
        cond_review.append({
            "name":          c["name"],
            "date":          c["date"].isoformat() if c["date"] else "",
            "template":      c["template"] or "",
            "drills":        "; ".join(c["drill_links"].keys()),
            "drill_count":   len(c["drill_links"]),
            "notes":         c["notes"] or "",
        })
    write_csv(out_dir / "conditioning_review.csv", cond_review)

    # Conflicts (multiple clusters on same date → session ambiguity)
    date_cluster_counts: dict[str, int] = {}
    for c in clusters:
        d = c["date"].isoformat() if c["date"] else "unknown"
        date_cluster_counts[d] = date_cluster_counts.get(d, 0) + 1
    conflict_rows = [
        {"date": d, "cluster_count": n}
        for d, n in date_cluster_counts.items() if n > 1
    ]
    write_csv(out_dir / "conflicts.csv", conflict_rows)

    # Build full migration manifest
    manifest = []
    for r in reconciliation:
        cluster = next((c for c in clusters if c["cluster_id"] == r["cluster_id"]), {})
        set_list = sets_by_cluster.get(r["cluster_id"], [])
        # Enrich sets with CSV data
        enriched_sets = []
        for s in set_list:
            k = (s["exercise_dir"], s["date"], s["set_num"])
            csv_row = csv_sets_by_key.get(k, {})
            ft_ex = exercise_mapping.get(s["exercise_dir"])
            enriched_sets.append({
                "exercise_notion":  s["exercise_dir"],
                "exercise_ft_id":   ft_ex["id"] if ft_ex else None,
                "exercise_ft_name": ft_ex["name"] if ft_ex else None,
                "set_num":          s["set_num"],
                "reps":             s["reps"],
                "weight_kg":        s["weight_kg"],
                "volume":           s["volume"],
                "new_pr_1rm":       s.get("new_pr_1rm"),
                "notes":            csv_row.get("notes") or s.get("notes"),
                "weight_reference": csv_row.get("weight_reference"),
                "session_intensity":csv_row.get("session_intensity"),
                "source_file":      s["source_file"],
            })

        manifest.append({
            "source":           "notion",
            "source_archive":   Path(args.zip_path).name,
            "source_record":    r["cluster_id"],
            "source_name":      r["cluster_name"],
            "source_date":      r["cluster_date"],
            "destination_type": "strength_session",
            "destination_id":   r["ft_session_id"] or None,
            "action":           r["classification"],
            "confidence":       float(r["confidence"]),
            "reason":           r["reason"],
            "session_metadata": {
                "template":    r["cluster_template"],
                "intensity":   cluster.get("session_intensity"),
                "duration_min":cluster.get("duration_min"),
                "calories":    cluster.get("calories"),
            },
            "exercises":        list(cluster.get("exercise_links", {}).keys()),
            "set_count":        r["cluster_set_count"],
            "sets":             enriched_sets,
        })

    write_json(out_dir / "migration_manifest.json", manifest)

    # Summary
    date_range_start = min((c["date"] for c in clusters if c["date"]), default=None)
    date_range_end   = max((c["date"] for c in clusters if c["date"]), default=None)

    summary = {
        "generated_at":          datetime.now().isoformat() + "Z",
        "source_archive":        Path(args.zip_path).name,
        "notion": {
            "clusters_total":          len(clusters),
            "set_records_total":        len(all_sets),
            "conditioning_sessions":    len(conditioning_sessions),
            "exercise_directories":     len(notion_exercise_dirs),
            "date_range_start":         date_range_start.isoformat() if date_range_start else None,
            "date_range_end":           date_range_end.isoformat() if date_range_end else None,
        },
        "ft_current": {
            "exercise_library_count":   len(ft_library),
            "strength_sessions_count":  len(ft_strength),
            "all_sessions_count":       len(ft_all),
        },
        "exercise_mapping": {
            "confirmed":  confirmed_count,
            "needs_review": review_count,
        },
        "session_reconciliation": {
            "ENRICH_EXISTING":    counts.get("ENRICH_EXISTING", 0),
            "CREATE_HISTORICAL":  counts.get("CREATE_HISTORICAL", 0),
            "POSSIBLE_DUPLICATE": counts.get("POSSIBLE_DUPLICATE", 0),
        },
        "date_conflicts":          len(conflict_rows),
        "output_dir":              str(out_dir.resolve()),
        "reports": [
            "exercise_mapping.csv",
            "exercise_mapping_review.csv",
            "session_reconciliation.csv",
            "session_match_review.csv",
            "conditioning_review.csv",
            "conflicts.csv",
            "migration_manifest.json",
            "migration_summary.json",
        ],
    }
    write_json(out_dir / "migration_summary.json", summary)

    print(f"\n{'='*60}")
    print(f"Reconciliation complete. Output: {out_dir.resolve()}")
    print(f"  Clusters:          {summary['notion']['clusters_total']}")
    print(f"  Set records:       {summary['notion']['set_records_total']}")
    print(f"  Date range:        {summary['notion']['date_range_start']} → {summary['notion']['date_range_end']}")
    print(f"  Exercise mapping:  {confirmed_count} confirmed, {review_count} review")
    print(f"  ENRICH_EXISTING:   {counts.get('ENRICH_EXISTING', 0)}")
    print(f"  CREATE_HISTORICAL: {counts.get('CREATE_HISTORICAL', 0)}")
    print(f"  POSSIBLE_DUPLICATE:{counts.get('POSSIBLE_DUPLICATE', 0)}")
    print(f"\nReview these files before any Supabase write:")
    print(f"  exercise_mapping_review.csv  ({review_count} exercises)")
    print(f"  session_match_review.csv     ({len(session_match_review)} sessions)")
    print(f"  conflicts.csv                ({len(conflict_rows)} date conflicts)")


if __name__ == "__main__":
    main()
