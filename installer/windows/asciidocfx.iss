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
; Top-level launcher that points JAVACMD at the bundled JRE
Source: "{#StagingDir}\launcher.bat"; DestDir: "{app}"; Flags: ignoreversion
; License file
Source: "{#StagingDir}\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion

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
