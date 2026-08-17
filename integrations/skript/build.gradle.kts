plugins {
    kotlin("jvm") version "2.0.21"
}

group = "dev.shadr.integrations"
version = "0.1.0-SNAPSHOT"

val shadrVersion = "0.1.0-SNAPSHOT"

dependencies {
    compileOnly(
        files(
            file("../../core/build/libs/core-$shadrVersion.jar"),
            file("../../platform-paper/build/libs/shadr-paper-$shadrVersion.jar"),
        ),
    )
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    compileOnly("com.github.SkriptLang:Skript:2.16.1") { isTransitive = false }
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveBaseName.set("shadr-skript")
}
