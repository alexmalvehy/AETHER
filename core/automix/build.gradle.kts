import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Automix's engine, from BitChord (which ported it from Orchard): what's stored about a song (tempo,
// beat grids, structure, energy, vocals), how ambitious a transition that evidence allows, and the
// plan for one pair of songs. Pure Kotlin: the player measures, plays and renders.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
