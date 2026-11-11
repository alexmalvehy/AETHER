import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.aether.player"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    api(libs.media3.session)
    api(libs.media3.cast)
    api(libs.play.services.cast.framework)
    api(libs.androidx.mediarouter)
    implementation(libs.media3.exoplayer)
    // A source's copy may come as a DASH or HLS manifest (Hi-Res copies often do): open media only.
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // A kept file's words read back as any player reads them.
    testImplementation(project(":core:lyrics"))
    testImplementation(libs.kotlin.test)
}
