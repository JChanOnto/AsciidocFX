#!/usr/bin/env bash
# scripts/internal/build_linux_installer.sh
#
# Build a Debian package (.deb) from target/appassembler/. Uses dpkg-deb,
# which is preinstalled on Ubuntu GitHub runners — no proprietary tooling.
#
# Layout:
#   /opt/asciidocfx/app/                  (appassembler payload)
#   /opt/asciidocfx/runtime/              (bundled JDK)
#   /opt/asciidocfx/asciidocfx            (launcher shell script)
#   /usr/bin/asciidocfx                   (symlink -> /opt/asciidocfx/asciidocfx)
#   /usr/share/applications/asciidocfx.desktop
#   /usr/share/mime/packages/asciidocfx.xml
#
# Usage:
#   ./scripts/internal/build_linux_installer.sh [--version X.Y.Z] [--java-home /path/to/jdk]
set -euo pipefail

VERSION=""
JAVA_HOME_OVERRIDE="${JAVA_HOME:-}"
APP_ASSEMBLER=""
OUTPUT_DIR=""
ARCH="amd64"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)      VERSION="$2"; shift 2;;
    --java-home)    JAVA_HOME_OVERRIDE="$2"; shift 2;;
    --appassembler) APP_ASSEMBLER="$2"; shift 2;;
    --output)       OUTPUT_DIR="$2"; shift 2;;
    --arch)         ARCH="$2"; shift 2;;
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
  echo "appassembler tree not found at $APP_ASSEMBLER" >&2; exit 1
fi
if [[ -z "$JAVA_HOME_OVERRIDE" || ! -x "$JAVA_HOME_OVERRIDE/bin/java" ]]; then
  echo "JAVA_HOME='$JAVA_HOME_OVERRIDE' is not a valid JDK (missing bin/java)" >&2; exit 1
fi

echo "[installer] Version       : $VERSION"
echo "[installer] AppAssembler  : $APP_ASSEMBLER"
echo "[installer] JavaHome      : $JAVA_HOME_OVERRIDE"
echo "[installer] OutputDir     : $OUTPUT_DIR"
echo "[installer] Arch          : $ARCH"

PKG_DIR="$REPO_ROOT/target/installer-staging/linux/asciidocfx_${VERSION}_${ARCH}"
rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/DEBIAN" \
         "$PKG_DIR/opt/asciidocfx" \
         "$PKG_DIR/usr/bin" \
         "$PKG_DIR/usr/share/applications" \
         "$PKG_DIR/usr/share/icons/hicolor/256x256/apps" \
         "$PKG_DIR/usr/share/mime/packages"

echo "[installer] Staging app/ ..."
mkdir -p "$PKG_DIR/opt/asciidocfx/app"
cp -R "$APP_ASSEMBLER"/* "$PKG_DIR/opt/asciidocfx/app/"
chmod +x "$PKG_DIR/opt/asciidocfx/app/bin/asciidocfx" || true

echo "[installer] Staging runtime/ ..."
mkdir -p "$PKG_DIR/opt/asciidocfx/runtime"
cp -R "$JAVA_HOME_OVERRIDE"/* "$PKG_DIR/opt/asciidocfx/runtime/"

echo "[installer] Writing launcher ..."
cat > "$PKG_DIR/opt/asciidocfx/asciidocfx" <<'LAUNCH'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$DIR/runtime"
export JAVACMD="$JAVA_HOME/bin/java"
exec "$DIR/app/bin/asciidocfx" "$@"
LAUNCH
chmod +x "$PKG_DIR/opt/asciidocfx/asciidocfx"

ln -sf /opt/asciidocfx/asciidocfx "$PKG_DIR/usr/bin/asciidocfx"

# --- Icon ------------------------------------------------------------------
if [[ -f "$REPO_ROOT/src/main/resources/logo.png" ]]; then
    cp "$REPO_ROOT/src/main/resources/logo.png" \
       "$PKG_DIR/usr/share/icons/hicolor/256x256/apps/asciidocfx.png"
fi

# --- .desktop --------------------------------------------------------------
cat > "$PKG_DIR/usr/share/applications/asciidocfx.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=AsciidocFX
Comment=AsciiDoc editor
Exec=/opt/asciidocfx/asciidocfx %F
Icon=asciidocfx
Categories=Office;TextEditor;Development;
MimeType=text/x-asciidoc;
StartupNotify=true
Terminal=false
DESKTOP

# --- MIME type -------------------------------------------------------------
cat > "$PKG_DIR/usr/share/mime/packages/asciidocfx.xml" <<MIME
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="text/x-asciidoc">
    <comment>AsciiDoc document</comment>
    <glob pattern="*.adoc"/>
    <glob pattern="*.asciidoc"/>
    <glob pattern="*.ad"/>
    <glob pattern="*.asc"/>
  </mime-type>
</mime-info>
MIME

# --- Control file ----------------------------------------------------------
INSTALLED_SIZE_KB="$(du -sk "$PKG_DIR" | awk '{print $1}')"
cat > "$PKG_DIR/DEBIAN/control" <<CONTROL
Package: asciidocfx
Version: ${VERSION}
Section: editors
Priority: optional
Architecture: ${ARCH}
Installed-Size: ${INSTALLED_SIZE_KB}
Maintainer: AsciidocFX <noreply@asciidocfx.com>
Homepage: https://asciidocfx.com
Description: AsciiDoc editor
 AsciidocFX is a JavaFX-based editor for the AsciiDoc lightweight
 markup language. Bundles its own Java runtime — no external JDK
 required.
CONTROL

cat > "$PKG_DIR/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
if command -v update-mime-database >/dev/null 2>&1; then
    update-mime-database /usr/share/mime || true
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database /usr/share/applications || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q /usr/share/icons/hicolor || true
fi
exit 0
POST
chmod +x "$PKG_DIR/DEBIAN/postinst"

# --- Build .deb ------------------------------------------------------------
mkdir -p "$OUTPUT_DIR"
DEB_OUT="$OUTPUT_DIR/asciidocfx_${VERSION}_${ARCH}.deb"
rm -f "$DEB_OUT"
echo "[installer] Building $DEB_OUT ..."
dpkg-deb --root-owner-group --build "$PKG_DIR" "$DEB_OUT"

echo "[installer] Done: $DEB_OUT"
