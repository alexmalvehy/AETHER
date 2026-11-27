import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A song's moving cover from wherever has one (after BitChord's and LastWave-Native's canvas, GPL-3.0):
// Apple Music's motion artwork (AETHER's own, from sources:apple, handed in) and the community's
// index of looping covers. Decoration for Now Playing: pure Kotlin, and the app supplies the HTTP and
// the listener's consent. Nothing here is played as music or needed for anything to work.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    api(project(":core:model"))
    // PODIUM's title normaliser: a cover is shown only for the song it's of.
    implementation(project(":core:lyrics"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
