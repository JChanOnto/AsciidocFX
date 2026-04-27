@echo off
REM ----------------------------------------------------------------------------
REM AsciidocFX top-level launcher (installed by Inno Setup).
REM
REM Points the appassembler-generated launcher at the bundled JRE that the
REM installer drops in <install dir>\runtime\, then chains to
REM app\bin\asciidocfx.bat. This keeps the appassembler launcher untouched
REM and works regardless of whether the user has Java on PATH.
REM
REM Argument forwarding: %* preserves quoting for paths passed via file
REM associations / shortcuts (e.g. "C:\path\with spaces\foo.adoc").
REM ----------------------------------------------------------------------------

setlocal
set "BASEDIR=%~dp0"
if exist "%BASEDIR%runtime\bin\java.exe" (
    set "JAVA_HOME=%BASEDIR%runtime"
    set "JAVACMD=%BASEDIR%runtime\bin\java.exe"
)
call "%BASEDIR%app\bin\asciidocfx.bat" %*
endlocal
