import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":ytmusic"))
    // PODIUM's title normaliser, to tell a music video's song from its covers and remixes.
    implementation(project(":core:lyrics"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Resolves a few songs' streams live and proves each serves audio (needs the network).
tasks.register<JavaExec>("streamCheck") {
    group = "verification"
    description = "Resolves YouTube Music streams live and checks both ends of each serve audio."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.sources.youtube.StreamCheckKt")
    // -Pids=a,b  -Pregion=IN  -Pcount=12  -Pdiagnose=true
    args = listOf("ids", "region", "count", "diagnose").mapNotNull { name -> project.findProperty(name)?.let { "$name=$it" } }
}

// Opens every "See all" on Home live and says what each holds (needs the network).
tasks.register<JavaExec>("browseCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.sources.youtube.BrowseCheckKt")
    args = listOf("region", "filter", "artist", "album", "radio").mapNotNull { name -> project.findProperty(name)?.let { "$name=$it" } }
}

// Asks Home with the languages and countries phones send, and says which the service refuses.
tasks.register<JavaExec>("localeCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.sources.youtube.LocaleCheckKt")
}

// What the service answers a session it won't accept (no real session is used).
tasks.register<JavaExec>("sessionCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.sources.youtube.SessionCheckKt")
}

// Saves the opening of a song's AAC copy, to read how its file is laid out (needs the network).
tasks.register<JavaExec>("aacLayoutCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.aether.sources.youtube.AacLayoutCheckKt")
    args = listOf("ids", "out", "bytes").mapNotNull { name -> project.findProperty(name)?.let { "$name=$it" } }
}
