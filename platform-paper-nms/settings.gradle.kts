pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "shadr-paper-nms"

include("api")
include("v1_21_8")
include("v1_21_11")
include("v26_1")
include("v26_2")
