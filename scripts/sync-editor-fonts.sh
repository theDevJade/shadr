#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

src=assets/font
dst=editor/assets/fonts

for f in nerd_mono.ttf nerd_mono_semibold.ttf; do
    test -f "$src/$f" || { echo "missing $src/$f" >&2; exit 1; }
done

mkdir -p "$dst"
cp "$src/nerd_mono.ttf" "$dst/jetbrains_mono_nerd.ttf"
cp "$src/nerd_mono_semibold.ttf" "$dst/jetbrains_mono_nerd_semibold.ttf"

echo "synced $src -> $dst"
