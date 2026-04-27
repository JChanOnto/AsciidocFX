# scripts/internal/build_windows_installer.ps1
#
# Stages the appassembler tree + a bundled JRE + the wrapper launcher and
# invokes Inno Setup (iscc.exe) to produce a single AsciidocFX-<ver>-Setup.exe.
#
# Prerequisites:
#   * `mvn -P install4j-package package` (or scripts\build.ps1) has produced
#     target\appassembler\ already.
#   * Inno Setup 6 is installed; iscc.exe is on PATH (or pass -IsccPath).
#   * A JDK is available — defaults to $env:JAVA_HOME, override with -JavaHome.
#
# Usage:
#   .\scripts\internal\build_windows_installer.ps1
#   .\scripts\internal\build_windows_installer.ps1 -Version 1.8.11 -JavaHome 'C:\jdk-21'

[CmdletBinding()]
param(
    [string]$Version,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$IsccPath = 'iscc.exe',
    [string]$AppAssembler,
    [string]$OutputDir,
    [string]$IconFile
)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')

# --- Resolve inputs --------------------------------------------------------
if (-not $AppAssembler) { $AppAssembler = Join-Path $repoRoot 'target\appassembler' }
if (-not $OutputDir)    { $OutputDir    = Join-Path $repoRoot 'target' }
if (-not $IconFile)     { $IconFile     = Join-Path $repoRoot 'src\main\resources\logo.png' }

if (-not (Test-Path $AppAssembler)) {
    throw "appassembler tree not found at '$AppAssembler'. Run scripts\build.ps1 first."
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "JavaHome '$JavaHome' is not a valid JDK (missing bin\java.exe). Set -JavaHome or `$env:JAVA_HOME."
}

# Resolve version from pom.xml if not supplied
if (-not $Version) {
    $pom = [xml](Get-Content (Join-Path $repoRoot 'pom.xml'))
    # Strip default xmlns so XPath works
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace('m', $pom.DocumentElement.NamespaceURI)
    $Version = $pom.SelectSingleNode('/m:project/m:version', $ns).InnerText
}

Write-Host "[installer] Version       : $Version"
Write-Host "[installer] AppAssembler  : $AppAssembler"
Write-Host "[installer] JavaHome      : $JavaHome"
Write-Host "[installer] OutputDir     : $OutputDir"

# --- Locate iscc.exe -------------------------------------------------------
$iscc = (Get-Command $IsccPath -ErrorAction SilentlyContinue)?.Source
if (-not $iscc) {
    foreach ($cand in @(
        "$env:ProgramFiles\Inno Setup 6\iscc.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\iscc.exe"
    )) {
        if (Test-Path $cand) { $iscc = $cand; break }
    }
}
if (-not $iscc) {
    throw "iscc.exe not found. Install Inno Setup 6 (choco install innosetup) or pass -IsccPath."
}
Write-Host "[installer] iscc          : $iscc"

# --- Stage the payload -----------------------------------------------------
$staging = Join-Path $repoRoot 'target\installer-staging\windows'
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

Write-Host "[installer] Staging app/ ..."
Copy-Item -Recurse -Force "$AppAssembler\*" (New-Item -ItemType Directory -Force -Path (Join-Path $staging 'app')).FullName

Write-Host "[installer] Staging runtime/ (JRE) ..."
$runtimeDst = New-Item -ItemType Directory -Force -Path (Join-Path $staging 'runtime')
Copy-Item -Recurse -Force "$JavaHome\*" $runtimeDst.FullName

Write-Host "[installer] Staging launcher.bat ..."
Copy-Item (Join-Path $repoRoot 'installer\windows\launcher.bat') $staging

Write-Host "[installer] Staging LICENSE.txt ..."
$license = Join-Path $repoRoot 'LICENSE'
if (Test-Path $license) {
    Copy-Item $license (Join-Path $staging 'LICENSE.txt')
} else {
    Set-Content -Path (Join-Path $staging 'LICENSE.txt') -Value 'See https://github.com/asciidocfx/AsciidocFX'
}

# Convert PNG -> ICO so Inno Setup / shortcuts have a proper icon. ImageMagick
# (`magick`) is the easiest path; if it's missing we fall back to writing a
# placeholder icon by reusing the favicon.ico that ships with the app.
$icoTarget = Join-Path $staging 'app\bin\asciidocfx.ico'
New-Item -ItemType Directory -Force -Path (Split-Path $icoTarget) | Out-Null
$magick = (Get-Command magick -ErrorAction SilentlyContinue)?.Source
if ($magick -and (Test-Path $IconFile)) {
    Write-Host "[installer] Converting $IconFile -> $icoTarget via ImageMagick"
    & $magick $IconFile -define icon:auto-resize=256,128,64,48,32,16 $icoTarget
    if ($LASTEXITCODE -ne 0) { throw "magick failed to convert icon" }
} else {
    $fallback = Join-Path $repoRoot 'conf\public\favicon.ico'
    if (Test-Path $fallback) {
        Write-Host "[installer] Using fallback icon $fallback"
        Copy-Item $fallback $icoTarget
    } else {
        Write-Warning "No icon available; installer will use Inno Setup default."
        Remove-Item -ErrorAction SilentlyContinue $icoTarget
    }
}

# --- Run Inno Setup --------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$iss = Join-Path $repoRoot 'installer\windows\asciidocfx.iss'

# When the icon was unavailable, strip the SetupIconFile / UninstallDisplayIcon
# directives by passing an empty define — easier: only pass them when present.
$isccArgs = @(
    "/DMyAppVersion=$Version",
    "/DStagingDir=$staging",
    "/DOutputDir=$OutputDir",
    $iss
)

Write-Host "[installer] iscc $($isccArgs -join ' ')"
& $iscc @isccArgs
if ($LASTEXITCODE -ne 0) { throw "iscc failed with exit code $LASTEXITCODE" }

$out = Get-ChildItem (Join-Path $OutputDir "AsciidocFX-$Version-Setup.exe") -ErrorAction Stop
Write-Host ""
Write-Host "[installer] Done: $($out.FullName)  ($([math]::Round($out.Length/1MB,1)) MB)"
