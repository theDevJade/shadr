plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":resourcepack"))
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi)
    testImplementation(kotlin("test"))
    testImplementation(libs.paper.api)
}

tasks.jar {
    archiveBaseName.set("shadr-paper")

    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }

    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { if (it.isDirectory) it else zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC",
        "module-info.class", "META-INF/versions/*/module-info.class",
    )
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.processResources, tasks.jar)
}

val bundled = mapOf(
    "font" to rootProject.file("assets/font"),
    "sounds" to rootProject.file("assets/shadr/sounds"),
    "pages" to rootProject.file("protocol/pages"),
    "components" to rootProject.file("protocol/components"),
    "effects" to rootProject.file("protocol/effects"),
    "shaders" to rootProject.file("shaders"),
)

val bundledIndex by tasks.registering {
    val output = layout.buildDirectory.file("generated/bundled-index/index.txt")
    inputs.files(bundled.values.map { fileTree(it) })
    outputs.file(output)
    doLast {
        val lines = bundled.entries.sortedBy { it.key }.flatMap { (name, dir) ->
            if (!dir.isDirectory) emptyList()
            else dir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .map { "$name/${it.relativeTo(dir).invariantSeparatorsPath}" }
                .sorted()
                .toList()
        }
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }

    from(bundledIndex) { into("bundled") }

    bundled.forEach { (name, dir) -> from(dir) { into("bundled/$name") } }
}
