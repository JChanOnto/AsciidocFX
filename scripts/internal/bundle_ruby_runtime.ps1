# scripts/internal/bundle_ruby_runtime.ps1
#
# Download a portable CRuby for Windows + vendor `asciidoctor-pdf` and its
# dependencies into `<repo>/ruby-runtime/windows/`. The directory is then
# copied verbatim into the install4j package, giving end users the fast
# native-Ruby PDF render path without a separate install.
#
# Prefer invoking via `scripts\setup.ps1 -WithRuby` or `scripts\build.ps1
# -WithRuby` rather than running this directly.
#
# Idempotent: skips download/install if the target tree already looks
# populated. Delete `ruby-runtime/windows/` to force a fresh build.
#
# Layout produced:
#     ruby-runtime/windows/
#         bin/ruby.exe
#         bin/asciidoctor-pdf.bat       <- entrypoint AsciidocFX shells out to
#         bin/...
#         lib/ruby/gems/<x.y.0>/gems/asciidoctor-pdf-*/
#         lib/ruby/gems/<x.y.0>/gems/asciidoctor-diagram-*/
#         ...

$ErrorActionPreference = 'Stop'
# This script lives at <repo>/scripts/internal/; repo root is two levels up.
$repoRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtimeDir = Join-Path $repoRoot 'ruby-runtime\windows'

# Pin to a known-good RubyInstaller release.  Bump deliberately.
$rubyVersion = '3.4.1-1'
$rubyZipUrl  = "https://github.com/oneclick/rubyinstaller2/releases/download/RubyInstaller-$rubyVersion/rubyinstaller-$rubyVersion-x64.7z"
$rubyZipName = "rubyinstaller-$rubyVersion-x64.7z"

# Gems pinned to versions matching the project Gemfile in
# DEMO-2026-Q1-TrueADC-Docs.  Update together with that Gemfile.
$gems = @(
    'asciidoctor:2.0.23',
    'asciidoctor-pdf:2.3.23',
    'asciidoctor-diagram:2.3.2',
    'rouge:4.5.1',
    'prawn-svg:0.36.0'
)

if ((Test-Path (Join-Path $runtimeDir 'bin\ruby.exe')) -and `
    (Test-Path (Join-Path $runtimeDir 'bin\asciidoctor-pdf.bat'))) {
    Write-Host "ruby-runtime/windows/ already populated; nothing to do."
    Write-Host "  Delete the directory to force a fresh download."
    exit 0
}

$tmpDir = Join-Path $env:TEMP "afx-ruby-bundle-$([guid]::NewGuid().ToString('N').Substring(0,8))"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$archive = Join-Path $tmpDir $rubyZipName

# 7-Zip is required for the upstream archive format.  Try common locations.
$sevenZip = @(
    'C:\Program Files\7-Zip\7z.exe',
    'C:\Program Files (x86)\7-Zip\7z.exe',
    "$env:USERPROFILE\scoop\shims\7z.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $sevenZip) {
    $found = Get-Command 7z.exe -ErrorAction SilentlyContinue
    if ($found) { $sevenZip = $found.Source }
}
if (-not $sevenZip) {
    Write-Error "7-Zip not found.  Install with `scoop install 7zip` or `winget install 7zip.7zip`."
    exit 1
}

Write-Host "Downloading $rubyZipUrl ..."
Invoke-WebRequest -Uri $rubyZipUrl -OutFile $archive -UseBasicParsing

Write-Host "Extracting Ruby runtime to $runtimeDir ..."
if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
& $sevenZip x $archive "-o$tmpDir" -y | Out-Null

# RubyInstaller archives unpack to `rubyinstaller-3.x.y-1-x64\`; flatten it.
$inner = Get-ChildItem -Path $tmpDir -Directory | Where-Object Name -Like 'rubyinstaller-*' | Select-Object -First 1
if (-not $inner) {
    Write-Error "Unexpected archive layout under $tmpDir"
    exit 1
}
Copy-Item -Recurse -Force "$($inner.FullName)\*" $runtimeDir

# Trim things end users don't need to keep the bundle small.
foreach ($d in 'share\doc', 'share\man', 'msys64\usr\share\doc', 'msys64\usr\share\man') {
    $p = Join-Path $runtimeDir $d
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}

# Install gems against the bundled Ruby.  We use plain `gem install` rather
# than Bundler so the runtime is self-contained and doesn't need a Gemfile
# at runtime.
$rubyExe = Join-Path $runtimeDir 'bin\ruby.exe'
$gemBat  = Join-Path $runtimeDir 'bin\gem.cmd'
if (-not (Test-Path $gemBat)) { $gemBat = Join-Path $runtimeDir 'bin\gem.bat' }

Write-Host "Installing gems with $gemBat ..."
foreach ($spec in $gems) {
    $name, $ver = $spec -split ':'
    Write-Host "  -> $name ($ver)"
    & $gemBat install $name --version $ver --no-document --conservative --silent
    if ($LASTEXITCODE -ne 0) {
        Write-Error "gem install $spec failed (exit $LASTEXITCODE)"
        exit $LASTEXITCODE
    }
}

# Smoke test
Write-Host "Smoke test: asciidoctor-pdf --version"
& (Join-Path $runtimeDir 'bin\asciidoctor-pdf.bat') --version
if ($LASTEXITCODE -ne 0) {
    Write-Error "Bundled asciidoctor-pdf failed its smoke test."
    exit $LASTEXITCODE
}

Remove-Item -Recurse -Force $tmpDir
Write-Host ""
Write-Host "Bundled Ruby runtime ready at: $runtimeDir"
