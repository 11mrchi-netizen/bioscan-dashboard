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

## SuppCo — decision: **conditional GO for a read-only, optional adapter; not built**

Verified from `https://supp.co/developers` and `https://api.supp.co/openapi.json`:

- A public API exists. OpenAPI at `api.supp.co/openapi.json`; the recommended agent interface is an MCP server at `api.supp.co/mcp`. Public reads need **no authentication**.
- `GET /api/products` supports `query`, `upc`, `upcs` (comma-separated), `amazonProductID` and `shopifyVariantID`, so barcode lookup **is** supported. Product responses include brand, category, format, serving size, servings per container, suggested use, other ingredients, verified status, trust scores and recall/testing info. `/api/nutrients`, `/api/protocols/{slug}` and lab-result endpoints also exist.
- Personal stack and lab-report tools require OAuth 2.1 with dynamic client registration. They are out of scope for this milestone (no private SuppCo personal-stack features without a supported authenticated integration).
- **Not stated / unresolved:** rate limits; pricing or partner requirements for non-public use (partner/write access is by contacting partners@supp.co); and, importantly, whether storing or caching their data is permitted. `https://supp.co/terms` returned HTTP 404 when checked, so the terms could not be read.

Decision: the interface is sufficient (UPC lookup, no auth), but **do not store or cache SuppCo data until its terms on storage/redistribution are located or confirmed with the provider**. Until then, the adapter, if built, must be live-read-only per request and optional, with the same snapshot-and-never-overwrite pattern DSLD uses and no runtime dependency. Do not scrape.

Open item: confirm SuppCo's terms (or ask partners@supp.co) before any caching.
