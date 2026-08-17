# shadr

[![build](https://img.shields.io/github/actions/workflow/status/theDevJade/shadr/ci.yml?branch=main)](https://github.com/theDevJade/shadr/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/theDevJade/shadr?include_prereleases&sort=semver)](https://github.com/theDevJade/shadr/releases)
[![licence](https://img.shields.io/badge/licence-Apache--2.0-lightgrey)](LICENSE)

shadr uses core shader magic, math, and logic to render UI to vanilla clients.

You write a page in YAML, or in the editor:

```yaml
- type: block_rounded
  id: card
  layer: 10.0
  color: 15151c
  position: {x: 1920/2 - 260, y: 1080/2 - 170}
  size: {width: 520, height: 340}
  rounding: {size: regular}
  hoverEffect: lift
  onClickAction:
    - "sound: shadr.click"
    - "message: pressed"
```

## How it works

Essentially, vertex reprojection below a threshold, along with SDF math. Shadr uses custom fonts, core shader overriding, and some real hacky stuff to make everything render.

## Running it

To run the demo, on MacOS, and Linux atleast use:
    ./run-demo.sh

## On a Paper server

Put the platform-paper jar in plugins/ and start the server.

    /shadr open <page>
    /shadr close
    /shadr reload
    /shadr pages
    /shadr pack
    /shadr update [check|install|cancel]

## Important Notes

Run `./gradlew spotlessApply` for everything, it will throw otherwise.

## AI Notice

Some of this codebase was generated and edited with local AI, mainly the tests. Local AI was also used for debugging and to check my math.

## Licence

Apache 2.0 - see [LICENSE](LICENSE).

There are core shaders under `shaders/overlays/` that
carry vanilla names are Mojang's, referenced from the client with shadr's hook spliced in. Shadr is not an official Minecraft product and is not approved by or associated with Mojang Studios.