import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Playlists in and out of AETHER: AETHER's own file, M3U, CSV (Exportify's too), Spotify's data
// download and public links, read leniently; the matching of a described song to one that plays here;
// and the rules that keep an import from ever making a duplicate. Pure Kotlin: the app brings the
// files, the network and the library.
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
    api(project(":core:model"))
    // The title and artist rules every matcher in AETHER shares (PODIUM's normaliser).
    implementation(project(":core:lyrics"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // Only for liveImport: matching a real page's songs against YouTube Music, live.
    testImplementation(project(":sources:youtube"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp)
}

// A saved playlist page, every song looked for on YouTube Music live (needs the network).
// ./gradlew :core:transfer:liveImport -Ppage=/path/to/page.html -Pkind=spotify|apple
tasks.register<JavaExec>("liveImport") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.core.transfer.LiveImportKt")
    args = listOfNotNull(providers.gradleProperty("page").orNull, providers.gradleProperty("kind").orNull)
}
