# Architecture

How a YAML file becomes pixels on an unmodified client, and which pieces have to agree with
each other for that to keep working.

## The hook

A resource pack may override Minecraft's core shaders. shadr's override adds one branch to the
entity and text vertex programs: if a vertex is below `y = -1000`, skip the projection matrix
entirely and write clip space directly from a fixed 1920x1080 design space.

`shaders/overlays/mc_26_2/include/hud.glsl`:

```glsl
#define refRes vec2(1920.0, 1080.0)
#define SHADR_FIELD_BAND 100000.0

bool is_hud(vec3 Position) {
    return (Position.y < -1000.0);
}

bool make_hud() {
    shadrMode = 0.0;
    if (is_hud(Position)) {
        float y = Position.y;
        shadrMode = 1.0;
        if (y < -SHADR_FIELD_BAND) {
            y += SHADR_FIELD_BAND;
            shadrMode = 2.0;
        }
        vec3 pos = vec3(Position.x, y, Position.z) + vec3(0.0, 15000.0, 0.0);
        pos.x *= -1.0;
        float offset = 0.0;
        if (y < -20000.0) {
            ...
        }
        pos.xy /= refRes * vec2(X, Y) / 2.0;
        pos.x += offset;
        pos.z = 0.95 - (pos.z / 100000000.0);
        gl_Position = vec4(pos, 1.0);
        return true;
    }
    return false;
}
```

The call site is `shaders/overlays/mc_26_2/core/entity.vsh`. Line 47 does the ordinary thing,
and line 76 overwrites it:

```glsl
gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
...
if (make_hud()) {
    vertexColor = Color;
    lightMapColor = vec4(1.0);
    overlayColor = vec4(1.0);
    sphericalVertexDistance = 0.0;
    cylindricalVertexDistance = 0.0;
}
```

The lighting and fog values are flattened in the same branch, because a HUD element that
picks up world lighting or fades into distance fog is not a HUD element.

`text.vsh` carries the same call under `#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)`, so
real GUI text is left alone.

So a display entity parked 15,000 blocks below the world renders on the HUD, at a position
controlled to the pixel, at any resolution. Nothing in a normal world is ever down there.

### The bands

The numeric thresholds in `make_hud()` are not arbitrary and are not private to the shader.
`HudPositionCalculator` places elements at `HUD_Y_BASE = -15000.178` plus an alignment offset
of 10,000 (left), 20,000 (center), or 30,000 (right), and the `-20000 / -30000 / -40000`
comparisons in the shader are how the shader reads that offset back. `pos.x *= -1.0` mirrors
the design space, which is why `PaperHudSink.HUD_FACING` rotates every display 180 degrees
about Y to compensate. `0.95` is `HudPositionCalculator.HUD_DEPTH_BASE`.

Two halves of one wire format, written in two languages. `HudPositionCalculatorTest` reads
`include/hud.glsl` out of every overlay directory and fails if the numbers drift apart. If you
change placement math, change the shader, and the reverse. The symptom of getting this wrong is
a UI that is mirrored or offset by a screen width, which is documented in
`testserver/README.md` as exactly that.

## YAML to draws

Everything in this section lives in `core/` and touches no platform types.

**`page.PageLoader`** parses with SnakeYAML under a `SafeConstructor` and
`isAllowDuplicateKeys = false`. It loads components, effects, and pages from three directories,
and it accumulates problems into an `issues` list rather than throwing, so a page with one bad
element still opens and the editor can show you what is wrong.

**`page.Expr`** is a recursive-descent evaluator over `+ - * / %`, parens, unary sign, and a
fixed set of variables (`screenWidth`, `screenHeight`, `halfWidth`, and friends, from
`Expr.screenVars`). It is arithmetic only. There are no comparisons, no functions, and no
`min`/`max`. Every failure returns `null` rather than `0`, which matters because a silent zero
would place an element at the top left corner and look like a layout bug instead of a parse
error.

**`page.TemplateResolver`** flattens the authored tree into a flat `List<Element>`. It expands
component invocations, `loop:` blocks, and `grid_block` containers, substitutes `${param}` and
`{{param}}`, and resolves children against their parent's origin and alignment. Three caps bound
it: depth 12, 2048 loop iterations, and 500 elements per page. The element cap is configurable
via `editor.max-page-elements` and reports itself as an issue when it truncates.

One consequence worth knowing before you author anything: this is a load-time flatten.
`TemplateResolver.DYNAMIC_FIELDS` holds exactly three entries (`size.width`, `size.height`,
`opacity`), and those are the only fields re-evaluated when a placeholder changes. Everything
else is frozen once the page is resolved.

**`hud.PageRenderer`** turns elements into `HudDraw`s and `HitRegion`s. Most types become one
or two draws. A rounded box becomes six: two inset fills (`__fill_h` and `__fill_v`) that cover
the cross shape, plus four corner glyphs. There is no rounded rectangle primitive available, so
one is assembled out of glyphs. Content is emitted as MiniMessage
(`<#rrggbb><font:shadr>...`), and every visual is a glyph in a generated bitmap font: fills,
corners, gradients, sliders, circles, and the cursor.

**`hud.HudPositionCalculator`** converts design-space coordinates into world position, scale,
and translation, including the alignment band described above and a depth derived from the
element's `layer`.

