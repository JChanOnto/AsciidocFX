# scripts/run.ps1
#
# Run AsciidocFX from source via the `local-run` Maven profile.
# Ensures the JavaFX SDK is present and that its native bin\ DLLs are on
# PATH for the JavaFX QuantumRenderer.
#
# Usage:
#   .\scripts\run.ps1                    # ensure setup, then mvn local-run
#   .\scripts\run.ps1 -SkipSetup         # don't call setup.ps1 first
#   .\scripts\run.ps1 -SoftwareRender    # force JavaFX software pipeline
#                                        # (use over RDP / no GPU / VM)

[CmdletBinding()]
param(
    [switch]$SkipSetup,
    [switch]$SoftwareRender
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

# JavaFX 25 has no automatic D3D->software fallback when the GPU pipeline
# fails to initialize (e.g. inside an RDP session that has lost its desktop
# context). Symptom: NPE from QuantumToolkit.isSupported because
# GraphicsPipeline.getPipeline() returns null. -Dprism.order=sw forces the
# software pipeline. JAVA_TOOL_OPTIONS is appended to the JVM args by the
# launcher itself, so it stacks with pom.xml's <jvmArguments> rather than
# replacing them (unlike -Dspring-boot.run.jvmArguments).
if ($SoftwareRender) {
    $sw = '-Dprism.order=sw -Dprism.verbose=true'
    if ($env:JAVA_TOOL_OPTIONS) {
        $env:JAVA_TOOL_OPTIONS = "$($env:JAVA_TOOL_OPTIONS) $sw"
    } else {
        $env:JAVA_TOOL_OPTIONS = $sw
    }
    Write-Host "[run] -SoftwareRender: JAVA_TOOL_OPTIONS=$($env:JAVA_TOOL_OPTIONS)"
}

Push-Location $repoRoot
try {
    Write-Host "[run] mvn -P local-run spring-boot:run"
    & mvn -P local-run spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
