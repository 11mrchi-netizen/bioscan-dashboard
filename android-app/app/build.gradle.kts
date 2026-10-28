import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android -- AGP 9's built-in Kotlin support
    // replaces it (see root build.gradle.kts). The Compose Compiler plugin
    // is still required separately, unrelated to that change.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Google's Web OAuth client ID (NOT a secret -- client IDs are public
// identifiers, unlike client secrets, same as the anon key convention used
// throughout this project) -- read from local.properties so it's easy to set
// per-checkout without editing tracked source. See android-app/README.md.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val googleWebClientId: String = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""

android {
    namespace = "com.bioscan.fieldterminal"
    // 37, not 36: a real build error said Compose 2026.08.00's own
    // dependencies require compiling against API 37+ -- confirmed via that
    // error, not assumed from a "current Android version" search alone.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bioscan.fieldterminal"
        minSdk = 26 // Health Connect (Phase G) requires API 26+
        // Deliberately lower than compileSdk -- 37's runtime behavior
        // changes aren't something this scaffold has any reason to opt into
        // yet. compileSdk only needed bumping to satisfy a dependency
        // requirement, not because the app needs newer platform APIs.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildTypes {
        release {
            // Perf pass: shrink + optimise (R8) and drop unused resources. Signed with the debug keystore on purpose: the
            // Google sign-in client is registered against that SHA-1, so a release build signs in like the debug one. This
            // is a personal build, not a store upload. The daily driver stays debug until the release build has had a
            // tab-by-tab smoke test (R8 is the one change here that can only fail at runtime).
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Also sets the Kotlin JVM target under AGP's built-in Kotlin support
        // -- no separate `kotlinOptions` block exists anymore (that was the
        // org.jetbrains.kotlin.android plugin's DSL extension, now removed).
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Analysis Layer 3 / Phase 7: the metric methodology registry is shared data (read by the Node tests and the console too),
    // so it lives next to the shared analysis core and is packaged here as an asset, one source of truth for all three.
    sourceSets {
        getByName("main") {
            assets.srcDir("../../supabase/functions/_shared/metric-registry")
            // Health concerns: the region and symptom vocabulary, shared with the analysis core and the migration's seed.
            assets.srcDir("../../supabase/functions/_shared/health-vocab")
            // Running form analyser: MediaPipe's pose model, downloaded at build time (downloadPoseModel below).
            // A plain folder: AGP 9 refuses a Provider here. The download still runs first (preBuild dependsOn downloadPoseModel below).
            assets.srcDir(layout.buildDirectory.dir("generated/pose-model").get().asFile)
        }
    }

    // The pose model is read in place from the APK (memory-mapped), which needs it stored uncompressed.
    androidResources {
        noCompress += listOf("task")
    }
}

// Running form analyser (docs/running-form/README.md): MediaPipe's pose model, version 1 of the "full" variant (9.4 MB),
// fetched from Google's model bucket at build time rather than committed, and checked against its SHA-256 so a changed
// or broken download fails the build instead of shipping. Already-present files with the right hash are not fetched.
val poseModelUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"
val poseModelSha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
val poseModelFile = layout.buildDirectory.file("generated/pose-model/pose_landmarker_full.task")
val downloadPoseModel by tasks.registering {
    outputs.file(poseModelFile)
    doLast {
        val target = poseModelFile.get().asFile
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (target.isFile && sha256(target) == poseModelSha256) return@doLast
        target.parentFile.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        URI(poseModelUrl).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = sha256(part)
        check(got == poseModelSha256) { "Pose model download has SHA-256 $got, expected $poseModelSha256" }
        part.renameTo(target)
    }
}
tasks.named("preBuild") { dependsOn(downloadPoseModel) }

// Performance pass: ./gradlew :app:assembleDebug -PcomposeReports writes stability/skippability reports to
// app/build/compose_compiler (off by default, it slows the build).
if (project.hasProperty("composeReports")) {
    composeCompiler {
        reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
        metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // Status tile icons (barbell/food/heart/beaker, DAV-70) -- Favorite is in
    // material3's own bundled core icon set, but FitnessCenter/Restaurant/
    // Science are part of the extended set, which is its own separate
    // artifact (version pinned to the same BOM everything else here uses).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Supabase Kotlin client (community-maintained, io.github.jan-tennert.supabase).
    // Bumped 2026-09-15 after a real crash: the app crashed on launch with
    // NoClassDefFoundError on io.ktor.client.plugins.HttpTimeout, because
    // supabase-kt 3.8.0 (this BOM) requires Ktor 3.5.1 internally (confirmed
    // by reading supabase-kt-android's own Gradle module metadata directly,
    // not guessed) -- the explicit ktor-client-android:2.3.12 pin below was
    // two major Ktor versions behind and shadowed whatever compatible
    // version Gradle would otherwise have resolved.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    // Zepp integration plan: first client-side Edge Function call site (zepp-extract).
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-android:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Native Google sign-in via Android's Credential Manager (confirmed current
    // approach per Supabase's own native-Android-auth docs -- see Step 2 notes
    // in mobile-app-implementation-roadmap.md).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Step 14: Play Services' Authorization API -- a distinct flow from the
    // Credential Manager sign-in above, used only to request the
    // calendar.readonly/drive.readonly scopes the Map tab needs. Confirmed
    // current API/dependency directly against Android's own developer docs
    // (developer.android.com/identity/authorization), not assumed.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Barcode scanning via Google Play services: no CAMERA permission, no camera code.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Routine intelligence R2: activity transitions, geofences and fused location (docs/routine-intelligence/).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Map tab background: osmdroid (free, no API key of its own, no billing)
    // over CARTO's free-tier Dark Matter tiles -- chosen over the Google Maps
    // SDK specifically to avoid requiring a billing-enabled Google Cloud
    // project just to display a map. See ROADMAP.md for the comparison.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Phase G1: Health Connect, current stable release (1.2.0 exists but is
    // alpha-only as of writing -- confirmed against the Jetpack releases
    // page, not assumed). See ROADMAP.md for the full Phase G plan.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Runs the Health Connect sync as real background work instead of a
    // plain Activity-scoped coroutine -- the latter dies (real on-device
    // error: "Software caused connection abort") the moment the user
    // switches away mid-sync, since Android doesn't guarantee a foreground
    // Activity's coroutines keep running once backgrounded. WorkManager
    // survives that (and even process death), and retries on failure with
    // its own backoff instead of requiring the user to babysit the app.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Running form analyser: MediaPipe Tasks pose landmarker (Apache 2.0, runs on the phone). 0.10.26 is the first release
    // whose Android packages support the 16 KB page size.
    implementation("com.google.mediapipe:tasks-vision:0.10.26")

    // Perf pass: installs the baseline profile (src/main/baseline-prof.txt) so release builds start precompiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
