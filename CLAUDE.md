# Working on this repository

## The app map: the owner's way of directing UI work

The owner keeps an editable map of the Android app ("Field Terminal") as a claude.ai artifact:

**Field Terminal Map** — https://claude.ai/artifact/27iuoPccEPA7ixdeoHZpLf

Every screen, overlay and sheet is a box; every tap between them is an arrow. The owner marks what they want changed
directly on the map, and it should always match the app as built.

### How it stores its data

The page keeps everything in its artifact database (read and write it with the `ArtifactData` tool and the URL above):

- `nodes/<id>`: one screen. Fields: `label`, `kind` (tab, subtab, page, overlay, sheet, legacy, entry), `area`
  (entry, today, detail, plan, log, lab, me, setup), `x`, `y` (canvas position), `summary`, `contains` (what is on the
  screen, top to bottom, one line each), `file` (the main source file), `status`, `note`.
- `edges/<source>__<target>`: one tap. Fields: `source`, `target`, `label` (what you tap), `how` (tap, dock, subtab,
  notification, auto), `status`, `note`.
- `status` is the request: `keep`, `change`, `remove` or `new`. `note` is what the owner wants changed, in their words.

### When asked to "read the map" or "build the map's changes"

1. List both collections and collect every node and edge whose `status` is not `keep` or whose `note` is not empty.
   That is the change list. Ask the owner only where a request is genuinely ambiguous.
2. Build the changes in the app. Run the tests and `assembleDebug` before pushing.
3. Update the map to match what was built:
   - add new screens and arrows with `status: "new"`, in free space near the screens they connect to;
   - delete screens and arrows that no longer exist;
   - rewrite `summary` and `contains` of the screens that changed;
   - set the requests that were done back to `status: "keep"` with an empty `note`.

   Read the documents first and pin every write to the version you read (`if_version`), so edits the owner made in
   the meantime are never overwritten. Keep the owner's box positions: only place new boxes.
4. Report what was built, what was not, and how the map changed.

The map's own page (HTML) does not need republishing to change its content: only the database changes.
