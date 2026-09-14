import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bioscan.fieldterminal"
        minSdk = 26 // Health Connect (Phase G) requires API 26+
        targetSdk = 35
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // Extended icon set -- guarantees Home/LocationOn/List/Settings resolve
    // without checking each one against the smaller default icon set.
    // Step 4 replaces these placeholder icons with the mockups' exact
    // inline-SVG shapes anyway, so this dependency is temporary scaffolding.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Supabase Kotlin client (community-maintained, io.github.jan-tennert.supabase).
    // Confirm these module names/versions are still current at build time --
    // this ecosystem has renamed modules before (gotrue-kt -> auth-kt).
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-android:2.3.12")

    // Native Google sign-in via Android's Credential Manager (confirmed current
    // approach per Supabase's own native-Android-auth docs -- see Step 2 notes
    // in mobile-app-implementation-roadmap.md).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
