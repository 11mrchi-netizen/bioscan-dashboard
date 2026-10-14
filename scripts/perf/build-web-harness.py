"""Builds perf-harness/index.html: the real index.html with the supabase-js CDN script swapped for scripts/perf/supabase-stub.js.

    python scripts/perf/build-web-harness.py
    python -m http.server 8532          # from the repo root (or preview_start "static")
    open http://localhost:8532/perf-harness/index.html

perf-harness/ is git-ignored; it is regenerated from the current index.html every time.
"""
import pathlib
import shutil
import sys

root = pathlib.Path(__file__).resolve().parents[2]
src = (root / "index.html").read_text(encoding="utf-8")
marker = '<script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.min.js"></script>'
if marker not in src:
    sys.exit("index.html no longer loads supabase-js from jsDelivr under the expected tag; update this script")
out = root / "perf-harness"
out.mkdir(exist_ok=True)
(out / "index.html").write_text(src.replace(marker, '<script src="./supabase-stub.js"></script>'), encoding="utf-8")
shutil.copy(root / "scripts" / "perf" / "supabase-stub.js", out / "supabase-stub.js")
# the page's relative links (login.html, sw.js) are not needed by the harness
print("wrote", out / "index.html")
