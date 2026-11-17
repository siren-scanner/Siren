import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sirenscout"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sirenscout"
        minSdk = 26
        targetSdk = 35
        versionCode = 66
        versionName = "1.0.64"
    }

    // Private settings for the maintainer's own builds, kept out of the shared code: the self-update
    // server for Siren / Siren Test lives in local.properties (siren.updateUrl, siren.updateFallbackUrl).
    val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
    /** A local.properties URL as a BuildConfig string, with [suffix] added; "" when not set. */
    fun localUrl(key: String, suffix: String = "") =
        "\"" + (local.getProperty(key)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { it + suffix } ?: "") + "\""
    // Where people send suggestions and find the code (gradle.properties: siren.repoUrl).
    val repoUrl = (project.findProperty("siren.repoUrl") as String?).orEmpty()
    val author = (project.findProperty("siren.author") as String?).orEmpty()

    // The release key lives outside the project (~/.android) so it never gets shared with the code.
    val keystoreProps = File(System.getProperty("user.home"), ".android/sirenscout-keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            create("release") {
                val p = Properties().apply { keystoreProps.inputStream().use(::load) }
                storeFile = File(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug builds install alongside as their own app, and can show made-up demo data (DemoMode).
        debug {
            applicationIdSuffix = ".demo"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Three apps that install side by side, each with its own data and car-display port:
    //  - "Siren" (stable) and "Siren Test" (new features first) update themselves from the
    //    maintainer's own server and use Google Play services and on-device AI;
    //  - "foss" is the open-source edition built by F-Droid: no Google libraries, no self-updates.
    flavorDimensions += "channel"
    defaultConfig {
        // Only the public (foss) edition links to the project page for suggestions; the
        // maintainer's own builds have no feedback buttons.
        buildConfigField("String", "REPO_URL", "\"\"")
        buildConfigField("String", "AUTHOR", "\"$author\"")
        // The open-source edition only talks to open services (no hexdb.io lookups).
        buildConfigField("boolean", "PUBLIC_EDITION", "false")
    }
    productFlavors {
        create("stable") {
            dimension = "channel"
            resValue("string", "app_name", "Siren")
            buildConfigField("String", "UPDATE_URL", localUrl("siren.updateUrl"))
            buildConfigField("String", "UPDATE_FALLBACK_URL", localUrl("siren.updateFallbackUrl"))
            buildConfigField("int", "CAR_PORT", "8080")
            buildConfigField("boolean", "TEST_BUILD", "false")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-test"
            resValue("string", "app_name", "Siren Test")
            buildConfigField("String", "UPDATE_URL", localUrl("siren.updateUrl", "/beta"))
            buildConfigField("String", "UPDATE_FALLBACK_URL", localUrl("siren.updateFallbackUrl", "/beta"))
            buildConfigField("int", "CAR_PORT", "8081")
            buildConfigField("boolean", "TEST_BUILD", "true")
        }
        create("foss") {
            dimension = "channel"
            applicationIdSuffix = ".foss"
            resValue("string", "app_name", "Siren")
            buildConfigField("String", "UPDATE_URL", "\"\"")
            buildConfigField("String", "UPDATE_FALLBACK_URL", "\"\"")
            buildConfigField("String", "REPO_URL", "\"$repoUrl\"")
            buildConfigField("boolean", "PUBLIC_EDITION", "true")
            buildConfigField("int", "CAR_PORT", "8082")
            buildConfigField("boolean", "TEST_BUILD", "false")
        }
    }
    sourceSets {
        // Google Play services and ML Kit code, shared by the two self-updating apps.
        getByName("stable").java.srcDir("src/google/java")
        getByName("beta").java.srcDir("src/google/java")
        // The maintainer's own network settings (update server address), if present; not shared.
        getByName("stable").res.srcDir(rootProject.file("private/res"))
        getByName("beta").res.srcDir(rootProject.file("private/res"))
        // Their release notes, which describe the maintainer's own setup. The open-source edition
        // has its own (src/foss/assets).
        getByName("stable").assets.srcDir(rootProject.file("private/assets"))
        getByName("beta").assets.srcDir(rootProject.file("private/assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Google libraries: only in Siren and Siren Test, never in the open-source (foss) edition.
    "stableImplementation"("com.google.android.gms:play-services-location:21.3.0")
    "betaImplementation"("com.google.android.gms:play-services-location:21.3.0")
    // On-device Gemini Nano (still a beta library).
    "stableImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta4")
    "betaImplementation"("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Play services pulls in an old Fragment that mishandles activity results; pin a fixed one.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (the Android stub just throws).
    testImplementation("org.json:json:20240303")
}
