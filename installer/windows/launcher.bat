@echo off
REM ----------------------------------------------------------------------------
REM AsciidocFX top-level launcher (installed by Inno Setup).
REM
REM Wires up three things that the appassembler-generated app\bin\asciidocfx.bat
REM does NOT handle on its own when the bundled JRE is a stock JDK (Temurin)
REM rather than an install4j-jlinked custom JRE that already contains JavaFX:
REM
REM   1. JAVACMD / JAVA_HOME -> the bundled <install>\runtime\bin\java.exe
REM      so the user doesn't need a system Java.
REM
REM   2. JAVA_OPTS picks up --module-path "<install>\javafx\lib" so the
REM      --add-modules=javafx.controls,javafx.fxml,... line in the
REM      appassembler launcher resolves against the JavaFX SDK we ship in
REM      <install>\javafx\.
REM
REM   3. PATH gets <install>\javafx\bin prepended so that JavaFX's
REM      QuantumRenderer (and javafx.web's WebKit DLLs) can dlopen their
REM      native libraries (javafx_iio.dll, prism_d3d.dll, jfxwebkit.dll, ...).
REM
REM Argument forwarding: %* preserves quoting for paths passed via file
REM associations / shortcuts (e.g. "C:\path\with spaces\foo.adoc").
REM ----------------------------------------------------------------------------

setlocal
set "BASEDIR=%~dp0"
REM Strip trailing backslash so concatenations below produce clean paths.
if "%BASEDIR:~-1%"=="\" set "BASEDIR=%BASEDIR:~0,-1%"

REM --- Bundled JRE -----------------------------------------------------------
if exist "%BASEDIR%\runtime\bin\java.exe" (
    set "JAVA_HOME=%BASEDIR%\runtime"
    set "JAVACMD=%BASEDIR%\runtime\bin\java.exe"
)

REM --- JavaFX SDK ------------------------------------------------------------
if exist "%BASEDIR%\javafx\lib\javafx.controls.jar" (
    REM The appassembler bat invokes %JAVACMD% %JAVA_OPTS% ... so anything we
    REM put into JAVA_OPTS is prepended to the JVM args before --add-modules.
    set "JAVA_OPTS=--module-path "%BASEDIR%\javafx\lib" %JAVA_OPTS%"
    REM Native JavaFX DLLs (prism_d3d, javafx_iio, jfxwebkit, ...).
    set "PATH=%BASEDIR%\javafx\bin;%PATH%"
)

call "%BASEDIR%\app\bin\asciidocfx.bat" %*
endlocal
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
