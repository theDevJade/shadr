# platform-minestom

shadr's Minestom adapter: a full `PlatformBridge` implementation.

Everything that puts a HUD on a screen lives here: display entities, the camera seat, look
sampling, world shader displays, and pack delivery. `testserver/` is a thin demo over this module.

## Why this is its own Gradle build

Minestom ships Java 25 class files (major version 69). The root build targets Java 21 for the
Paper API, and the Kotlin version pinned there cannot read class files that new, so a module
depending on Minestom does not compile inside the root build.

Consequences worth knowing:

- It is Java, not Kotlin, for the same reason. `javac` reads version 69 fine.
- It consumes `core` as a jar, so `./gradlew :core:jar` has to run in the repo root first.
- A file dependency carries no metadata, so core's own dependencies are repeated in
  `build.gradle.kts`. A mismatch surfaces as a `NoClassDefFoundError` at runtime, not a compile
  error, because those classes are only touched when a `@Serializable` type initialises.

## Using it

```java
final MinestomBridge bridge = new MinestomBridge(worldName -> instance);
bridge.install();

bridge.input().onSample(sample -> {
});

bridge.cameraControl().start(playerId, () -> {
    bridge.inputSource().resetMapper(playerId);
    bridge.hud().mount(playerId);
});
```

`install()` registers the events and the per-tick pump, and has to run after
`MinecraftServer.init()`. `onSample` fires once per seated player per tick; drive a `UiSession`
from it and hand the result to `bridge.hud().apply(...)`. The `cameraControl().start` callback
must be chained, because `setInstance` is asynchronous.

`testserver/src/main/java/dev/shadr/testserver/Server.java` is the worked example.

## Two ordering constraints

Both fail silently when you get them wrong.

1. `setInstance` before metadata. An entity with no instance has no viewers, so a metadata write
   has nobody to flush to, and reconciliation skips unchanged draws — so whatever the spawn
   packet misses stays missing.
2. Interpolation fields before the transform. A display applies its transformation against them.
   Set a scale without them and you get an entity that is present, textured, and 1×1.

## Not done yet

Minestom has raw packet control, so the endgame is synthetic entity ids and hand-written
`SET_ENTITY_DATA` with no server-side entity and therefore no entity budget. This adapter uses
real entities, like the Paper one. The packet path is a change behind `HudSink`, not a different
architecture.
