plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.snakeyaml)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

configurations.all {
    exclude(group = "io.papermc.paper")
    exclude(group = "net.minestom")
}
