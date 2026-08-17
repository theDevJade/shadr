# Page format

A page is one YAML file. Pages live in `pages/`, reusable partials in `components/`, and named
hover and click effects in `effects/`. Inside the Paper plugin those are subdirectories of
`plugins/shadr/`; in this repo they are under `protocol/`.

Parsing is done by `PageLoader` and flattening by `TemplateResolver`, both in
`core/src/main/kotlin/dev/shadr/core/page/`. Neither throws on a bad page. Problems accumulate
into an issues list that the editor shows and the server logs, and the rest of the page still
opens.

Two things to know before anything else:

- **An unrecognised `type:` silently becomes `block`.** `ElementType.parse` falls back rather
  than reporting, so a typo draws a white rectangle instead of an error.
- **Pages are flattened at load.** Only `size.width`, `size.height`, and `opacity` are
  re-evaluated when a placeholder changes. Every other field is frozen once resolved.

## Page

```yaml
name: main_menu          # optional; defaults to the filename
screen:
  width: 1920
  height: 1080
blocks:
  - type: block_rounded
    id: card
    position: {x: 1920/2 - 260, y: 1080/2 - 170}
    size: {width: 520, height: 340}
animations: []
```

### `screen:`

| Key | Default | Meaning |
|---|---|---|
| `width` | `1920` | design space width; positions are in these units |
| `height` | `1080` | design space height |
| `offsetX`, `offsetY` | `0` | shifts the whole page |
| `hitboxOffsetX`, `hitboxOffsetY` | `0` | shifts every hit region without moving the visuals |
| `cursorSize` | `10` | |
| `cursorSpeed` | `1.0` | sensitivity multiplier, clamped 0.1 to 4.0 downstream |
| `cursorUnicode` | `` | which of the eight cursor glyphs to draw |
| `cursorLayer` | `9700` | the cursor is deliberately near the top of the stack |
| `preview.defaultZoom` | `0.8` | editor only |

The design space is fixed regardless of the player's resolution. That is the point: the shader
divides by `refRes` and the client's actual window size never enters the page.

## Elements

Every element is a map in `blocks:`. Positions are relative to the parent when nested.

### Types

| `type` | Draws |
|---|---|
| `block` | filled rectangle |
| `block_rounded` | rectangle with rounded corners (six draws: two inset fills plus four corner glyphs) |
| `circle` | ellipse, circular when width and height match |
| `gradient` | a fake gradient, one glyph |
| `blur` | approximated blur panel |
| `progress` | bar or slider track |
| `block_sdf` | distance-field rectangle, sharper edges at large scale |
| `text` | a TextDisplay |
| `item` | an ItemDisplay |
| `shader` | a custom fragment program drawn in place of an item |
| `image` | reserved; see below |
| `hitbox` | an invisible hit region |
| `component` | invokes a partial from `components/` |
| `grid_block` | lays its children out in a row or column |

`image` is parsed and accepted but has no branch in `PageRenderer`, so it currently renders as a
plain block. The atlas machinery behind it (`UiImageAtlas`, the `uiimages` font) exists and is
generated; nothing wires an element to it yet.

### Keys

Aliases are separated by `|`. Numeric fields accept expressions.

