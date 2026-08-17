#!/usr/bin/env bash
#
# Copies the typeface into editor/assets/fonts/ under the names editor/pubspec.yaml declares.
#
# Flutter refuses asset paths outside the package directory, so the editor cannot point at
# assets/font/ directly. Committing a second copy is 4.8 MiB of duplicate binary in a 12 MB
# repo, so the copies are generated instead and gitignored. Run this before `flutter build web`
# or `flutter test`; run-demo.sh --editor and the CI editor job both call it.
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
