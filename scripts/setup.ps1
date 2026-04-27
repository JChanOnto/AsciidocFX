# scripts/setup.ps1
#
# One-shot dev environment setup for AsciidocFX on Windows.
#
# Default (no args): checks for and installs every "Required" prerequisite
# from README.md, then downloads the JavaFX SDK into jmods/windows-jars/.
# Already-installed tools are detected and skipped.
#
# Required prerequisites handled:
#   * Eclipse Temurin JDK 25 LTS         (winget: EclipseAdoptium.Temurin.25.JDK)
#   * Apache Maven 3.9+                  (winget: Apache.Maven)
#   * Git                                (winget: Git.Git)
#   * Node.js LTS + npm                  (winget: OpenJS.NodeJS.LTS)
#   * @mermaid-js/mermaid-cli (mmdc)     (npm install -g)
#   * Graphviz (dot)                     (winget: Graphviz.Graphviz)
#                                        + sets GRAPHVIZ_DOT user env var
#
# Optional flags:
#   -WithRuby      Also install 7-Zip and bundle a portable CRuby runtime
#                  into ruby-runtime/windows/ (~40 MB). Required only if you
#                  want the install4j package to ship the fast native-Ruby
#                  PDF render path.
#   -SkipPrereqs   Skip prerequisite check/install; only download the JavaFX
#                  SDK. Use this in CI where deps come from a base image.
#   -Force         Re-download the JavaFX SDK / CRuby bundle even if present.
#
# After installation, open a NEW terminal so the updated PATH / JAVA_HOME
# from the per-tool installers becomes visible.