| Key | Default | Notes |
|---|---|---|
| `type` | `block` | |
| `id` | `el_<path>` | auto-generated from the tree position when absent |
| `position.x` \| `x` | `0` | added to the parent's origin |
| `position.y` \| `y` | `0` | |
| `size.width` \| `width` \| `scale.width` | `20` | a negative value sets `mirrorX` and then takes the absolute value; minimum 1 |
| `size.height` \| `height` \| `scale.height` | `20` | same, with `mirrorY` |
| `layer` \| `size.depth` \| `depth` | `0` | higher draws in front |
| `color` \| `style.color` | white | exactly six hex digits, with optional `#` or `0x` |
| `opacity` | `255` | clamped 0 to 255 |
| `unicode` | type's glyph | overrides the drawn glyph on non-text types |
| `text` | `""` | on `text`; on other types it is an alias for `unicode` |
| `font` \| `style.font` \| `text.font` | `shadr` | `shadr`, `shadr_semibold`, `shadr_sharp`, `shadr_sharp_semibold` |
| `align` \| `hudAlign` | inherited, else `center` | `left`, `right`, `center` |
| `textAlign` \| `text-align` | `center` | |
| `lineWidth` \| `line-width` \| `wrap` | `200` | wrap width for text |
| `enabled` | `true` | `false` drops the element before rendering |
| `outline.size` | none | an outline exists only when size is above 0 |
| `outline.color`, `outline.layer` | | |
| `rounding.size` | type default | `small`, `medium`, `regular`, `large`; `none` coerces to `regular` |
| `rounding.radius`, `rounding.unicode` | | override the generated corner |
| `rounding.topLeft.{unicode,x,y}` | | and `topRight`, `bottomRight`, `bottomLeft` |
| `rotationDeg` \| `rotation` \| `rotate` | `0` | |
| `mirrorX`, `mirrorY` | `false` | |
| `item` \| `itemDisplay` \| `itemDisplayBlock` | | for `type: item` |
| `shader` | | for `type: shader`, names a program in `shaders/items/` |
| `customModelData` \| `item.customModelData` | | |
| `interactive` | `true` | |
| `disableHitbox` \| `disable-hitbox` | `false` | |
| `hitboxOffsetX` \| `hitbox.x`, `hitboxOffsetY` \| `hitbox.y` | `0` | |
| `hoverEffect` \| `hover.effect` | | names a file in `effects/` |
| `clickEffect` \| `click.effect` | | |
| `onClickAction` \| `onClick` \| `actions` | | |
| `onLeftClickAction` \| `onLeftClick` | | |
| `onRightClickAction` \| `onRightClick` | | |
| `permission` | | required before `console:` will run |
| `children` | | nested elements, positioned relative to this one |

Rounding is only honoured on `block`, `block_rounded`, and `block_sdf`. `block_rounded` and
`block_sdf` are rounded without asking.

`pivot.x` \| `pivotOffsetX` and `pivot.y` \| `pivotOffsetY` are parsed into the element but
never read by the renderer. Same for `playerHeadText` and `hoverText` \| `hover_text`. They are
accepted and do nothing.

## Expressions

Any numeric field can be an arithmetic expression instead of a number:

```yaml
position: {x: 1920/2 - 260, y: halfHeight - 170}
size: {width: screenWidth * 0.25}
```

Operators are `+ - * / %`, with parentheses and unary sign. Variables come from
`Expr.screenVars`: `screenWidth`, `screenHeight`, `width`, `height`, `halfWidth`, `halfHeight`.
Loop bodies additionally get `loopIndex`, `loopNumber`, and `loop`.

There are no comparisons, no conditionals, and no functions, so there is nowhere to clamp a
value. `protocol/components/progress_bar.yml` documents the consequence: a fill fraction above 1
draws past the end of its own track and nothing stops it.

An expression that fails to parse yields `null`, not `0`, so the field keeps its default rather
than snapping to the origin. Failures include trailing operators, unbalanced parentheses,
unknown variables, division or modulo by zero, and any non-finite result.

## Actions

Nine verbs. Both a string form and a single-entry map form are accepted, and a single action may
be given instead of a list.

```yaml
onClickAction:
  - "sound: shadr.click"
  - "message: pressed"
  - "delay: 10"
  - "redirect: settings"
```

| Verb | Argument | Notes |
|---|---|---|
| `command:` | a command | run as the player; a leading `/` is stripped |
| `console:` | a command | run as console, **only** if the element carries `permission:` |
| `message:` | text | |
| `sound:` | `<key> [volume]` | |
| `delay:` | ticks | reschedules the remainder of the list; capped at 1200 ticks |
| `close:` | | closes the page |
| `open:` | page name | opens as a popup over the current page |
| `redirect:` | page name | opens replacing the current page |
| `teleport:` | `world x y z` or `x y z` | |

The `console:` gate is a security boundary rather than a convenience. Without it, anyone who can
author a page could hand themselves op. Unknown verbs are dropped silently.

## Placeholders

`%name%` anywhere in a string. Unresolved names are left on screen exactly as written, which is
the intended escape hatch rather than an oversight.

