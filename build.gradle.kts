plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
}

val licenseHeader = rootProject.file("gradle/license-header.txt")

apply(plugin = "com.diffplug.spotless")
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    format("glsl") {
        target("shaders/**/*.glsl")
        licenseHeaderFile(licenseHeader, "(#define|#moj_import|//)")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val shadrVersion: String = providers.gradleProperty("shadrVersion").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = "dev.shadr"
    version = shadrVersion

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            licenseHeaderFile(licenseHeader)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.matching { it.name == "check" }.configureEach { dependsOn("spotlessCheck") }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21) // MC 1.21.8..26.2 all run on Java 21
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // PlaceholderAPI, for the Paper adapter. compileOnly; see platform-paper.
        maven("https://repo.extendedclip.com/releases/")
    }
}
