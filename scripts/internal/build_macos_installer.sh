#!/usr/bin/env bash
# scripts/internal/build_macos_installer.sh
#
# Build a macOS .app bundle + .dmg from target/appassembler/.
#
#   AsciidocFX.app/
#     Contents/
#       Info.plist
#       MacOS/AsciidocFX       (launcher shell script)
#       Resources/AsciidocFX.icns (if a .icns is supplied)
#       app/                    (appassembler/* payload)
#       runtime/                (bundled JDK from $JAVA_HOME)
#
# Then wraps the .app in a drag-to-Applications DMG via hdiutil (built-in
# on macOS runners; no install4j or third-party dep required).
#
# Usage:
#   ./scripts/internal/build_macos_installer.sh [--version X.Y.Z] [--java-home /path/to/jdk]
set -euo pipefail

VERSION=""
JAVA_HOME_OVERRIDE="${JAVA_HOME:-}"
APP_ASSEMBLER=""
OUTPUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)      VERSION="$2"; shift 2;;
    --java-home)    JAVA_HOME_OVERRIDE="$2"; shift 2;;
    --appassembler) APP_ASSEMBLER="$2"; shift 2;;
    --output)       OUTPUT_DIR="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_ASSEMBLER="${APP_ASSEMBLER:-$REPO_ROOT/target/appassembler}"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/target}"

if [[ -z "$VERSION" ]]; then
  VERSION="$(awk -F'[<>]' '/<version>/{print $3; exit}' "$REPO_ROOT/pom.xml")"
fi
if [[ ! -d "$APP_ASSEMBLER" ]]; then
  echo "appassembler tree not found at $APP_ASSEMBLER (run scripts/build.sh first)" >&2; exit 1
fi
if [[ -z "$JAVA_HOME_OVERRIDE" || ! -x "$JAVA_HOME_OVERRIDE/bin/java" ]]; then
  echo "JAVA_HOME='$JAVA_HOME_OVERRIDE' is not a valid JDK (missing bin/java)" >&2; exit 1
fi

echo "[installer] Version       : $VERSION"
echo "[installer] AppAssembler  : $APP_ASSEMBLER"
echo "[installer] JavaHome      : $JAVA_HOME_OVERRIDE"
echo "[installer] OutputDir     : $OUTPUT_DIR"

# JDK distros differ in layout: Temurin macOS ships with Contents/Home as the
# JAVA_HOME (i.e. JAVA_HOME ends in .../Contents/Home). For the bundled
# runtime we want a self-contained JRE folder, so resolve to the JAVA_HOME
# directory itself and copy that subtree.
RUNTIME_SRC="$JAVA_HOME_OVERRIDE"

STAGING="$REPO_ROOT/target/installer-staging/macos"
APP_BUNDLE="$STAGING/AsciidocFX.app"
rm -rf "$STAGING"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"

echo "[installer] Staging app/ ..."
mkdir -p "$APP_BUNDLE/Contents/app"
cp -R "$APP_ASSEMBLER"/* "$APP_BUNDLE/Contents/app/"

echo "[installer] Staging runtime/ ..."
mkdir -p "$APP_BUNDLE/Contents/runtime"
cp -R "$RUNTIME_SRC"/* "$APP_BUNDLE/Contents/runtime/"

echo "[installer] Writing Info.plist ..."
cat > "$APP_BUNDLE/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>                 <string>AsciidocFX</string>
    <key>CFBundleDisplayName</key>          <string>AsciidocFX</string>
    <key>CFBundleIdentifier</key>           <string>com.kodedu.AsciidocFX</string>
    <key>CFBundleVersion</key>              <string>${VERSION}</string>
    <key>CFBundleShortVersionString</key>   <string>${VERSION}</string>
    <key>CFBundlePackageType</key>          <string>APPL</string>
    <key>CFBundleExecutable</key>           <string>AsciidocFX</string>
    <key>CFBundleIconFile</key>             <string>AsciidocFX.icns</string>
    <key>NSHighResolutionCapable</key>      <true/>
    <key>LSMinimumSystemVersion</key>       <string>10.13</string>
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeName</key>           <string>AsciiDoc Document</string>
            <key>CFBundleTypeRole</key>           <string>Editor</string>
            <key>LSHandlerRank</key>              <string>Default</string>
            <key>CFBundleTypeExtensions</key>
            <array>
                <string>adoc</string>
                <string>asciidoc</string>
                <string>ad</string>
                <string>asc</string>
            </array>
        </dict>
    </array>
</dict>
</plist>
PLIST

echo "[installer] Writing launcher ..."
cat > "$APP_BUNDLE/Contents/MacOS/AsciidocFX" <<'LAUNCH'
#!/bin/bash
# Resolve bundle root: this script lives at AsciidocFX.app/Contents/MacOS/.
DIR="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$DIR/runtime/Contents/Home"
if [[ ! -x "$JAVA_HOME/bin/java" && -x "$DIR/runtime/bin/java" ]]; then
    export JAVA_HOME="$DIR/runtime"
fi
export JAVACMD="$JAVA_HOME/bin/java"
exec "$DIR/app/bin/asciidocfx" "$@"
LAUNCH
chmod +x "$APP_BUNDLE/Contents/MacOS/AsciidocFX"
chmod +x "$APP_BUNDLE/Contents/app/bin/asciidocfx" || true

# Optional .icns — only copy if a packaged one exists.
if [[ -f "$REPO_ROOT/installer/macos/AsciidocFX.icns" ]]; then
    cp "$REPO_ROOT/installer/macos/AsciidocFX.icns" "$APP_BUNDLE/Contents/Resources/AsciidocFX.icns"
fi

# --- Build DMG -------------------------------------------------------------
mkdir -p "$OUTPUT_DIR"
DMG_DIR="$REPO_ROOT/target/installer-staging/macos-dmg"
rm -rf "$DMG_DIR"
mkdir -p "$DMG_DIR"
cp -R "$APP_BUNDLE" "$DMG_DIR/"
ln -s /Applications "$DMG_DIR/Applications"

DMG_OUT="$OUTPUT_DIR/AsciidocFX-${VERSION}.dmg"
rm -f "$DMG_OUT"
echo "[installer] Building $DMG_OUT ..."
hdiutil create -volname "AsciidocFX ${VERSION}" \
               -srcfolder "$DMG_DIR" \
               -ov -format UDZO \
               "$DMG_OUT"

echo "[installer] Done: $DMG_OUT"