Built in: `%shadr_player%`, `%shadr_online%`, `%shadr_max_players%`, `%shadr_tps%`,
`%shadr_ping%`, `%shadr_world%`, `%shadr_time%`. PlaceholderAPI expansions resolve after those
when PAPI is installed.

Refresh interval is `editor.placeholders.text-refresh`, 20 ticks by default. Turning that down
to 1 re-sends every dynamic element twenty times a second and starves the cursor, so leave it
alone unless you have a reason.

## Components

A component file declares defaults and a body:

```yaml
# components/stat_chip.yml
params:
  label: "Label"
  value: "0"
  accent: "4cc9f0"
blocks:
  - type: block_rounded
    id: "${id}_bg"
    color: "${accent}"
```

Invoked from a page:

```yaml
- type: component
  component: stat_chip
  position: {x: 100, y: 100}
  params: {id: chip_players, label: "Players", value: "128"}
```

Substitution is `${name}` or `{{name}}`. A parameter that is the entire value keeps its type, so
`width: "${w}"` with `w: 200` stays an integer. A parameter embedded in a longer string is
stringified.

Parameters resolve in this order, later winning: the component's own `defaults`, then inherited
context parameters that the defaults also declare, then any inline key on the invocation that is
not `type`, `component`, `params`, `position`, `children`, or `enabled`, then the explicit
`params:` map.

Nesting is capped at depth 12. An unknown component name is recorded as an issue and skipped.

Thirteen components ship in `protocol/components/`: `badge`, `button`, `button_ghost`,
`divider`, `list_row`, `modal`, `panel`, `progress_bar`, `section_header`, `stat_chip`, `tab`,
`toast`, `tooltip`.

## Grids

```yaml
- type: grid_block
  direction: row      # anything other than "column" is a row
  gap: 16
  position: {x: 200, y: 400}
  children:
    - {type: block, size: {width: 80, height: 40}}
    - {type: component, component: badge}
```

The cursor advances by each child's produced extent, meaning the real bounding box of everything
that child emitted, plus `gap`. A component child therefore advances by its actual footprint
rather than by a nominal size.

## Loops

```yaml
- loop: 4
  blocks:
    - type: block
      id: "offer_${loopIndex}"
      position: {y: 1080/2 - 160 + loopIndex * 92}
```

`loop:` accepts three shapes:

- a number or expression, giving a count from 0
- a list, iterated as given
- a map `{from: 1, to: 5}` or `{from: 1, count: 5}`, inclusive

The body is `blocks:` or `block:`. If neither is present the node itself is repeated with `loop`
removed. Each iteration binds `${loop}`, `${loopIndex}` (0-based), and `${loopNumber}` (1-based)
as parameters, and the same names as expression variables. Capped at 2048 iterations.

## Effects

An effect file in `effects/` is flat:

```yaml
id: lift
move-y: -4
scale-x: "104%"
scale-y: "104%"
duration-ms: 250
interpolation: ease_out
```

Keys accept both kebab and camel case: `move-x` \| `moveX`, `move-y`, `scale-x`, `scale-y`,
`opacity-delta`, `rotation-deg`, `duration-ms` (default 250), `interpolation`. Scale values are
percentages; the `%` is stripped and the rest evaluated as an expression.

Seven ship: `glow`, `lift`, `nudge`, `pop`, `press`, `sink`, `tilt`.

## Animations

```yaml
animations:
  - name: slide_in
    durationTicks: 20
    steps:
      - target: card
        axis: y
        from: -200
        to: 0
        easing: ease_out
```

A step needs `target`. `axis` defaults to `y` and accepts `x`, `y`, `dx`, `dy`, `width`,
`height`, `layer`, `opacity`, `rotation`. Give either `from`/`to` or a `values:` keyframe list,
plus optional `delay` and `duration` in ticks.

`from`/`to` steps are handed to the client as native display interpolation. Keyframe lists and
anything animating `opacity` are stepped server side instead, because the client cannot
interpolate those.

## Caps

| Cap | Value | Configurable |
|---|---|---|
| elements per page | 500 | `editor.max-page-elements` |
| component nesting depth | 12 | no |
| loop iterations | 2048 | no |
| action delay | 1200 ticks | no |

Exceeding the element cap drops the overflow and records an issue naming the count.
