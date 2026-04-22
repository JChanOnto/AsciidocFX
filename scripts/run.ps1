# scripts/run.ps1
#
# Run AsciidocFX from source via the `local-run` Maven profile.
# Ensures the JavaFX SDK is present and that its native bin\ DLLs are on
# PATH for the JavaFX QuantumRenderer.
#
# Usage:
#   .\scripts\run.ps1                    # ensure setup, then mvn local-run
#   .\scripts\run.ps1 -SkipSetup         # don't call setup.ps1 first

[CmdletBinding()]
param(
    [switch]$SkipSetup
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$jfxBin   = Join-Path $repoRoot 'jmods\windows-jars\bin'

if (-not $SkipSetup) {
    & (Join-Path $PSScriptRoot 'setup.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not (Test-Path $jfxBin)) {
    Write-Error "JavaFX SDK bin\ not found at $jfxBin. Run scripts\setup.ps1 first."
    exit 1
}

# Prepend JavaFX native DLL dir so QuantumRenderer can load.
$env:Path = "$jfxBin;$env:Path"

Push-Location $repoRoot
try {
    Write-Host "[run] mvn -P local-run spring-boot:run"
    & mvn -P local-run spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
