import java.util.Properties

// Home Terminal: the app for the Google TV Streamer 4K. Phase E1 only collects the home's environment (temperature,
// humidity, light, later air quality) from the Matter sensors in Google Home and writes it to Supabase.
// See docs/home-environment/README.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val googleWebClientId: String = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""

// The Google Home APIs SDK is hosted locally (see settings.gradle.kts), so a checkout without it must still build: with
// HOME_API_SDK_VERSION unset, src/homestub stands in for src/homeapi and the app says the SDK is missing instead of
// reading sensors. Set it in local.properties to the version you downloaded, e.g. HOME_API_SDK_VERSION=17.1.0.
val homeApiSdkVersion: String? = localProperties.getProperty("HOME_API_SDK_VERSION")?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.bioscan.hometerminal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bioscan.hometerminal"
        // 29: the Home APIs sample app's floor. The Streamer runs Android TV 14.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        // One definition for both apps: android-app/gradle.properties.
        buildConfigField("String", "SUPABASE_URL", "\"${providers.gradleProperty("supabaseUrl").get()}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${providers.gradleProperty("supabasePublishableKey").get()}\"")
        buildConfigField("boolean", "HOME_API_SDK", "${homeApiSdkVersion != null}")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same reason as :app: the Google sign-in client is registered against the debug keystore's SHA-1.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // kotlin, not java: under AGP 9's built-in Kotlin, a java.srcDir is not compiled by the Kotlin compiler.
            kotlin.directories += if (homeApiSdkVersion != null) "src/homeapi/java" else "src/homestub/java"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // Same Supabase client and Ktor engine as :app (see the reasoning there).
    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Google sign-in for the Supabase session, as in :app.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    if (homeApiSdkVersion != null) {
        implementation("com.google.android.gms:play-services-home:$homeApiSdkVersion")
        implementation("com.google.android.gms:play-services-home-types:$homeApiSdkVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    }

    testImplementation("junit:junit:4.13.2")
}
