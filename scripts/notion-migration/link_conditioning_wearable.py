"""
Links Notion conditioning sessions to same-day Health Connect 'other' sessions.

Patches each matched Notion session's details with:
  wearable_session_id: <HC session id>

This makes v_conditioning_drills automatically surface HR/calories/actual duration
for the 76 overlapping dates.

Usage:
  python link_conditioning_wearable.py [--apply]

Env:
  SUPABASE_SERVICE_ROLE_KEY
"""

import argparse
import json
import os
import sys

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

SUPABASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co"


def headers(key):
    return {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "Prefer": "return=representation",
    }


def get_all(key, table, select, extra=None):
    params = {"select": select, "limit": "10000"}
    if extra:
        params.update(extra)
    r = requests.get(f"{SUPABASE_URL}/rest/v1/{table}", headers=headers(key), params=params)
    r.raise_for_status()
    return r.json()


def patch(key, session_id, details, dry_run):
    if dry_run:
        return
    r = requests.patch(
        f"{SUPABASE_URL}/rest/v1/exercise_sessions",
        headers=headers(key),
        params={"id": f"eq.{session_id}"},
        json={"details": details},
    )
    if not r.ok:
        raise requests.HTTPError(f"{r.status_code} {r.text}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apply", action="store_true")
    p.add_argument("--service-role-key", default=os.environ.get("SUPABASE_SERVICE_ROLE_KEY", ""))
    args = p.parse_args()

    if not args.service_role_key:
        sys.exit("SUPABASE_SERVICE_ROLE_KEY required")

    dry_run = not args.apply
    print(f"{'DRY-RUN' if dry_run else 'APPLY'} mode\n")

    key = args.service_role_key

    print("Fetching Notion conditioning sessions...")
    notion = get_all(key, "exercise_sessions", "id,start_time,details",
                     {"source": "eq.notion", "type": "eq.other"})
    print(f"  {len(notion)} sessions")

    print("Fetching Health Connect 'other' sessions...")
    hc = get_all(key, "exercise_sessions", "id,start_time,end_time,avg_hr,max_hr,calories_active,duration_min",
                 {"source": "eq.health_connect", "type": "eq.other"})
    print(f"  {len(hc)} sessions")

    # Index HC sessions by date; if multiple on same day keep the one with most HR data
    hc_by_date: dict[str, dict] = {}
    for s in hc:
        date = s["start_time"][:10]
        existing = hc_by_date.get(date)
        if not existing or (s.get("avg_hr") and not existing.get("avg_hr")):
            hc_by_date[date] = s

    linked = already_linked = no_match = 0

    for session in notion:
        date = session["start_time"][:10]
        details = session.get("details") or {}

        if details.get("wearable_session_id"):
            already_linked += 1
            continue

        hc_session = hc_by_date.get(date)
        if not hc_session:
            no_match += 1
            continue

        details["wearable_session_id"] = str(hc_session["id"])
        try:
            patch(key, session["id"], details, dry_run)
            linked += 1
            if dry_run and linked <= 5:
                print(f"  [DRY] {date} notion={session['id']} → hc={hc_session['id']}"
                      f" (avg_hr={hc_session.get('avg_hr','?'):.0f}" if hc_session.get("avg_hr") else
                      f"  [DRY] {date} notion={session['id']} → hc={hc_session['id']}")
        except Exception as e:
            print(f"  ERROR {session['id']}: {e}")

    print(f"""
==============================
{'DRY RUN ' if dry_run else ''}Complete
  Linked:          {linked}
  Already linked:  {already_linked}
  No HC match:     {no_match}
""")
    if dry_run:
        print("Pass --apply to write.")


if __name__ == "__main__":
    main()
