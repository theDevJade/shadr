plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("dev.shadr.pack.CliKt")
}
