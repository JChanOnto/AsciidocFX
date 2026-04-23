#!/bin/bash
# scripts/internal/download_javafx_jars.sh
#
# Downloads the JavaFX 25 SDK runtime jars (lib/) for all *nix targets into
# <repo>/jmods/{linux-jars,mac-jars,mac-m1-jars,windows-jars}/.
#
# Prefer invoking via `scripts/setup.sh` rather than running this directly.

set -e

VERSION=25
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

# jmods/mac-m1-jars
rm -rf jmods/mac-m1-jars
mkdir -p jmods
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_osx-aarch64_bin-sdk.zip -o sdk.zip
unzip -o sdk.zip -d jmods/mac-m1-jars
rm sdk.zip
mv jmods/mac-m1-jars/javafx-sdk-${VERSION}/lib/* jmods/mac-m1-jars/
rm -rf jmods/mac-m1-jars/javafx-sdk-${VERSION}

# jmods/mac-jars (x86_64)
rm -rf jmods/mac-jars
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_osx-x64_bin-sdk.zip -o sdk.zip
unzip -o sdk.zip -d jmods/mac-jars
rm sdk.zip
mv jmods/mac-jars/javafx-sdk-${VERSION}/lib/* jmods/mac-jars/
rm -rf jmods/mac-jars/javafx-sdk-${VERSION}

# jmods/linux-jars
rm -rf jmods/linux-jars
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_linux-x64_bin-sdk.zip -o sdk.zip
unzip -o sdk.zip -d jmods/linux-jars
rm sdk.zip
mv jmods/linux-jars/javafx-sdk-${VERSION}/lib/* jmods/linux-jars/
rm -rf jmods/linux-jars/javafx-sdk-${VERSION}

# jmods/windows-jars (used by the local-run profile on Windows; SDK keeps
# its lib/+bin/ layout because Windows needs the bin/ DLLs on PATH).
rm -rf jmods/windows-jars
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_windows-x64_bin-sdk.zip -o sdk.zip
unzip -o sdk.zip -d jmods/windows-jars-tmp
rm sdk.zip
mv jmods/windows-jars-tmp/javafx-sdk-${VERSION} jmods/windows-jars
rmdir jmods/windows-jars-tmp
