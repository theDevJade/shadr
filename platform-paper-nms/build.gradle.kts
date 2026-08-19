plugins {
    java
    id("com.diffplug.spotless") version "8.9.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

data class Backend(val devBundle: String, val java: Int)

val backends = mapOf(
    "v1_21_8" to Backend("1.21.8-R0.1-SNAPSHOT", 21),
    "v1_21_11" to Backend("1.21.11-R0.1-SNAPSHOT", 21),
    "v26_1" to Backend("26.1.1.build.16-alpha", 25),
    "v26_2" to Backend("26.2.build.112-stable", 25),
)

val paperApi = "io.papermc.paper:paper-api:26.2.build.112-stable"
val sharedPackage = "dev.shadr.paper.nms.impl"

allprojects {
    group = "dev.shadr"
    version = providers.gradleProperty("shadrVersion").getOrElse("0.1.0-SNAPSHOT")
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(backends[name]?.java ?: 21))
    }
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            licenseHeaderFile(rootProject.file("../gradle/license-header.txt"))
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

spotless {
    java {
        target("common/src/**/*.java")
        licenseHeaderFile(rootProject.file("../gradle/license-header.txt"))
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

project(":api") {
    dependencies { add("compileOnly", paperApi) }
}

configure(subprojects.filter { it.name != "api" }) {
    apply(plugin = "io.papermc.paperweight.userdev")

    val module = name
    val shared = rootProject.file("common/src/main/java/${sharedPackage.replace('.', '/')}")
    val generated = layout.buildDirectory.dir("generated/backend")

    val generateBackend by tasks.registering(Sync::class) {
        from(shared) {
            filter { line -> line.replace("package $sharedPackage;", "package dev.shadr.paper.nms.$module;") }
        }
        into(generated.map { it.dir("dev/shadr/paper/nms/$module") })
        eachFile {
            val override = file("src/main/java/dev/shadr/paper/nms/$module/$name")
            if (override.isFile) exclude()
        }
    }

    dependencies {
        add("compileOnly", project(":api"))
        add("paperweightDevelopmentBundle", "io.papermc.paper:dev-bundle:${backends.getValue(module).devBundle}")
    }

    extensions.configure<SourceSetContainer> {
        named("main") { java.srcDir(generated) }
    }

    tasks.named("compileJava") { dependsOn(generateBackend) }
}

tasks.named<Jar>("jar") { enabled = false }

val bundle by tasks.registering(Jar::class) {
    archiveBaseName.set("shadr-paper-nms")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    subprojects.forEach { sub ->
        from(sub.layout.buildDirectory.dir("classes/java/main"))
        dependsOn("${sub.path}:classes")
    }
}

tasks.named("assemble") { dependsOn(bundle) }
