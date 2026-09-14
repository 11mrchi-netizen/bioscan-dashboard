# Field Terminal — Android app

Native Android companion app (Kotlin + Jetpack Compose). See the repo root
`ROADMAP.md` (P8) for why this exists, and `mobile-app-scoping.md` /
`mobile-app-implementation-roadmap.md` (not committed here — they live outside
the repo) for the full design/build sequencing this scaffold follows.

## Status

**Steps 1–2 done (project scaffold + Google sign-in).** Real navigation
(Step 3) and visual design (Step 4, gated on `/design/` mockups) are separate,
deliberately sequenced follow-ups — not built yet.

## Manual setup required before sign-in works

Step 2's roadmap doc flagged a real open question rather than letting it be
guessed at: does the existing (web) Google OAuth client work for a native
Android flow, or is a second, Android-specific one needed? Checked against
Google's and Supabase's current (Sept 2026) docs: **yes, a second client is
needed** — confirmed, not assumed. Concretely, before sign-in will work:

1. **Create an Android OAuth client** in Google Cloud Console (the same
   project already used for the web dashboard's OAuth) — type "Android",
   package name `com.bioscan.fieldterminal`, and this debug build's SHA-1
   certificate fingerprint:
   ```
   02:B6:50:DF:9A:B7:3A:EE:76:AD:0A:A6:E1:9E:81:28:56:FE:38:B0
   ```
   (Generated this session into the standard `~/.android/debug.keystore`
   location — same fixed debug alias/password Android tooling always uses —
   so Android Studio will reuse it, not generate a conflicting second one.
   A *release* build needs its own separate SHA-1 registered later, once a
   release signing key exists — not needed yet.)
2. **Register both client IDs** (the existing web one + this new Android one)
   in Supabase Dashboard → Authentication → Providers → Google, comma-
   separated with the web client ID first — per Supabase's own docs, this is
   required for `signInWith(IDToken)` to accept tokens from either flow.
3. **Add the Web client ID** (not the new Android one — Credential Manager's
   `GetGoogleIdOption` always wants the web client ID as its "server client
   ID") to `android-app/local.properties` (create this file — it's
   gitignored, same as `local.properties` always is for Android projects):
   ```
   GOOGLE_WEB_CLIENT_ID=<the existing web OAuth client ID>
   ```
   This isn't a secret (client IDs are public identifiers, unlike client
   secrets — same distinction this project already draws for the anon key
   vs. the service-role key), it's just kept out of tracked source so it's
   easy to set per-checkout.

## Opening this project

This scaffold was written without a local Android SDK/Gradle install to
verify against, so the very first open in Android Studio is also the first
real build check:

1. Open this `android-app/` folder in Android Studio (not the repo root).
2. Let it sync — Android Studio will download the Gradle distribution
   pinned in `gradle/wrapper/gradle-wrapper.properties` and generate
   `gradle/wrapper/gradle-wrapper.jar` itself (deliberately not committed —
   see `.gitignore`). This is normal for a hand-written scaffold, not a sign
   something's missing.
3. If sync reports outdated AGP/Kotlin/Compose/dependency versions, take the
   IDE's suggested upgrade — the versions in `build.gradle.kts` /
   `app/build.gradle.kts` were current when written, not pinned intentionally.
4. Run on a device or emulator (API 26+ — Health Connect, planned for Phase G,
   requires it).

## Package layout

- `com.bioscan.fieldterminal` — application ID and root package.
- `ui/theme/` — placeholder Compose theme only. The real design system comes
  from `/design/` (repo root) once Phase B (Step 4 of the implementation
  roadmap) applies it — don't hand-roll styling here before then.
