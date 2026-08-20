#!/usr/bin/env pwsh
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$srcRel = 'assets/font'
$dstRel = 'editor/assets/fonts'
$src = Join-Path $root $srcRel
$dst = Join-Path $root $dstRel

# source name -> the name pubspec.yaml declares
$copies = [ordered]@{
    'nerd_mono.ttf'          = 'jetbrains_mono_nerd.ttf'
    'nerd_mono_semibold.ttf' = 'jetbrains_mono_nerd_semibold.ttf'
}

foreach ($name in $copies.Keys) {
    if (-not (Test-Path -LiteralPath (Join-Path $src $name) -PathType Leaf)) {
        throw "missing $srcRel/$name"
    }
}

if (-not (Test-Path -LiteralPath $dst -PathType Container)) {
    New-Item -ItemType Directory -Path $dst -Force | Out-Null
}

foreach ($name in $copies.Keys) {
    Copy-Item -LiteralPath (Join-Path $src $name) -Destination (Join-Path $dst $copies[$name]) -Force
}

Write-Host "synced $srcRel -> $dstRel"
