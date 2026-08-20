#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [switch]$Editor,
    [switch]$Pack
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
Set-Location -LiteralPath $root

# `-or` short-circuits, so $IsWindows (Core-only) is never touched on 5.1.
$onWindows = ($PSVersionTable.PSVersion.Major -lt 6) -or $IsWindows

function Get-Gradlew {
    param([string]$Directory = $root)
    Join-Path $Directory $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
}

# Native commands don't honour $ErrorActionPreference, so check $LASTEXITCODE by hand.
function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory
    )

    $pushed = $false
    if ($WorkingDirectory) {
        Push-Location -LiteralPath $WorkingDirectory
        $pushed = $true
    }
    try {
        & $FilePath @ArgumentList
        if ($LASTEXITCODE -ne 0) {
            throw "'$FilePath $($ArgumentList -join ' ')' exited with code $LASTEXITCODE"
        }
    }
    finally {
        if ($pushed) { Pop-Location }
    }
}

function Build-ResourcePack {
    $paths = @('shaders', 'out/pack', 'assets/font', 'assets/shadr/sounds') |
        ForEach-Object { Join-Path $root $_ }

    Invoke-Checked (Get-Gradlew) @(':resourcepack:run', "--args=$($paths -join ' ')")
}

if ($Editor) {
    Write-Host '==> editor (flutter build web)'
    & (Join-Path $root 'scripts/sync-editor-fonts.ps1')
    Invoke-Checked 'flutter' @('build', 'web', '--release') -WorkingDirectory (Join-Path $root 'editor')
}

if ($Pack) {
    Write-Host '==> resource pack'
    Build-ResourcePack
}

if (-not (Test-Path -LiteralPath (Join-Path $root 'out/pack') -PathType Container)) {
    Write-Warning 'no pack at out/pack, building one'
    Build-ResourcePack
}

Write-Host '==> core + resourcepack (jars, consumed by the Minestom builds)'
Invoke-Checked (Get-Gradlew) @(':core:jar', ':resourcepack:jar')

Write-Host '==> platform-minestom (the SPI adapter)'
$minestom = Join-Path $root 'platform-minestom'
Invoke-Checked (Get-Gradlew $minestom) @('jar') -WorkingDirectory $minestom

Write-Host '==> testserver'
& (Get-Gradlew (Join-Path $root 'testserver')) -p testserver run
exit $LASTEXITCODE