**`hud.HudDiff`** compares the previous frame's draws to the next and returns removed, spawned,
and updated sets. A draw is respawned rather than updated when its key is new, when its kind
changed, or when the platform reports the entity no longer exists. This is what keeps a page
update from being a full teardown.

**`session.UiSession`** is the per-player state machine every platform drives. It holds the
cursor position, the hovered and pressed element ids, and a dirty flag, applies hover and click
effects by re-rendering through `EffectDef.applyTo`, and appends the cursor's own draw
(`__shadr_cursor`) to the list.

## Input

A player seated in a camera entity cannot turn the world, which frees their look delta to be a
mouse delta.

`cursor.LookMapper` maps yaw delta to horizontal movement and absolute pitch to vertical
position, with sensitivity clamped to 0.1 through 4.0. `cursor.CursorPredictor` then runs two
`CursorKalmanFilter` instances (constant velocity, Joseph-form covariance update) and
extrapolates forward by the player's smoothed ping, capped at four ticks and at 10% of the
screen. `applyDirectionCutoff` refuses to extrapolate against the current input direction,
because overshooting backwards past a button the player is aiming at is worse than lagging
behind it.

Clicks arrive because `PaperCamera` spawns a large `Interaction` entity in front of the camera.
It is the only reason click packets exist at all, since there is nothing else in front of the
player to hit.

## The Paper adapter

`platform-paper/` implements `core/spi/PlatformBridge` and holds every Bukkit type in the
project. `ShadrPlugin.onEnable` reads config, builds the pack (pages can reference uploaded
images, so the atlas is folded in before the pack is generated), hosts it, loads pages, and
starts a one-tick timer.

`PaperHudSink` mounts one carrier `TextDisplay` per player and makes every draw a passenger of
it, invisible by default and shown only to its owner. It re-seats the mount every tick, because
vanilla re-sends the passenger list on unrelated events and Paper offers no hook to suppress
that.

`PaperCamera` seats the player on an invisible marker and spectates a second marker 1.5 blocks
above. When frosted glass is enabled the eye is an Enderman instead, because
`GameRenderer.checkEntityPostEffect` is what activates the `invert` post chain and it keys off
the spectated entity type.

`MiniMessageText` parses page-authored strings through a restricted resolver: colors, fonts,
decorations, gradients, and newlines. An unrestricted parse would let an authored page inject
click events and run commands. For the same reason `action.ActionRunner` refuses `console:`
unless the element carries an explicit `permission:`.

`platform-minestom/` is the same contract implemented against Minestom, in its own Gradle build.
See its README for why.

## The resource pack

`resourcepack/` is a standalone CLI (`dev.shadr.pack.CliKt`) that the plugin also calls in
process. `PackGenerator.build` writes metadata and icon, generates fonts, shapes, item shaders
and celestials, copies sounds, and writes the shader overlays.

One pack covers 1.21 through 26.2 via six overlay directories keyed to `pack_format` ranges
(`PackLayout.PackOverlay`). The client picks the overlay itself; the server only needs to know
which one for logging.

Nothing under `assets/font/` is a hand-authored texture. `FontAssets` draws all of it with
Java2D at build time: the 1x1 white fill, three rounded fills, gradient, slider, a 512px circle,
four radii of corner quarter-discs, eight cursors, and the negative-advance space that makes
overlapping glyphs possible. MSDF atlases come from `msdf/Msdf.kt`, which is derived from
msdfgen. `MSDF_SPREAD` in the generator must equal `SHADR_FIELD_RANGE` in `hud_fragment.glsl`,
and `MsdfTest` enforces that across every overlay.

Font provider order is load-bearing in a way that is easy to undo by accident. `FontManager`
reverses a font's provider list before handing it to `FontSet`, so later in the list means
higher priority. Nerd Fonts pack thousands of icons into U+E000 through U+F8FF, which is exactly
where shadr's shapes, corners and cursors live. Listing the bitmaps after the TTF is the only
reason a `block` draws a box rather than a Pomicon. `PackAssetsTest` pins it.

`PackArchive` zips deterministically: fixed timestamps, explicit CRC and size, entries sorted by
path. Two builds of an unchanged pack must hash identically or every player re-downloads on
every server restart. `PackHost` then serves the bytes from a two-thread JDK `HttpServer` at
`/pack`, with the SHA-1 as the ETag and the payload swapped atomically on reload.

## Module map

| Module | Contents | Build |
|---|---|---|
| `core/` | pages, expressions, placement, cursor, sessions, actions, animation, shaders, editor server, updater | root |
| `resourcepack/` | pack generation, font and atlas rasterisation, zip, HTTP host | root |
| `platform-paper/` | Paper adapter and the shipped plugin jar | root |
| `platform-minestom/` | Minestom adapter | own |
| `testserver/` | standalone Minestom server running the full pipeline | own |
| `integrations/skript`, `integrations/typewriter` | optional plugin integrations | own |
| `editor/` | Flutter web editor | Flutter |
| `shaders/overlays/` | per-version core shader sources | not compiled |
| `protocol/` | starter library: 6 pages, 13 components, 7 effects | data |

There are five Gradle builds, not one. The split is forced rather than chosen: Minestom ships
Java 25 class files, the root build's Kotlin 2.0.21 cannot read them, and the root build targets
Java 21 for the Paper API. `run-demo.sh` knows the order.

Read [`../SURVEY.md`](../SURVEY.md) for what is load-bearing, untested, and known-broken.
