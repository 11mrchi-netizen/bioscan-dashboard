# Field Terminal — Android app

Native Android companion app (Kotlin + Jetpack Compose). See the repo root
`ROADMAP.md` (P8) for why this exists, and `mobile-app-scoping.md` /
`mobile-app-implementation-roadmap.md` (not committed here — they live outside
the repo) for the full design/build sequencing this scaffold follows.

## Status

**Steps 1–4 done** (project scaffold, Google sign-in, 4-tab navigation
skeleton with Status's 5 sub-tabs, and the real `/design/` visual system
applied across all of it). Still placeholder *content* everywhere — Phase C
onward replaces each screen's placeholder text with real Supabase-backed
data.

**Fully verified end-to-end on a real emulator** (2026-09-15): installed,
launched without crashing, renders matching `/design/`'s tokens, and
"SIGN IN WITH GOOGLE" produces a real Supabase session with a real Google
account. Step 2's actual "done when" criterion is genuinely met, not just
built.

The first install crashed on launch with `NoClassDefFoundError` on
`io.ktor.client.plugins.HttpTimeout` -- a real Ktor/Supabase version
mismatch, not a design bug. See "Toolchain" below.

## Toolchain

The Step 1–3 pins (AGP 8.6.0, Kotlin 2.0.20, Gradle 8.9, compileSdk 35) were
written without a local SDK to verify against. Once this machine's Android
Studio (bundling JBR 25 as its build JVM) was available, a real build
surfaced a chain of genuine incompatibilities — fixed in order, each
confirmed via the actual build error rather than guessed:

1. **Kotlin 2.0.20 crashes under JDK 25** — `JavaVersion.parse("25.0.3")`
   throws (upstream bug KT-83610, fixed in Kotlin 2.1.20+). Bumped to
   Kotlin 2.4.20 (current stable).
2. **AGP 9.0+ removed the separate Kotlin Android plugin** — applying
   `org.jetbrains.kotlin.android` alongside AGP 9.4.0 now fails outright
   (AGP has *built-in* Kotlin support). Removed it; kept
   `org.jetbrains.kotlin.plugin.compose` (still required separately since
   Kotlin 2.0, unrelated to the built-in-Kotlin change — confirmed by a
   second real build error after an early web result claimed otherwise).
3. **Compose BOM 2026.08.00 needs compileSdk 37**, not 35/36 as guessed from
   a "what's the current Android version" search — bumped `compileSdk` to
   37 while deliberately keeping `targetSdk` at 36 (no reason to opt into
   API 37's runtime behavior changes yet; compileSdk only needed bumping to
   satisfy a dependency requirement).
4. **XML comments can't contain `--`** — a few resource files used `--` as
   an em-dash-style separator (fine in Kotlin `//` comments, illegal in XML
   comments per spec). Fixed the three affected files.
5. **A real `RowScope.weight()` resolution bug**, isolated by testing: an
   explicit `import androidx.compose.foundation.layout.weight` in this
   Compose version resolves to an unrelated internal symbol instead of the
   intended member extension. Fix was to remove the import entirely and let
   `weight()` resolve via `RowScope`'s implicit receiver, which needs no
   import at all.
6. **`NoClassDefFoundError` on `io.ktor.client.plugins.HttpTimeout` at
   runtime** (the build succeeded; this only showed up installing and
   launching on a real emulator) -- the explicit `ktor-client-android:2.3.12`
   pin was two major Ktor versions behind what `supabase-kt` 3.8.0 actually
   needs (Ktor 3.5.1, confirmed by reading `supabase-kt-android`'s own
   Gradle module metadata directly rather than trusting a version-number
   guess). Bumped the Supabase BOM from 3.0.0 to 3.8.0 and
   `ktor-client-android` to 3.5.1 to match.

Also: Android Studio itself ran `updateDaemonJvm` on this project at some
point, adding `gradle/gradle-daemon-jvm.properties` (pins the Gradle
*daemon's* own JVM to JetBrains Runtime 21 via auto-download, independent of
whatever `JAVA_HOME` is set to) and the `foojay-resolver-convention` plugin
in `settings.gradle.kts` that makes that auto-download possible. This is a
cleaner, more permanent fix for the same JDK-25-is-too-new class of problem
item 1 above patches at the Kotlin-compiler level -- both are kept.

Gradle wrapper is pinned to **9.7.1** (`gradle/wrapper/gradle-wrapper.properties`)
and, unlike the original Step 1 note, the wrapper jar/scripts (`gradlew`,
`gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) **are** committed now that
a real one has been generated and verified — the earlier "let Android Studio
generate it" plan was reasonable before any of this was build-tested, but a
verified wrapper is strictly better to commit than to regenerate blind.

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
   (Generated into the standard `~/.android/debug.keystore` location — same
   fixed debug alias/password Android tooling always uses — so Android
   Studio reuses it, not generates a conflicting second one. A *release*
   build needs its own separate SHA-1 registered later, once a release
   signing key exists — not needed yet.)
2. **Register both client IDs** (the existing web one + this new Android one)
   in Supabase Dashboard → Authentication → Providers → Google, comma-
   separated with the web client ID first — per Supabase's own docs, this is
   required for `signInWith(IDToken)` to accept tokens from either flow.
3. **Add the Web client ID** to `android-app/local.properties` (gitignored,
   same as always for Android projects) — **already done** on this machine
   as of Step 4:
   ```
   GOOGLE_WEB_CLIENT_ID=<the existing web OAuth client ID>
   ```
   Not a secret (client IDs are public identifiers, unlike client secrets —
   same distinction this project draws for the anon key vs. service-role
   key), just kept out of tracked source for per-checkout convenience.

## Opening this project

1. Open this `android-app/` folder in Android Studio (not the repo root).
2. Let it sync — the wrapper is now committed and verified, so this should
   be a normal sync, not a first-time bootstrap.
3. Run on a device or emulator (API 26+ — Health Connect, planned for
   Phase G, requires it) and confirm the 4 tabs + 5 Status sub-tabs actually
   look like `/design/`'s mockups, not just that the build succeeds.

## Package layout

- `com.bioscan.fieldterminal` — application ID and root package.
- `ui/theme/` — the real Field Terminal design tokens (`Color.kt`,
  `Type.kt`, `TextStyles.kt`, `Theme.kt`), extracted 1:1 from
  `/design/README.md`. Bundled fonts (JetBrains Mono, Saira, Saira
  Condensed — `res/font/`) came from Google's official open-source fonts
  repo; their OFL license text is in `licenses/fonts/`.
- `ui/components/` — small shared pieces used across multiple screens
  (`ScreenHeader`, `AmberButton`) so the header/button look isn't
  copy-pasted per screen.
- `ui/nav/` — the 4-tab structure (`FieldTerminalNavHost.kt`,
  `TopLevelTab.kt`) and Status's 5-sub-tab state (`StatusSubTab`).
- `ui/screens/` — one file per top-level screen. All placeholder content
  (Phase C onward replaces this).
