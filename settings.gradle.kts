rootProject.name = "shadr"

include(
    "core",
    "platform-paper",
    "resourcepack",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
    }
}
