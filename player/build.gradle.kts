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
    // Automix's analyser and loop mix (BitChord's native code).
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // The models are read whole into ONNX Runtime: stored, not compressed, they open at once.
    androidResources { noCompress += "onnx" }
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
    api(project(":core:automix"))
    implementation(libs.onnxruntime.android)
    // Automix's measured features travel as JSON between the native analyser and Kotlin.
    implementation(libs.kotlinx.serialization.json)
    api(libs.media3.session)
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
    // Casting to a TV (BitChord's and LastWave's): Google Cast's sender and its route discovery, and
    // the small server that hands the TV what the phone plays (LastWave's CastStreamServer).
    api(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    // A kept file's words read back as any player reads them.
    testImplementation(project(":core:lyrics"))
    testImplementation(libs.kotlin.test)
}
