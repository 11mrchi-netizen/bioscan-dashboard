# 08 — Calendar incremental sync and Drive GPX resolution

**Linear:** [DAV-145](https://linear.app/biodashboard/issue/DAV-145) · [DAV-146](https://linear.app/biodashboard/issue/DAV-146) · **Status:** code complete, live verification pending device access

## DAV-145 — incremental Calendar sync

`data/CalendarSyncRepository.kt` is deliberately separate from `data/MapRepository.kt`'s
`fetchUpcomingEvents()` (doc 01's own finding): that one is a live, unpersisted 24h-window read,
rebuilt from scratch every time the Map tab opens. This is a real incremental sync using Google
Calendar's own documented protocol:

- **First sync** (no stored `syncToken`): `timeMin=now`, `showDeleted=true`, `singleEvents=true`,
  paginated via `pageToken`. The last page's `nextSyncToken` is saved to `calendar_sync_state`.
- **Later syncs**: `syncToken=<stored>` instead of `timeMin`/`showDeleted` — Google returns only
  what changed (including cancellations) since the last sync, never the whole calendar again.
- **Token expiry** (`HTTP 410 Gone`, Google's own documented signal that a syncToken can no longer
  resolve to a delta): the local `calendar_events` cache for that calendar is cleared and a fresh
  full sync reseeds it — this is Google's normal, expected token lifecycle, not treated as an error.
- **Dedup and updates**: `unique(user_id, calendar_id, event_id)` on `calendar_events`, upserted on
  every sync — a changed event overwrites its own row; a cancelled one gets `is_cancelled = true`
  rather than being silently dropped (so a consumer can tell "removed" from "never existed").
- **Consent stays minimal**: reuses `auth/GoogleAuthorizationManager.kt`'s existing
  `calendar.readonly` grant as-is — no new scope requested.
- **Graceful unavailability**: `sync()` never throws past its own boundary — every real failure path
  (network, auth, a non-410 API error) resolves to `CalendarSyncResult.Failure(message)`, so a caller
  (DAV-148's pipeline) can mark the feature unavailable for this run without crashing anything else.

## DAV-146 — Drive GPX resolution (design, implementation lands with DAV-148)

Per this ticket's own text, the Calendar API's real `attachments` array (already captured verbatim
into `calendar_events.attachments` jsonb by DAV-145's sync — `fileId`/`fileUrl`/`mimeType`/`title`,
Google's own field names) is the **primary** resolution path, checked first. `domain/NextSession.kt`'s
existing `parseGpxLink()` (a regex over the event description for a Drive share URL) becomes the
**documented fallback**, used only when no attachment resolves — exactly the priority DAV-146's own
text asks for ("prefer explicit attachments... keep fuzzy matching as an optional fallback"). No new
code was needed for the fallback path since it already exists; the primary path's actual file-ID
extraction and download live in DAV-148's pipeline function, where both paths converge into one
`driveFileId: String?` before anything downloads.

## Verification

No pure-math surface here to unit-test (this is an I/O repository, like `NutritionBarcodeLookupRepository`
and friends) — `./gradlew compileDebugKotlin testDebugUnitTest` confirms it builds clean and doesn't
regress anything, but real verification needs a live OAuth token and a real Calendar account, the same
device access DAV-143/144 are already waiting on.
