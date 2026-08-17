// Standalone, and deliberately NOT in the root build's `include(...)`.
//
// Minestom is compiled to Java 25 class files (major version 69). The root build targets
// Java 21 for the Paper API, and its Kotlin 2.0.21 cannot read class files that new, so a
// module that depends on Minestom cannot live in the same build until both toolchains move.
// Keeping it separate is what lets this be a real adapter rather than a scaffold; the
// alternative was forcing a toolchain bump on Paper, which supports neither.
//
// It consumes `core` as a jar, the same way `testserver/` did before this module existed.
rootProject.name = "shadr-minestom"
