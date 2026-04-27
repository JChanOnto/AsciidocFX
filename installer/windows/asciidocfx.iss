; ----------------------------------------------------------------------------
; AsciidocFX — Inno Setup installer script
;
; Replaces the previous proprietary install4j installer with a free,
; open-source toolchain (Inno Setup 6, https://jrsoftware.org/isinfo.php).
;
; Inputs (all paths are relative to this .iss file unless overridden):
;   #define MyAppVersion <x.y.z>            -- pass on command line
;   #define StagingDir   <abs path>         -- folder containing the
;                                              prepared payload tree:
;                                                <staging>\app\           (appassembler/*)
;                                                <staging>\runtime\       (bundled JRE)
;                                                <staging>\launcher.bat   (wrapper)
;   #define OutputDir    <abs path>         -- where to drop the .exe
;
; Build:
;   iscc.exe /DMyAppVersion=1.8.11 ^
;            /DStagingDir=C:\path\to\staging ^
;            /DOutputDir=C:\path\to\target ^
;            installer\windows\asciidocfx.iss
;
; The accompanying scripts/internal/build_windows_installer.ps1 helper
; assembles the staging tree and invokes iscc with the right flags.
; ----------------------------------------------------------------------------

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif
#ifndef StagingDir
  #error "StagingDir must be defined (pass /DStagingDir=... to iscc)"
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

#define MyAppName        "AsciidocFX"
#define MyAppPublisher   "AsciidocFX"
#define MyAppURL         "https://asciidocfx.com"
#define MyAppExeName     "launcher.bat"

[Setup]
; AppId uniquely identifies the app for upgrade/uninstall. The leading
; "{{" is Inno's escape for a single literal "{" — the actual AppId stored
; in the registry is "{7853-9376-5862-1224}" (matching the legacy
; install4j applicationId, so existing installs upgrade in place).
AppId={{7853-9376-5862-1224}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir={#OutputDir}
OutputBaseFilename=AsciidocFX-{#MyAppVersion}-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\app\bin\asciidocfx.ico
LicenseFile={#StagingDir}\LICENSE.txt
SetupIconFile={#StagingDir}\app\bin\asciidocfx.ico
ChangesAssociations=yes
; Force Inno Setup to write a per-run install log to %TEMP%\Setup Log YYYY-MM-DD #NNN.txt
; (every file copied, every registry write, every error). When users report
; "bundled Ruby didn't work on a fresh install", ask them for this log --
; it's the ground truth for whether the payload reached disk.
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "associate";   Description: "Associate AsciiDoc files (.adoc, .asciidoc, .ad, .asc) with {#MyAppName}"; GroupDescription: "File associations:"

[Files]
; Application payload (appassembler tree -> {app}\app\)
Source: "{#StagingDir}\app\*";     DestDir: "{app}\app";     Flags: recursesubdirs createallsubdirs ignoreversion
; Bundled JRE (-> {app}\runtime\)
Source: "{#StagingDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: recursesubdirs createallsubdirs ignoreversion
; JavaFX SDK (-> {app}\javafx\) — referenced by launcher.bat via --module-path.
Source: "{#StagingDir}\javafx\*";  DestDir: "{app}\javafx";  Flags: recursesubdirs createallsubdirs ignoreversion
; Top-level launcher that points JAVACMD at the bundled JRE
Source: "{#StagingDir}\launcher.bat"; DestDir: "{app}"; Flags: ignoreversion
; License file
Source: "{#StagingDir}\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion

[UninstallDelete]
; Diagnostic log written by the post-install [Code] section. Not part of
; the [Files] manifest, so register it here so uninstall removes it.
Type: files; Name: "{app}\install-log.txt"

[Icons]
Name: "{group}\{#MyAppName}";          Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app\bin\asciidocfx.ico"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}";    Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\app\bin\asciidocfx.ico"; Tasks: desktopicon

[Registry]
; File associations — AsciiDoc extensions
Root: HKA; Subkey: "Software\Classes\.adoc";     ValueType: string; ValueName: ""; ValueData: "AsciidocFX.Document"; Flags: uninsdeletevalue; Tasks: associate
Root: HKA; Subkey: "Software\Classes\.asciidoc"; ValueType: string; ValueName: ""; ValueData: "AsciidocFX.Document"; Flags: uninsdeletevalue; Tasks: associate
Root: HKA; Subkey: "Software\Classes\.ad";       ValueType: string; ValueName: ""; ValueData: "AsciidocFX.Document"; Flags: uninsdeletevalue; Tasks: associate
Root: HKA; Subkey: "Software\Classes\.asc";      ValueType: string; ValueName: ""; ValueData: "AsciidocFX.Document"; Flags: uninsdeletevalue; Tasks: associate
Root: HKA; Subkey: "Software\Classes\AsciidocFX.Document";                  ValueType: string; ValueName: ""; ValueData: "AsciiDoc Document"; Flags: uninsdeletekey; Tasks: associate
Root: HKA; Subkey: "Software\Classes\AsciidocFX.Document\DefaultIcon";      ValueType: string; ValueName: ""; ValueData: "{app}\app\bin\asciidocfx.ico"; Tasks: associate
Root: HKA; Subkey: "Software\Classes\AsciidocFX.Document\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#MyAppExeName}"" ""%1"""; Tasks: associate

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent shellexec

[Code]
{ ----------------------------------------------------------------------------
  Post-install diagnostics for the bundled CRuby payload.

  The fast PDF render path in AsciidocFX requires:
      <install>\app\ruby\bin\ruby.exe
      <install>\app\ruby\bin\asciidoctor-pdf.bat

  When these are missing, the app silently falls back to in-process JRuby
  (slow). To make that failure mode catchable on end-user machines:

    1. Verify both files exist immediately after install completes.
    2. Copy Inno Setup's own per-install log into <install>\install-log.txt
       so the user can attach it to a bug report without hunting in %TEMP%.
    3. Append a bundled-Ruby section to install-log.txt with the file
       sizes / existence flags.
    4. If anything is missing (and we're not running silently) show a
       message box pointing at install-log.txt.
  ---------------------------------------------------------------------------- }

// Pascal Script in Inno Setup does not have BoolToStr; provide our own.
function BoolStr(B: Boolean): string;
begin
  if B then Result := 'True' else Result := 'False';
end;

procedure VerifyBundledRubyAndWriteLog;
var
  RubyExe, AdocPdfBat, LogDest, RubyDir, Note, SetupLog: string;
  RubyExeOk, AdocPdfOk: Boolean;
  LogLines: TArrayOfString;
begin
  RubyDir    := ExpandConstant('{app}\app\ruby\bin');
  RubyExe    := RubyDir + '\ruby.exe';
  AdocPdfBat := RubyDir + '\asciidoctor-pdf.bat';
  LogDest    := ExpandConstant('{app}\install-log.txt');

  RubyExeOk := FileExists(RubyExe);
  AdocPdfOk := FileExists(AdocPdfBat);

  // Mirror Inno's auto-generated setup log next to the install for easy
  // access. ExpandConstant of the log constant resolves to the active
  // setup log path when SetupLogging=yes (returns '' if logging is off).
  // Use // line comments here -- a Pascal block comment containing the
  // literal {log} would terminate at the inner } and break compilation.
  SetupLog := ExpandConstant('{log}');
  if (SetupLog <> '') and FileExists(SetupLog) then begin
    // CopyFile (renamed from the deprecated FileCopy) returns False if the
    // destination already exists and FailIfExists is True; we pass False so
    // re-installs overwrite cleanly.
    if not CopyFile(SetupLog, LogDest, False) then
      Log('Could not copy setup log to ' + LogDest);
  end;

  // Append a bundled-Ruby section so it's the first thing visible in the file.
  SetArrayLength(LogLines, 8);
  LogLines[0] := '';
  LogLines[1] := '================================================================';
  LogLines[2] := '  Bundled CRuby payload check (post-install)';
  LogLines[3] := '================================================================';
  LogLines[4] := '  Install dir      : ' + ExpandConstant('{app}');
  LogLines[5] := '  ruby.exe         : ' + RubyExe + '   exists=' + BoolStr(RubyExeOk);
  LogLines[6] := '  asciidoctor-pdf  : ' + AdocPdfBat + '   exists=' + BoolStr(AdocPdfOk);
  LogLines[7] := '================================================================';
  if not SaveStringsToFile(LogDest, LogLines, True) then
    Log('Could not append bundled-Ruby summary to ' + LogDest);

  // Loud warning if anything is missing. Skipped in silent installs (/SILENT)
  // so unattended deploys don't block on a messagebox.
  if (not RubyExeOk) or (not AdocPdfOk) then begin
    Log('WARNING: Bundled CRuby payload incomplete.');
    Log('  ruby.exe         exists=' + BoolStr(RubyExeOk));
    Log('  asciidoctor-pdf  exists=' + BoolStr(AdocPdfOk));
    if not WizardSilent then begin
      // Build the message body. NOTE: do NOT start a line with #13#10 --
      // Inno's ISPP preprocessor treats # at the first non-whitespace
      // position as a directive, parses #13 as directive name "13", and
      // aborts with "Unknown preprocessor directive". Use Chr() calls.
      Note :=
        'AsciidocFX installed successfully, but the bundled Ruby runtime is incomplete:' + Chr(13) + Chr(10) +
        Chr(13) + Chr(10) +
        '  ruby.exe        : ' + BoolStr(RubyExeOk) + Chr(13) + Chr(10) +
        '  asciidoctor-pdf : ' + BoolStr(AdocPdfOk) + Chr(13) + Chr(10) +
        Chr(13) + Chr(10) +
        'PDF export will fall back to the slower in-process JRuby renderer.' + Chr(13) + Chr(10) +
        Chr(13) + Chr(10) +
        'A diagnostic log has been written to:' + Chr(13) + Chr(10) +
        '  ' + LogDest + Chr(13) + Chr(10) +
        Chr(13) + Chr(10) +
        'Please attach that file to a bug report so the build can be fixed.';
      MsgBox(Note, mbInformation, MB_OK);
    end;
  end else begin
    Log('Bundled CRuby payload OK (ruby.exe + asciidoctor-pdf.bat present).');
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    VerifyBundledRubyAndWriteLog;
end;
