# scripts/build.ps1
#
# Package AsciidocFX into target/appassembler/ + target/asciidocfx-<ver>.zip.
#
# By default this:
#   1. Runs scripts\setup.ps1 (downloads JavaFX SDK if missing)
#   2. Invokes `mvn -P install4j-package clean package -DskipTests`
#
# Usage:
#   .\scripts\build.ps1                 # standard package
#   .\scripts\build.ps1 -WithRuby       # also bundle portable CRuby runtime
#   .\scripts\build.ps1 -SkipSetup      # skip the setup step (e.g. CI)
#   .\scripts\build.ps1 -- -DskipTests=false   # forward extra args to mvn

[CmdletBinding()]
param(
    [switch]$WithRuby,
    [switch]$SkipSetup,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MvnArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not $SkipSetup) {
    # NOTE: must use HASHTABLE splatting for named/switch params; array
    # splatting (@('-WithRuby')) passes elements positionally and setup.ps1
    # has no positional params, which raises "A positional parameter cannot
    # be found that accepts argument '-WithRuby'".
    $setupArgs = @{}
    if ($WithRuby) { $setupArgs['WithRuby'] = $true }
    & (Join-Path $PSScriptRoot 'setup.ps1') @setupArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Push-Location $repoRoot
try {
    $args = @('-P', 'install4j-package', 'clean', 'package', '-DskipTests')
    if ($MvnArgs) { $args += $MvnArgs }
    Write-Host "[build] mvn $($args -join ' ')"
    & mvn @args
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "[build] Done. Artifacts:"
Write-Host "  target\appassembler\bin\asciidocfx.bat   (launcher)"
Write-Host "  target\appassembler\                     (payload — feeds installer/windows)"
