plugins {
    id("com.diffplug.spotless") version "8.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("../platform-minestom/build/libs/shadr-minestom.jar"))

    implementation("net.minestom:minestom:2026.08.07-26.2")
    implementation(files("../core/build/libs/core-0.1.0-SNAPSHOT.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("net.kyori:adventure-text-minimessage:4.24.0")

    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("dev.shadr.testserver.Server")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
    standardInput = System.`in`
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
