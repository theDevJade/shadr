plugins {
    kotlin("jvm") version "2.4.10"
    id("com.typewritermc.module-plugin") version "2.0.0"
}

group = "dev.shadr.integrations"
version = "0.1.0-SNAPSHOT"

val shadrVersion = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.typewritermc.com/releases")
    removeIf { it is MavenArtifactRepository && it.url.host == "maven.evokegames.gg" }
}

configurations.all {
    exclude(group = "me.tofaa.entitylib")
}

dependencies {
    compileOnly(
        files(
            file("../../core/build/libs/core-$shadrVersion.jar"),
            file("../../platform-paper/build/libs/shadr-paper-$shadrVersion.jar"),
        ),
    )
}

typewriter {
    namespace = "shadr"

    extension {
        name = "Shadr"
        shortDescription = "Drive shadr pages and world shaders from Typewriter."
        description = """
            Adds actions for opening and closing shadr pages, hanging shadr shaders in the
            world, despawning them again, and rebuilding the shadr resource pack.
        """.trimIndent()
        engineVersion = "0.9.0"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("shadr")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
