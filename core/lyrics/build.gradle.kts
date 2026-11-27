import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lyrics, from PODIUM: provider-independent models, LRC parsing, the LRCLIB provider, matching and a
// cache. Pure Kotlin; the app supplies the HTTP, the cache directory and the listener's consent.
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ./gradlew :core:lyrics:liveCheck   asks every lyrics library live for a few songs
tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Asks every lyrics library live and says what each answered."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.core.lyrics.LiveCheckKt")
    providers.gradleProperty("debug").orNull?.let { systemProperty("debug", it) }
    args = listOfNotNull(providers.gradleProperty("songs").orNull)
}