[CmdletBinding()]
param(
    [switch]$WithRuby,
    [switch]$SkipPrereqs,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$internal = Join-Path $PSScriptRoot 'internal'

# --- helpers ---------------------------------------------------------------

function Have([string]$cmd) {
    [bool](Get-Command $cmd -ErrorAction SilentlyContinue)
}

# Pull fresh PATH + a few key vars from the registry into the current session
# so tools just installed by winget become visible without reopening pwsh.
function Update-SessionEnvironment {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath    = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = (@($machinePath, $userPath) | Where-Object { $_ }) -join ';'
    foreach ($v in 'JAVA_HOME', 'GRAPHVIZ_DOT', 'M2_HOME') {
        $val = [Environment]::GetEnvironmentVariable($v, 'Machine')
        if (-not $val) { $val = [Environment]::GetEnvironmentVariable($v, 'User') }
        if ($val) { Set-Item -Path "env:$v" -Value $val }
    }
}

# Well-known install locations (glob-friendly) for tools whose installers
# don't always update PATH (or whose PATH update gets clobbered by the
# `setx` 1024-char User PATH truncation bug). Used as a fallback after
# winget install if `Have <cmd>` still returns false.
$Script:ToolBinSearchPaths = @{
    'java' = @(
        'C:\Program Files\Eclipse Adoptium\jdk-*\bin',
        'C:\Program Files\Java\jdk-*\bin',
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-*\bin"
    )
    'mvn'  = @(
        'C:\Program Files\Apache\maven-*\bin',
        'C:\ProgramData\chocolatey\lib\maven\apache-maven-*\bin',
        "$env:USERPROFILE\scoop\apps\maven\current\bin"
    )
    'git'  = @(
        'C:\Program Files\Git\cmd',
        'C:\Program Files (x86)\Git\cmd'
    )
    'node' = @(
        'C:\Program Files\nodejs',
        'C:\Program Files (x86)\nodejs',
        "$env:LOCALAPPDATA\Programs\nodejs"
    )
    'npm'  = @(
        'C:\Program Files\nodejs',
        "$env:LOCALAPPDATA\Programs\nodejs"
    )
    'mmdc' = @(
        "$env:APPDATA\npm"
    )
    'dot'  = @(
        'C:\Program Files\Graphviz\bin',
        'C:\Program Files (x86)\Graphviz\bin'
    )
    '7z'   = @(
        'C:\Program Files\7-Zip',
        'C:\Program Files (x86)\7-Zip',
        "$env:USERPROFILE\scoop\apps\7zip\current"
    )
}

# Search the well-known locations for a directory containing <cmd>.{exe,cmd,bat,ps1}.
# Returns the absolute bin dir or $null. Picks the highest-versioned match (Sort -Descending).
function Find-ToolBinDir([string]$cmd) {
    if (-not $Script:ToolBinSearchPaths.ContainsKey($cmd)) { return $null }
    $exts = @('.exe', '.cmd', '.bat', '.ps1')
    $candidates = foreach ($pattern in $Script:ToolBinSearchPaths[$cmd]) {
        Get-Item -Path $pattern -ErrorAction SilentlyContinue
    }
    $hits = foreach ($dir in $candidates) {
        foreach ($ext in $exts) {
            if (Test-Path -LiteralPath (Join-Path $dir.FullName ($cmd + $ext)) -PathType Leaf) {
                $dir.FullName
                break
            }
        }
    }
    if (-not $hits) { return $null }
    # Prefer newest (highest version dir) when multiple match.
    return ($hits | Sort-Object -Descending | Select-Object -First 1)
}

# Append a directory to the User PATH via direct registry write, avoiding the
# `setx` / [Environment]::SetEnvironmentVariable 1024-char truncation bug.
# Also updates current session PATH and broadcasts WM_SETTINGCHANGE so newly
# spawned shells (and Explorer-launched processes) pick up the change.
function Add-UserPathEntry([string]$dir) {
    if (-not $dir) { return $false }
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try {
        $current = [string]$key.GetValue(
            'Path', '',
            [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $entries = @($current -split ';' | Where-Object { $_ })
        if ($entries -contains $dir) { return $false }
        $new = ($entries + $dir) -join ';'
        $key.SetValue('Path', $new, [Microsoft.Win32.RegistryValueKind]::ExpandString)
    } finally {
        $key.Close()
    }
    if (($env:Path -split ';') -notcontains $dir) {
        $env:Path = "$env:Path;$dir"
    }
    # Broadcast so new processes inherit the updated PATH.
    try {
        $sig = @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(
    IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
        if (-not ('Win32.NativeMethods' -as [type])) {
            Add-Type -MemberDefinition $sig -Name NativeMethods -Namespace Win32 | Out-Null
        }
        $HWND_BROADCAST = [IntPtr]0xffff
        $WM_SETTINGCHANGE = 0x1A
        $result = [UIntPtr]::Zero
        [void][Win32.NativeMethods]::SendMessageTimeout(
            $HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, 'Environment',
            2, 5000, [ref]$result)
    } catch {
        # Non-fatal; PATH is still persisted.
    }
    return $true
}

# After a winget install where `Have <cmd>` still fails, search known install
# dirs and add the discovered bin dir to User PATH. Returns $true if the tool
# is now discoverable on PATH.
function Resolve-MissingToolOnPath([string]$cmd, [string]$label) {
    if (Have $cmd) { return $true }
    $bin = Find-ToolBinDir $cmd
    if (-not $bin) {
        Write-Warning "$label was installed but '$cmd' is not on PATH and could not be found in known locations. Add it to PATH manually."
        return $false
    }
    Write-Host "[setup] Found $label at $bin (not on PATH); adding to user PATH"
    [void](Add-UserPathEntry $bin)
    if (Have $cmd) {
        Write-Host "[setup] OK: $label now resolvable as '$cmd'"
        return $true
    }
    Write-Warning "$label located at $bin but '$cmd' still not resolvable. Open a new shell and re-run setup."
    return $false
}

# Wraps `winget install`. Returns $true on success or "already installed".
# Retries transient network failures (e.g. 0x80072ee7 InternetOpenUrl).
function Install-WingetPackage([string]$id, [string]$label) {
    if (-not (Have 'winget')) {
        Write-Warning "winget not found. Install '$label' manually (see README.md)."
        return $false
    }
    # 0           = installed
    # -1978335189 = no applicable update / already installed
    $successCodes  = @(0, -1978335189)
    # 0x80072ee7 -> -2147012889 ERROR_INTERNET_NAME_NOT_RESOLVED
    # 0x80072efe -> -2147012866 ERROR_INTERNET_CONNECTION_ABORTED
    # 0x80072efd -> -2147012867 ERROR_INTERNET_CANNOT_CONNECT
    # 0x80072ee2 -> -2147012894 ERROR_INTERNET_TIMEOUT
    # -1978335215 / -1978334969 winget download / installer download error
    $transientCodes = @(-2147012889, -2147012866, -2147012867, -2147012894, -1978335215, -1978334969)

    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $suffix = if ($attempt -gt 1) { " (attempt $attempt/$maxAttempts)" } else { "" }
        Write-Host "[setup] Installing $label via winget ($id) ...$suffix"
        & winget install --id $id --exact `
            --accept-source-agreements --accept-package-agreements `
            --silent --disable-interactivity 2>&1 | Out-Host
        $code = $LASTEXITCODE
        Update-SessionEnvironment
        if ($successCodes -contains $code) { return $true }
        if (($transientCodes -contains $code) -and ($attempt -lt $maxAttempts)) {
            $delay = [int][Math]::Pow(2, $attempt)  # 2s, 4s
            Write-Warning "winget install $id hit transient error 0x$('{0:X8}' -f $code); retrying in ${delay}s..."
            Start-Sleep -Seconds $delay
            continue
        }
        Write-Warning "winget install $id exited with code $code"
        return $false
    }
    return $false
}

# Returns the major Java version on PATH, or 0 if `java` is missing.
function Get-JavaMajorVersion {
    if (-not (Have 'java')) { return 0 }
    $verLine = (& java -version 2>&1 | Select-Object -First 1)
    if ($verLine -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

# Verify `java -version` reports >= 25; install Temurin 25 otherwise.
function Confirm-Jdk25 {
    $major = Get-JavaMajorVersion
    if ($major -ge 25) {
        Write-Host "[setup] OK: JDK $major detected"
        return
    }
    if ($major -gt 0) {
        Write-Host "[setup] Found JDK $major but need >= 25; installing Temurin 25..."
    } else {
        Write-Host "[setup] JDK not found; installing Temurin 25..."
    }
    [void](Install-WingetPackage 'EclipseAdoptium.Temurin.25.JDK' 'Eclipse Temurin JDK 25')
    # Post-install: if `java` still isn't on PATH, locate the Temurin bin and
    # add it. Without this, every re-run of setup.ps1 would re-install.
    if (-not (Have 'java')) {
        [void](Resolve-MissingToolOnPath 'java' 'Eclipse Temurin JDK 25')
    }
    $major = Get-JavaMajorVersion
    if ($major -lt 25) {
        Write-Warning "JDK 25 install completed but active 'java' reports version $major. A higher-priority older JDK may be on PATH; check 'where.exe java'."
    }
}

function Confirm-Tool([string]$cmd, [string]$wingetId, [string]$label) {
    if (Have $cmd) {
        Write-Host "[setup] OK: $label detected ($cmd)"
        return
    }
    Write-Host "[setup] $label not found; installing..."
    [void](Install-WingetPackage $wingetId $label)
    if (-not (Have $cmd)) {
        # Installer succeeded but didn't update PATH (or User PATH was
        # truncated by the setx 1024-char bug). Search known locations.
        [void](Resolve-MissingToolOnPath $cmd $label)
    }
}

# --- prerequisite checks ---------------------------------------------------

if (-not $SkipPrereqs) {
    Write-Host "[setup] Checking required tools..."

    Confirm-Jdk25
    Confirm-Tool 'mvn'  'Apache.Maven'        'Apache Maven'
    Confirm-Tool 'git'  'Git.Git'             'Git'
    Confirm-Tool 'node' 'OpenJS.NodeJS.LTS'   'Node.js LTS'
    Confirm-Tool 'dot'  'Graphviz.Graphviz'   'Graphviz'

    Update-SessionEnvironment

    # mmdc — npm global. Needs node first.
    if (Have 'mmdc') {
        Write-Host "[setup] OK: mmdc (Mermaid CLI) detected"
    } elseif (Have 'npm') {
        Write-Host "[setup] Installing @mermaid-js/mermaid-cli via npm..."
        & npm install -g '@mermaid-js/mermaid-cli'
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "npm install mmdc failed (exit $LASTEXITCODE)"
        } elseif (-not (Have 'mmdc')) {
            # npm's global prefix (%APPDATA%\npm) isn't always on PATH on a
            # fresh Node.js install. Add it so subsequent runs see mmdc.
            [void](Resolve-MissingToolOnPath 'mmdc' 'Mermaid CLI')
        }
    } else {
        Write-Warning "npm not on PATH yet. Open a NEW terminal and re-run scripts\setup.ps1 to install mmdc."
    }

    # GRAPHVIZ_DOT — set as user env var if missing and dot is available.
    # JAVA_HOME is set by Temurin's MSI installer; we don't touch it.
    if (Have 'dot') {
        $existing = [Environment]::GetEnvironmentVariable('GRAPHVIZ_DOT', 'User')
        if (-not $existing) {
            $dotPath = (Get-Command dot).Source
            Write-Host "[setup] Setting user env GRAPHVIZ_DOT = $dotPath"
            [Environment]::SetEnvironmentVariable('GRAPHVIZ_DOT', $dotPath, 'User')
            $env:GRAPHVIZ_DOT = $dotPath
        }
    }

    # 7-Zip is only needed for the optional CRuby bundle on Windows.
    # winget's MSI installs to C:\Program Files\7-Zip\ without touching PATH,
    # so Confirm-Tool relies on Resolve-MissingToolOnPath / the '7z' entry in
    # $Script:ToolBinSearchPaths to add that dir on first run.
    if ($WithRuby) {
        Confirm-Tool '7z' '7zip.7zip' '7-Zip'
    }
}

# --- JavaFX SDK ------------------------------------------------------------

$jfxJarsDir = Join-Path $repoRoot 'jmods\windows-jars'
if ($Force -and (Test-Path $jfxJarsDir)) {
    Write-Host "[setup] -Force: removing existing $jfxJarsDir"
    Remove-Item -Recurse -Force $jfxJarsDir
}
if (Test-Path (Join-Path $jfxJarsDir 'bin\javafx_iio.dll')) {
    Write-Host "[setup] OK: JavaFX SDK present at jmods\windows-jars\ (use -Force to re-download)"
} else {
    Write-Host "[setup] Downloading JavaFX SDK ..."
    # NOTE: use truthy `if ($LASTEXITCODE)` rather than `-ne 0`. When the
    # sub-script runs only PS cmdlets (no native commands) AND no native
    # command ran earlier in this session, $LASTEXITCODE is $null --
    # PowerShell evaluates `$null -ne 0` as $true, which would `exit`
    # this script silently. Truthy form treats both 0 and $null as
    # success. This bug bit CI when invoked with -SkipPrereqs (which
    # skips the prerequisite block where every other native command
    # lives), making setup return 0 without ever bundling CRuby.
    & (Join-Path $internal 'download_javafx_jars.ps1')
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

# --- Bundled CRuby (optional) ---------------------------------------------

if ($WithRuby) {
    $rubyDir = Join-Path $repoRoot 'ruby-runtime\windows'
    if ($Force -and (Test-Path $rubyDir)) {
        Write-Host "[setup] -Force: removing existing $rubyDir"
        Remove-Item -Recurse -Force $rubyDir
    }
    Write-Host "[setup] Bundling portable CRuby runtime ..."
    & (Join-Path $internal 'bundle_ruby_runtime.ps1')
    if ($LASTEXITCODE) { exit $LASTEXITCODE }

    # Belt-and-suspenders: confirm the bundle actually produced the file
    # the installer pipeline depends on. If the sub-script silently no-op'd
    # (e.g. partially-populated tree it thought was complete), surface it
    # here rather than discovering it three steps later in the Inno Setup
    # build.
    $rubyBat = Join-Path $rubyDir 'bin\asciidoctor-pdf.bat'
    if (-not (Test-Path $rubyBat)) {
        Write-Error "[setup] Bundled CRuby runtime missing $rubyBat after bundle_ruby_runtime.ps1 ran. The installer payload will be incomplete."
        exit 1
    }
    Write-Host "[setup] OK: bundled CRuby ready ($rubyBat)"
}

Write-Host ""
Write-Host "[setup] Done."
Write-Host "  If any tools were just installed, open a NEW terminal so PATH / JAVA_HOME refresh."
Write-Host "  Next:  .\scripts\build.ps1            (package the app)"
Write-Host "         .\scripts\run.ps1              (run from source)"
