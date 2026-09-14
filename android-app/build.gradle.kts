// Top-level build file. Bumped 2026-09-14 from the original Step 1 pins
// (AGP 8.6.0 / Kotlin 2.0.20) after a real build failure surfaced a genuine
// incompatibility: Kotlin 2.0.20's JavaVersion.parse() throws on JDK 25's
// version string (upstream bug KT-83610) -- and this machine's Android
// Studio bundles JBR 25 as its build JVM.
//
// AGP 9.0 introduced *built-in* Kotlin support -- applying
// org.jetbrains.kotlin.android separately now fails outright under AGP 9.x
// (confirmed via a real build error, not assumed). AGP manages its own
// Kotlin Gradle Plugin version as a runtime dependency, well above the
// 2.1.20 floor that fixes the JDK 25 bug above.
//
// The Compose Compiler plugin is a separate thing, still required since
// Kotlin 2.0 regardless of AGP's built-in Kotlin support (also confirmed via
// a real build error after removing it alongside kotlin.android -- one early
// web result claimed otherwise, but the actual Gradle error was unambiguous).
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
