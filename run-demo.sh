#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ " $* " == *" --editor "* ]]; then
    echo "==> editor (flutter build web)"
    ./scripts/sync-editor-fonts.sh
    (cd editor && flutter build web --release)
fi

if [[ " $* " == *" --pack "* ]]; then
    echo "==> resource pack"
    ./gradlew :resourcepack:run \
        --args="$PWD/shaders $PWD/out/pack $PWD/assets/font $PWD/assets/shadr/sounds"
fi

if [[ ! -d out/pack ]]; then
    echo "no pack at out/pack, building one" >&2
    ./gradlew :resourcepack:run \
        --args="$PWD/shaders $PWD/out/pack $PWD/assets/font $PWD/assets/shadr/sounds"
fi

echo "==> core + resourcepack (jars, consumed by the Minestom builds)"
./gradlew :core:jar :resourcepack:jar

echo "==> platform-minestom (the SPI adapter)"
(cd platform-minestom && ./gradlew jar)

echo "==> testserver"
exec ./testserver/gradlew -p testserver run
