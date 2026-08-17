plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":resourcepack"))
    compileOnly(libs.paper.api)
    // Never bundled, and never required at runtime. shadr resolves PlaceholderAPI through a
    // class that is only loaded once the plugin is confirmed present, so a server without it
    // sees no difference beyond `%some_expansion%` staying on screen as written.
    compileOnly(libs.placeholderapi)
    testImplementation(kotlin("test"))
    // The Paper API is compileOnly for the plugin itself, but a test that loads any class
    // touching it needs the types on the test classpath too.
    testImplementation(libs.paper.api)
}

/**
 * The plugin jar has to be self-contained, and until this existed it was not.
 *
 * A Bukkit plugin is loaded by a classloader that can see the server and its own jar, and
 * nothing else. `implementation(project(":core"))` puts core on the *compile* classpath and
 * changes nothing about what ships, so the jar contained 25 adapter classes and none of the
 * engine, none of the Kotlin stdlib, and none of snakeyaml. Paper's answer to that is
 * `NoClassDefFoundError: kotlin/NoWhenBranchMatchedException` before `onEnable` is ever
 * reached, which is to say the plugin could not load on any server, ever.
 *
 * `paper-api` is `compileOnly`, so it is not in `runtimeClasspath` and does not get bundled,
 * which is exactly right, since the server supplies it.
 *
 * Not relocated. Relocating the Kotlin stdlib breaks its metadata and its reflection, and
 * every Kotlin plugin in the ecosystem bundles it plainly for that reason. The cost is real
 * and worth stating: Bukkit's plugin classloaders can see each other, so two plugins shipping
 * incompatible Kotlin versions can collide. Nothing here can prevent that, and it is the reason
 * `core` carries no network or crypto dependency and hand-rolls its WebSocket.
 */
tasks.jar {
    // `shadr-paper-<version>.jar` rather than Gradle's default `platform-paper-…`. This is a
    // published contract rather than cosmetics: the updater picks the plugin out of a release
    // that also carries the integration jars and the pack zip, and it does so by matching this
    // name (UpdateChecker.DEFAULT_ASSET_PATTERN). Rename either half alone and every server
    // is told an update exists and then told the release has no plugin jar in it.
    archiveBaseName.set("shadr-paper")

    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { if (it.isDirectory) it else zipTree(it) }
    })
    // Dependencies carry overlapping metadata: several ship a `META-INF/MANIFEST.MF` and
    // Kotlin's modules each ship a `.kotlin_module`. First one wins; the plugin's own comes
    // first because its output is added before the classpath.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        // A jar assembled from signed jars fails verification, because the signatures describe
        // the files as they were in the *original* jar. The symptom is a SecurityException at
        // load with nothing naming the cause.
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC",
        // A stray module-info from a dependency confuses nothing at runtime but breaks some
        // tooling that reads the jar as modular when it is not.
        "module-info.class", "META-INF/versions/*/module-info.class",
    )
}

tasks.test {
    useJUnitPlatform()
    // Both of these read build *output* rather than restating build configuration, which is
    // the only version of either check worth having: BundledAssetsTest guards the build and
    // the plugin disagreeing about what is seeded, and PluginJarTest guards the jar not
    // containing the code it needs, which every classpath-based check in the project passed
    // while being false.
    dependsOn(tasks.processResources, tasks.jar)
}

/**
 * What gets bundled into the jar, and where it lands in the data folder on first run.
 *
 * One list, consumed twice: `processResources` copies from it, and [bundledIndex] writes an
 * index of it. The plugin then reads the index rather than a hard-coded list of filenames,
 * because a jar is not a directory and there is no portable way to enumerate a resource folder from
 * inside one, and the alternative was a Kotlin list that had to be edited every time someone
 * added a page.
 */
val bundled = mapOf(
    "font" to rootProject.file("assets/font"),
    "sounds" to rootProject.file("assets/shadr/sounds"),
    "pages" to rootProject.file("protocol/pages"),
    "components" to rootProject.file("protocol/components"),
    "effects" to rootProject.file("protocol/effects"),
    // The whole `shaders/` tree, not just `items/`. `PackGenerator` takes this directory as
    // its `shaderSrc` and needs `overlays/<version>/`, which are Mojang's core shaders with
    // shadr's changes spliced in, and there is no way to synthesise them at runtime. Without
    // them a dropped-in jar throws `missing shader sources for 26.2+` inside onEnable and the
    // plugin disables itself, which is where this was found.
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
    // token-expand plugin.yml version
    filesMatching("plugin.yml") { expand("version" to project.version) }

    // Into `bundled/` explicitly: `from(a-task)` contributes the task's output *files*, so
    // without this the index lands at the resource root and getResource(BUNDLED_INDEX)
    // looks for it one directory down and finds nothing.
    from(bundledIndex) { into("bundled") }

    // The fonts and UI sounds the pack is built from, plus the starter library: pages,
    // components, effects and the demo shaders. They live at the repo root because the
    // Minestom harness and the plugin build the same pack from the same source; bundling them
    // is what makes a dropped-in jar produce a *complete* pack and have something to open,
    // rather than a working renderer with no typeface, silent buttons and an empty pages
    // directory. See ShadrPlugin.unpackBundledAssets.
    bundled.forEach { (name, dir) -> from(dir) { into("bundled/$name") } }
}
