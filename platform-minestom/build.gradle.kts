plugins {
    id("com.diffplug.spotless") version "8.9.0"
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Targets Minecraft 26.2, the same version shadr's default pack overlay is written for.
    api("net.minestom:minestom:2026.08.07-26.2")

    // shadr's core, as a jar rather than a project dependency: this build is isolated from the
    // root one on purpose (see settings.gradle.kts), so it consumes the artifact the root build
    // produces. Run `./gradlew :core:jar` in the repo root first.
    api(files("../core/build/libs/core-0.1.0-SNAPSHOT.jar"))

    // A file dependency carries no metadata, so core's own dependencies have to be repeated
    // here by hand. Keep these in step with gradle/libs.versions.toml; a mismatch shows up as
    // a NoClassDefFoundError at runtime rather than at compile time, because the classes are
    // only reached when a @Serializable type is first initialised.
    api("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.yaml:snakeyaml:2.2")
    api("net.kyori:adventure-text-minimessage:4.24.0")
}

// Java rather than Kotlin, for the same reason this build is separate: Minestom's class files
// are major version 69, which the Kotlin version the rest of the project is pinned to cannot
// read. javac has no such trouble.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

/**
 * Spotless, sharing the root build's licence banner.
 *
 * A relative path into the parent directory rather than a copy: this is a separate Gradle build
 * (see settings.gradle.kts) and cannot reference `rootProject` over there, but a second copy of
 * the header would drift out of step with the first the day anyone edits it.
 */
spotless {
    java {
        target("src/**/*.java")
        licenseHeaderFile(file("../gradle/license-header.txt"))
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
