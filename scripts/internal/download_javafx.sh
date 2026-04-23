#!/bin/bash
# scripts/internal/download_javafx.sh
#
# Downloads the JavaFX 25 SDK runtime jars (lib/) AND the link-time .jmod
# files for all four targets into <repo>/jmods/. The .jmod variant is what
# the install4j-package profile bundles into the launcher.
#
# Prefer invoking via `scripts/setup.sh --full` rather than running this
# directly.

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

# jmods/mac-m1
rm -rf jmods/mac-m1
mkdir -p jmods
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_osx-aarch64_bin-jmods.zip -o mac-m1-jmods.zip
unzip -o mac-m1-jmods.zip -d jmods/mac-m1
rm mac-m1-jmods.zip

# jmods/mac
rm -rf jmods/mac
mkdir -p jmods
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_osx-x64_bin-jmods.zip -o mac-jmods.zip
unzip -o mac-jmods.zip -d jmods/mac
rm mac-jmods.zip

# jmods/linux
rm -rf jmods/linux
mkdir -p jmods
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_linux-x64_bin-jmods.zip -o linux-jmods.zip
unzip -o linux-jmods.zip -d jmods/linux
rm linux-jmods.zip

# jmods/windows
rm -rf jmods/windows
mkdir -p jmods
curl -L https://download2.gluonhq.com/openjfx/${VERSION}/openjfx-${VERSION}_windows-x64_bin-jmods.zip -o windows-jmods.zip
unzip -o windows-jmods.zip -d jmods/windows
rm windows-jmods.zip
