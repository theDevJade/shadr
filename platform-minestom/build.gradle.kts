plugins {
    id("com.diffplug.spotless") version "8.9.0"
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api("net.minestom:minestom:2026.08.07-26.2")

    api(files("../core/build/libs/core-0.1.0-SNAPSHOT.jar"))

    api("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.yaml:snakeyaml:2.2")
    api("net.kyori:adventure-text-minimessage:4.24.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

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
