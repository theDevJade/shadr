# shadr editor

A browser editor for shadr pages, written in Flutter for web. It talks to a running server over
a WebSocket and edits the YAML on disk, so what you drag is what gets committed.

It is off by default. The server hosts it only when `editor.web.enabled` is set.

## Build

```bash
../scripts/sync-editor-fonts.sh     # copies the typeface in; see below
flutter build web --release
```

Or from the repo root, which does both plus the server:

```bash
./run-demo.sh --editor
```

The output lands in `editor/build/web/`. Point `editor.web.ui-dir` at it, or copy it to
`plugins/shadr/editor-web/`, which is the default location.

If the server finds no build at that path it serves a short instructions page, not a 404,
so a blank-looking editor usually means the Flutter build is missing and not that something
crashed.

### The font step

`pubspec.yaml` declares `assets/fonts/jetbrains_mono_nerd.ttf` and its semibold, but those files
are not committed. They are byte-identical to `assets/font/nerd_mono.ttf` at the repo root, and
two 2.4 MB copies of the same typeface in one repository is worth avoiding. Flutter refuses asset
paths outside the package directory, so the copy is generated.
`scripts/sync-editor-fonts.sh` does it, `run-demo.sh --editor` calls it, and CI calls it before
`flutter test`.

## Enabling it on a server

In `plugins/shadr/config.yml`:

```yaml
editor:
  web:
    enabled: true
    port: 8124
    bind: '127.0.0.1'
```

Then `/shadr editor link` prints a URL carrying a freshly minted token. The link expires after
30 minutes.

| Key | Default | Meaning |
|---|---|---|
| `editor.web.enabled` | `false` | |
| `editor.web.port` | `8124` | HTTP and the WebSocket share one port |
| `editor.web.bind` | `127.0.0.1` | |
| `editor.web.token` | `''` | blank generates one and writes it to `editor-token.txt` |
| `editor.web.allow-insecure` | `false` | honoured on loopback only |
| `editor.web.public-host` | `''` | hostname used in generated links |
| `editor.web.ui-dir` | `editor-web` | the `flutter build web` output |
| `editor.web.tls-keystore` | `''` | PKCS12, or JKS by extension. Relative to the data folder |
| `editor.web.tls-password` | `''` | |
| `editor.web.tls-key-password` | `''` | |

Sibling keys that affect editing, not hosting: `editor.autosave.enabled`,
`editor.autosave.interval-seconds`, `editor.history.undo-limit`, `editor.max-page-elements`,
`editor.placeholders.text-refresh`, `editor.sounds.enabled`, `editor.sounds.volume`.

## Binding off loopback

`EditorServer.init` refuses to start with open auth on a non-loopback bind. That is a hard
requirement, not a warning, and it is the only thing standing between a convenience feature and
an unauthenticated file writer on a public port.

For a non-loopback bind you want TLS. `config.yml` carries the two commands for producing a
keystore, one via `openssl pkcs12 -export` and one via `keytool -genkeypair`. A keystore that is
configured but fails to load stops the editor, and it will not fall back to plain HTTP, because an
`https` link that silently is not is worse than no link.

Tokens are compared with `MessageDigest.isEqual` and must be at least 16 characters. Generated
ones are 24 random bytes in URL-safe base64. The server accepts a token from four places, in
order: the `shadr_editor_token` cookie, a `Sec-WebSocket-Protocol` entry prefixed
`shadr.token.`, an `Authorization: Bearer` header, and a `?token=` query parameter. On success it
sets a session cookie with `HttpOnly` and `SameSite=Strict`, plus `Secure` under TLS.

`/shadr editor link` mints a short-lived token per invocation. Echoing the configured one would
leave a permanent credential in the server log and in every chat-logging plugin.
`/shadr editor revoke` drops the minted ones.

## Layout

| File | Role |
|---|---|
| `main.dart` | app shell, mode rail, toolbar, document picker, status bar |
| `model.dart` | `EditorModel`, transport, workspace state |
| `protocol.dart` | wire types, mirrors `core/editor/EditorProtocol.kt` |
| `canvas.dart` | the page canvas and its painter |
| `properties.dart` | the inspector |
| `layers.dart` | the element tree |
| `timeline.dart` | animation scrubber and track lanes |
| `shaders.dart` | shader list, editor, diagnostics, preview |
| `webgl.dart` | WebGL2 preview surface for shader compilation |
| `glsl_syntax.dart` | GLSL highlighting |
| `fields.dart` | value, scrub, color and choice inputs |
| `chrome.dart` | panels, sections, splitters |
| `actions.dart` | intents and the keyboard shortcut map |
| `snapping.dart`, `viewport.dart` | canvas guides and pan/zoom |
| `theme.dart` | design tokens |

## Protocol

`protocolVersion = 1`, JSON with a `t` discriminator, defined once in
`core/src/main/kotlin/dev/shadr/core/editor/EditorProtocol.kt` and mirrored in `protocol.dart`.

Server to client: `welcome`, `snapshot`, `error`, `saved`, `shaders`, `shaderSource`,
`shaderSaved`, `programSource`.

Client to server: `open`, `patch`, `patchAll`, `scrub`, `setStep`, `removeStep`, `undo`, `redo`,
`add`, `delete`, `reload`, `save`, plus the shader set (`openShader`, `saveShader`, `newShader`,
`renameShader`, `duplicateShader`, `deleteShader`, `setEnvironment`, `openProgram`,
`saveProgram`, `revertProgram`).

The WebSocket server is hand-rolled in `core/editor/WebSocketServer.kt` with no external
dependency. That is deliberate: the plugin jar bundles its dependencies unrelocated into a shared
Bukkit classloader space, so every library `core` takes on is a library that can collide with
another plugin's copy.

## Known limits

The editor displays considerably more than it can write. `PageWriter.diff` compares a fixed list
of fields, and anything outside it will appear to change and then not persist: alignment,
`lineWidth`, `unicode`, item data, actions, hover and click effects, mirroring, and per-corner
rounding. This is the largest gap in the editor.

Saving also notices when a numeric write would clobber an authored expression. `SaveResult`
reports that as `expressionsReplaced`, so a `position: {x: 1920/2 - 260}` that you drag by hand
tells you it is about to become a number.
