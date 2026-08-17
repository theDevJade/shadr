pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.typewritermc.com/releases")
    }
}

rootProject.name = "shadr-typewriter"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.typewritermc.com/releases")
    }
}
