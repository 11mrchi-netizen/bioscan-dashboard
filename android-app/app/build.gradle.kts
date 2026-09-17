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
            isMinifyEnabled = false
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
