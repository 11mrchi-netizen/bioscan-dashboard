# Supplement source spikes (DAV-359)

Version 1.0.0 · checked 2026-10-06. Findings below were read from the providers' own documentation and, for DSLD, confirmed with live read-only requests. Anything not stated by the provider is marked "not stated"; none of it is assumed.

## NIH DSLD — implemented

`supabase/functions/supplement-dsld-lookup` (verify_jwt on, read-only, writes nothing).

- API: `https://api.ods.od.nih.gov/dsld/v9`, no key required (confirmed with live requests). Rate limits and usage terms: **not stated** in the API guide.
- Endpoints used: `GET /search-filter?q=…&size=…` and `GET /label/{id}`.
- Quirks found and handled:
  - A search hit does **not** carry the UPC. The label (`/label/{id}`) has `upcSku`; the function treats hits only as candidates and keeps a label only when its own `upcSku` equals the scanned barcode (ignoring spaces and leading zeros).
  - `upcSku` is stored inconsistently (`"0 33674 13941 7"` for some labels, digits-only for others) and the exact-phrase match is token based, so the function probes as-passed, digits-only, UPC-A (for a leading-zero EAN-13) and GS1-spaced forms in order.
  - One UPC can match several label versions (the live test returned 5); the app compares the first and stores all of them in the snapshot payload.
  - Ingredient amounts live at `ingredientRows[].quantity[].{quantity, unit}`; rows without a numeric quantity (e.g. blends) are kept with `amount = null`, never invented.
- The app stores each verification as a per-user `supplement_source_snapshots` row (source, DSLD id, version, retrieval time, payload hash, payload, match status, conflicts). It never modifies the user's own product or ingredients; conflicting provider data is recorded, not applied.

## SuppCo — decision: **GO for personal use** (read-only adapter, optional dependency)

Verified from `https://supp.co/developers` and `https://api.supp.co/openapi.json`:

- A public API exists. OpenAPI at `api.supp.co/openapi.json`; the recommended agent interface is an MCP server at `api.supp.co/mcp`. Public reads need **no authentication**.
- `GET /api/products` supports `query`, `upc`, `upcs` (comma-separated), `amazonProductID` and `shopifyVariantID`, so barcode lookup **is** supported. Product responses include brand, category, format, serving size, servings per container, suggested use, other ingredients, verified status, trust scores and recall/testing info. `/api/nutrients`, `/api/protocols/{slug}` and lab-result endpoints also exist.
- Personal stack and lab-report tools require OAuth 2.1 with dynamic client registration. They are out of scope for this milestone (no private SuppCo personal-stack features without a supported authenticated integration).
- **Live check (2026-10-06):** `GET /api/products?upc=033674139417` returned HTTP 200 with no auth, `Cache-Control: max-age=300`, 10 results per page, and no rate-limit headers. Fields include `format`, `serving_size` (free string such as `"1 Tablet(s)"`), `servings_per_container`, `suggested_use`, `validated`, `off_market`, trust scores (strings), `trust_score_details`, `active_fda_recall`, `received_fda_warning_letter`, `failed_any_tests`, `price`, `price_per_serving`, and `product_ingredients`. UPC lookup is reliable; name search is patchy (e.g. "now foods l-tyrosine" returned 0 results).
- **Terms (found at `https://supp.co/about/terms-of-use`; `/terms` is a 404):** §5.3 prohibits using automated tools "to scrape or download data from any web pages" and commercially exploiting the Services; §1 grants a personal-use license. The **API is not addressed** by these terms. Rate limits and pricing for non-public use remain **not stated** (partner/write access is by contacting partners@supp.co).

Decision (updated): **GO for personal use**, using only the documented public API. Per-user snapshots with source, retrieval date and visible attribution are kept for the user's own pantry and verification. No scraping, no redistribution, lookups only on explicit user action or manual refresh (repeat calls deduped within the 5-minute cache window), no background polling, no runtime dependency (the app works without it). Revisit with partners@supp.co before the app is shared with anyone else.
