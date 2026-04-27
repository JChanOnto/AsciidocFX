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
    [string]$IconFile,
    # When set, abort if the appassembler tree does NOT contain a bundled
    # CRuby (target\appassembler\ruby\bin\asciidoctor-pdf.bat). Use in CI
    # when the release pipeline ran with -WithRuby and silently dropping
    # the fast PDF path would be a release-blocking regression.
    [switch]$RequireBundledRuby
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

# --- Bundled CRuby diagnostics --------------------------------------------
# The Maven install4j-package profile copies ruby-runtime/<os>/ into
# target\appassembler\ruby\ when present. If it's missing here, the
# installed app silently falls back to in-process JRuby for PDF render
# (slow). Make that visible at staging time so a CI / release builder
# catches it before users do.
$stagedRubyBin       = Join-Path $staging 'app\ruby\bin'
$stagedRubyExe       = Join-Path $stagedRubyBin 'ruby.exe'
$stagedAsciidoctorPdf = Join-Path $stagedRubyBin 'asciidoctor-pdf.bat'

Write-Host ""
Write-Host "================================================================"
Write-Host "  Bundled CRuby payload check (target: <install>\app\ruby\)"
Write-Host "================================================================"
$rubyOk = $true
if (-not (Test-Path $stagedRubyBin)) {
    Write-Warning "  app\ruby\bin\        MISSING  (BundledRubyResolver will return null)"
    $rubyOk = $false
} else {
    $rubyFiles = Get-ChildItem $stagedRubyBin -File -ErrorAction SilentlyContinue
    $rubySize  = (Get-ChildItem (Join-Path $staging 'app\ruby') -Recurse -File -ErrorAction SilentlyContinue |
                  Measure-Object Length -Sum).Sum
    Write-Host ("  app\ruby\bin\        present  ({0} files)" -f $rubyFiles.Count)
    Write-Host ("  app\ruby\ total      {0:N1} MB" -f ($rubySize / 1MB))
}
foreach ($req in @($stagedRubyExe, $stagedAsciidoctorPdf)) {
    $rel = $req.Substring($staging.Length).TrimStart('\')
    if (Test-Path $req) {
        Write-Host "  $rel  OK"
    } else {
        Write-Warning "  $rel  MISSING"
        $rubyOk = $false
    }
}
if ($rubyOk) {
    Write-Host "[installer] Bundled CRuby OK -- installed app will use fast native PDF render path."
} else {
    $msg = @(
        ""
        "*** BUNDLED CRuby NOT IN STAGING ***"
        "    The installer will produce a working AsciidocFX, but PDF render"
        "    will silently fall back to the in-process JRuby path (slow)."
        ""
        "    To fix:"
        "      1. Run scripts\setup.ps1 -WithRuby   (populates ruby-runtime\windows\)"
        "      2. Run scripts\build.ps1   -WithRuby (re-runs the package step which"
        "         copies ruby-runtime\windows\ -> target\appassembler\ruby\)"
        "      3. Re-run this script."
        ""
        "    Source dir checked    : $(Join-Path $repoRoot 'ruby-runtime\windows')"
        "    AppAssembler dir      : $AppAssembler"
        "    Staging target        : $stagedRubyBin"
        ""
    ) -join [Environment]::NewLine
    if ($RequireBundledRuby) {
        throw "Bundled CRuby missing from staging payload. $msg"
    } else {
        Write-Warning $msg
    }
}
Write-Host "================================================================"
Write-Host ""

Write-Host "[installer] Staging runtime/ (JRE) ..."
$runtimeDst = New-Item -ItemType Directory -Force -Path (Join-Path $staging 'runtime')
Copy-Item -Recurse -Force "$JavaHome\*" $runtimeDst.FullName

# JavaFX SDK — the bundled Temurin JDK does NOT include JavaFX modules.
# (install4j used to jlink jmods/windows/*.jmod into a custom JRE; we ship
# the stock JRE + the JavaFX SDK on the side, then point the launcher at
# it via --module-path. lib/ holds the module jars; bin/ holds the native
# DLLs that QuantumRenderer / WebKit dlopen at runtime.)
$jfxSdk = Join-Path $repoRoot 'jmods\windows-jars'
if (-not (Test-Path (Join-Path $jfxSdk 'lib\javafx.controls.jar'))) {
    throw "JavaFX SDK not found at '$jfxSdk' (missing lib\javafx.controls.jar). Run scripts\setup.ps1 first."
}
Write-Host "[installer] Staging javafx/ (JavaFX SDK lib + bin) ..."
$jfxDst = New-Item -ItemType Directory -Force -Path (Join-Path $staging 'javafx')
Copy-Item -Recurse -Force (Join-Path $jfxSdk 'lib') $jfxDst.FullName
Copy-Item -Recurse -Force (Join-Path $jfxSdk 'bin') $jfxDst.FullName

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

# Side-car manifest of what we packaged. Useful as a CI artifact: when a
# user reports "bundled Ruby didn't install on my machine", you can diff
# their actual install layout against this to see whether the payload
# even contained the files.
$manifest = Join-Path $OutputDir "AsciidocFX-$Version-payload-manifest.txt"
"AsciidocFX $Version installer payload manifest" | Out-File -Encoding utf8 $manifest
"Built       : $(Get-Date -Format o)"            | Out-File -Encoding utf8 -Append $manifest
"Staging dir : $staging"                          | Out-File -Encoding utf8 -Append $manifest
"BundledRuby : $rubyOk"                           | Out-File -Encoding utf8 -Append $manifest
""                                                | Out-File -Encoding utf8 -Append $manifest
"--- staging\app\ruby\ tree (or '<missing>') ---" | Out-File -Encoding utf8 -Append $manifest
$rubyRoot = Join-Path $staging 'app\ruby'
if (Test-Path $rubyRoot) {
    Get-ChildItem $rubyRoot -Recurse -File |
        ForEach-Object { '{0,12}  {1}' -f $_.Length, $_.FullName.Substring($staging.Length).TrimStart('\') } |
        Out-File -Encoding utf8 -Append $manifest
} else {
    "<missing>" | Out-File -Encoding utf8 -Append $manifest
}
Write-Host "[installer] Payload manifest: $manifest"

$out = Get-ChildItem (Join-Path $OutputDir "AsciidocFX-$Version-Setup.exe") -ErrorAction Stop
Write-Host ""
Write-Host "[installer] Done: $($out.FullName)  ($([math]::Round($out.Length/1MB,1)) MB)"
