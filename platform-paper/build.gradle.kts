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

sourceSets.named("main") {
    java.srcDir(rootProject.file("platform-paper-nms/api/src/main/java"))
}

val nmsBundle: File = rootProject.file("platform-paper-nms/build/libs/shadr-paper-nms-$version.jar")

tasks.jar {
    archiveBaseName.set("shadr-paper")

    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }

    inputs.file(nmsBundle).optional(true).withPathSensitivity(PathSensitivity.NAME_ONLY)
    from(provider { if (nmsBundle.isFile) zipTree(nmsBundle) else emptyList<Any>() })

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

val editorWeb: File = providers.gradleProperty("shadrEditorWeb")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("editor/build/web"))

val bundled = buildMap {
    put("font", rootProject.file("assets/font"))
    put("sounds", rootProject.file("assets/shadr/sounds"))
    put("pages", rootProject.file("protocol/pages"))
    put("components", rootProject.file("protocol/components"))
    put("effects", rootProject.file("protocol/effects"))
    put("shaders", rootProject.file("shaders"))
    if (editorWeb.isDirectory) put("editor-web", editorWeb)
}

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
