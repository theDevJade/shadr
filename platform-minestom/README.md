# platform-minestom

shadr's **Minestom** adapter: a complete `PlatformBridge`, not a scaffold.

Everything that puts a HUD on a screen lives here: display entities, the camera seat, look
sampling, world shader displays, and pack delivery. It was extracted from `testserver/`, which is
where each part was proven against a live client and which is now a thin demo over this module.

## Why this is its own Gradle build

Minestom ships **Java 25** class files (major version 69). The root build targets Java 21 for
the Paper API, and the Kotlin version pinned there cannot read class files that new, so a
module depending on Minestom genuinely cannot compile inside the root build. Listing it as a
subproject is what kept it a stub; giving it its own build is what let it become real.

Consequences worth knowing:

- It is **Java, not Kotlin**, for the same reason. `javac` reads version 69 fine.
- It consumes `core` as a **jar**, so `./gradlew :core:jar` has to run in the repo root first.
- Because a file dependency carries no metadata, core's own dependencies are repeated in
  `build.gradle.kts`. A mismatch surfaces as a `NoClassDefFoundError` at runtime rather than a
  compile error, because those classes are only touched when a `@Serializable` type initialises.

## Using it

```java
final MinestomBridge bridge = new MinestomBridge(worldName -> instance);
bridge.install();                      // events + the per-tick pump; call after MinecraftServer.init()

bridge.input().onSample(sample -> {    // one per seated player per tick
    // drive a UiSession, then bridge.hud().apply(...)
});

bridge.cameraControl().start(playerId, () -> {
    bridge.inputSource().resetMapper(playerId);
    bridge.hud().mount(playerId);      // must be chained: setInstance is asynchronous
});
```

`testserver/src/main/java/dev/shadr/testserver/Server.java` is the worked example.

## The two ordering constraints that are not style

Both were real failures, and both are silent:

1. **`setInstance` before metadata.** An entity with no instance has no viewers, so a metadata
   write has nobody to flush to. While every frame re-applied every draw this self-corrected;
   once reconciliation started skipping unchanged draws, whatever the spawn packet missed
   stayed missing forever.
2. **Interpolation fields before the transform.** A display applies its transformation against
   them. Set a scale without them and you get an entity that is present, textured, and 1×1.

## What is deliberately not done yet

Minestom has raw packet control, so the endgame is synthetic entity ids and hand-written
`SET_ENTITY_DATA` with no server-side entity at all, and so no entity budget. This adapter uses real
entities, like the Paper one, because that is the version that has actually been run. The
packet path is a change behind `HudSink`, not a different architecture.
