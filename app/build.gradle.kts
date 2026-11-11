import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: the key and its passwords live outside git, in keystore.properties (ignored) or in
// AETHER_KEYSTORE_* environment variables. Without either, the release APK is built unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val releaseStore = secret("storeFile", "AETHER_KEYSTORE_FILE")

// A release build installed beside the phone's own: -Paether.applicationIdSuffix=.rc. Testing one
// then needs no uninstall, which would wipe the listener's data.
val sideBySide = providers.gradleProperty("aether.applicationIdSuffix").orNull

android {
    namespace = "app.aether"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.aether"
        minSdk = 29
        targetSdk = 36
        // major * 1,000,000 + minor * 1,000 + patch, so it only ever rises.
        versionCode = 2_003_000
        versionName = "2.3.0"
    }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = secret("storePassword", "AETHER_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "AETHER_KEY_ALIAS")
                keyPassword = secret("keyPassword", "AETHER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Not debuggable by default, as in PODIUM: ART compiles it, so shaders and scrolling run
            // as they will for listeners. For a debugger: -Paether.debuggable=true
            isDebuggable = providers.gradleProperty("aether.debuggable").orNull == "true"
        }
        release {
            // Not shrunk: what's released is the same code that's tested on the phone every day, and
            // nothing can go missing that only reflection asks for. The libraries' baseline profiles
            // are still installed (profileinstaller), so the first run is already compiled.
            sideBySide?.let { applicationIdSuffix = it }
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:lyrics"))
    implementation(project(":player"))
    implementation(project(":sources:youtube"))
    implementation(project(":sources:local"))
    implementation(project(":sources:addon"))
    implementation(project(":sources:apple"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
    // The welcome's water: a short looping clip played muted into a TextureView.
    implementation(libs.media3.exoplayer)
    // An artist's short film: HLS, video only, kept in a cache of its own.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation("androidx.media3:media3-cast:1.11.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // The releases GitHub lists, for AETHER's own updates.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.profileinstaller)

    // Verse's line model and the offline albums (./gradlew :app:testDebugUnitTest).
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
