import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables (CI) or from keystore.properties (local builds).
// Updates install over the previous version only when every release is signed with the same key.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("RELEASE_KEYSTORE_PATH", "storeFile")

android {
    namespace = "dev.rtsearch.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.rtsearch.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"

        buildConfigField("String", "GITHUB_REPO", "\"kobyzskyi/rt-search-tv\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key, fall back to the debug key so the APK can still be installed for testing.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
