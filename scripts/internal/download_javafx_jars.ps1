# scripts/internal/download_javafx_jars.ps1
#
# Downloads the JavaFX 25 SDK for Windows into <repo>/jmods/windows-jars/.
# Required by the `local-run` Maven profile on Windows.
#
# The SDK keeps its native lib/+bin/ layout because Windows needs the
# bin/ DLLs on PATH for the JavaFX QuantumRenderer to start.
#
# Prefer invoking via `scripts\setup.ps1` rather than running this directly.
#
$ErrorActionPreference = 'Stop'
$Version = '25'
# This script lives at <repo>/scripts/internal/; repo root is two levels up.
$Root    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$JmodDir = Join-Path $Root 'jmods'
$Out     = Join-Path $JmodDir 'windows-jars'
$Tmp     = Join-Path $JmodDir 'windows-jars-tmp'
$Zip     = Join-Path $Root 'win-sdk.zip'

New-Item -ItemType Directory -Force -Path $JmodDir | Out-Null
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
if (Test-Path $Tmp) { Remove-Item -Recurse -Force $Tmp }

$Url = "https://download2.gluonhq.com/openjfx/$Version/openjfx-${Version}_windows-x64_bin-sdk.zip"
Write-Host "Downloading $Url"
Invoke-WebRequest -Uri $Url -OutFile $Zip

Write-Host "Extracting to $Out"
Expand-Archive -Path $Zip -DestinationPath $Tmp -Force
Move-Item (Join-Path $Tmp "javafx-sdk-$Version") $Out
Remove-Item -Recurse -Force $Tmp
Remove-Item $Zip

Write-Host "Done. Add the following to PATH before running:"
Write-Host "  $Out\bin"
