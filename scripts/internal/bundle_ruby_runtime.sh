#!/usr/bin/env bash
# scripts/internal/bundle_ruby_runtime.sh
#
# Download a portable CRuby for macOS / Linux + vendor `asciidoctor-pdf` and
# its dependencies into `<repo>/ruby-runtime/<os>/`. The directory is then
# copied verbatim into the install4j package, giving end users the fast
# native-Ruby PDF render path without a separate install.
#
# Prefer invoking via `scripts/setup.sh --with-ruby` or `scripts/build.sh
# --with-ruby` rather than running this directly.
#
# Idempotent: skips if the target tree already looks populated.  Delete
# `ruby-runtime/<os>/` to force a fresh build.
#
# Implementation note: there is no broadly-trusted "portable CRuby" tarball
# for macOS/Linux the way RubyInstaller provides one for Windows.  We use
# `ruby-build` (from rbenv) to compile a relocatable Ruby into the target
# directory.  This requires a working C toolchain on the build host (not on
# the end user's machine).

set -euo pipefail

# This script lives at <repo>/scripts/internal/; repo root is two levels up.
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

case "$(uname -s)" in
    Darwin)  os_dir="mac" ;;
    Linux)   os_dir="linux" ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

runtime_dir="$repo_root/ruby-runtime/$os_dir"

# Pin to a known-good CRuby release.  Bump deliberately.
ruby_version="3.4.1"

# Match bundle_ruby_runtime.ps1.
gems=(
    "asciidoctor:2.0.23"
    "asciidoctor-pdf:2.3.23"
    "asciidoctor-diagram:2.3.2"
    "rouge:4.5.1"
    "prawn-svg:0.36.0"
)

if [[ -x "$runtime_dir/bin/ruby" && -x "$runtime_dir/bin/asciidoctor-pdf" ]]; then
    echo "ruby-runtime/$os_dir/ already populated; nothing to do."
    echo "  Delete the directory to force a fresh build."
    exit 0
fi

# Locate ruby-build.  rbenv ships it at ~/.rbenv/plugins/ruby-build/bin.
ruby_build_bin=""
if command -v ruby-build >/dev/null 2>&1; then
    ruby_build_bin="$(command -v ruby-build)"
elif [[ -x "$HOME/.rbenv/plugins/ruby-build/bin/ruby-build" ]]; then
    ruby_build_bin="$HOME/.rbenv/plugins/ruby-build/bin/ruby-build"
fi
if [[ -z "$ruby_build_bin" ]]; then
    cat >&2 <<'EOF'
ruby-build not found.  Install with one of:
  macOS:   brew install ruby-build
  Linux:   git clone https://github.com/rbenv/ruby-build.git ~/.rbenv/plugins/ruby-build
EOF
    exit 1
fi

rm -rf "$runtime_dir"
mkdir -p "$runtime_dir"

echo "Building CRuby $ruby_version into $runtime_dir (this takes a few minutes)..."
"$ruby_build_bin" "$ruby_version" "$runtime_dir"

# Trim docs to keep the bundle small.
rm -rf "$runtime_dir/share/doc" "$runtime_dir/share/man" 2>/dev/null || true

echo "Installing gems..."
for spec in "${gems[@]}"; do
    name="${spec%%:*}"
    ver="${spec##*:}"
    echo "  -> $name ($ver)"
    "$runtime_dir/bin/gem" install "$name" --version "$ver" --no-document --conservative --silent
done

echo "Smoke test: asciidoctor-pdf --version"
"$runtime_dir/bin/asciidoctor-pdf" --version

echo ""
echo "Bundled Ruby runtime ready at: $runtime_dir"
