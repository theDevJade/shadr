# Roadmap

What is not built, and what would be involved in building it. This is not a schedule.

For what *is* built but fragile, read [`../SURVEY.md`](../SURVEY.md) instead.

## Bedrock and Geyser

Not started. The only trace of it in the codebase is a doc comment on
`PackLayout.PackOverlay.forVersion`, which notes that the server needs to know the client's
overlay for logging and for the Bedrock path. No `bedrock` or `geyser` identifier exists
anywhere in the source.

The problem is not adaptation, it is that the whole approach does not transfer. shadr renders by
overriding a core vertex shader, and Bedrock's resource pack format has no equivalent hook.
Geyser clients would need either a genuinely different renderer or a translation layer that
turns pages into Bedrock forms, which would drop the pixel positioning that is the reason the
project exists.

## Public API

There is no published API surface. `core/spi/PlatformBridge.kt` defines the platform contract
(`PlatformBridge`, `HudSink`, `CameraControl`, `InputSource`, `ResourcePackService`,
`PlayerRegistry`, `WorldDisplays`) but that is an inward-facing SPI for adapters, not something
a downstream plugin should bind to. `ShadrPlugin.shaderApi` exposes `ShaderApi`, reachable only
by casting the plugin instance.

The two builds under `integrations/` are the closest thing to consumers, and they compile
against jar paths rather than a published artifact, which is exactly the arrangement a real API
would replace. Nothing is published to Maven at all today; distribution is GitHub Releases plus
the in-plugin updater.

Before any of this can be called an API it needs a module that is versioned separately from the
implementation, and a decision about what `core` guarantees across versions.

## Version coverage for world and item shaders

`type: shader`, `block_sdf`, and every `EnvironmentEffect` (sky, clouds, celestials, frosted
glass) work on 26.2 only.

The reason is structural. Each override is a verbatim copy of that Minecraft version's vanilla
program with shadr's changes spliced in, and a copy from the wrong version does not compile
against the client's uniforms. Only `mc_26_2` has `core/item.fsh`. On the five older overlays
these features draw nothing.

`PackGenerator` already reports this rather than failing silently: `reportEnvironmentGap` and
`reportItemFragmentGap` exist for that purpose, and `PackGenerator.Gap` is the type they return.

Filling the gap is per-version manual work: take the vanilla program for that version, splice,
and verify on a real client. There is no way to generate it.

## Element types that are accepted but inert

Three things parse today and do nothing:

- **`type: image`** has no branch in `PageRenderer.renderElement`, so it falls through to the
  block path. The pieces behind it exist. `UiImageAtlas` allocates 256px tiles at codepoints
  `E100` through `EF6F` and persists the assignment, and `Glyphs.FONT_IMAGES` names the font.
  What is missing is the renderer branch that maps an element to a tile.
- **`playerHeadText`** is on `Element` and never read. `PlayerHeadFont` generates the eight
  `head_N` fonts and fetches skins off-thread, but no platform ever constructs it.
- **`hoverText`** and **`pivot.x` / `pivot.y`** are parsed into the element and never consumed.

Each is small on its own. They are listed together because they share a failure mode: an author
writes the key, sees no error, and gets no effect.

## Editor persistence gaps

The editor can display far more than it can save. `PageWriter.diff` compares a hand-written list
of fields, and anything not in that list is silently non-persistable: alignment, `lineWidth`,
`unicode`, item data, actions, hover and click effects, mirroring, and per-corner rounding.

This is the single largest gap between what the editor looks like it does and what it does.

## Minestom adapter

`platform-minestom/README.md` records what is deliberately unfinished there, chiefly the use of
synthetic entity ids with hand-written `SET_ENTITY_DATA` packets and no server-side entity
backing them.

## Test coverage

The cursor pipeline, the animation math, the action runner, the update installer, and every
Paper runtime class have no tests. `SURVEY.md` has the full mapping. The cursor and animation
classes are pure functions over numbers and are the cheapest of these to fix.
