#!/usr/bin/env bash
# scripts/run.sh
#
# Run AsciidocFX from source via the `local-run` Maven profile.
# Ensures the JavaFX SDK is present; on Linux also wires LD_LIBRARY_PATH so
# the native libs are picked up.
#
# Usage:
#   ./scripts/run.sh                    # ensure setup, then mvn local-run
#   ./scripts/run.sh --skip-setup       # don't call setup.sh first

set -euo pipefail

skip_setup=0
for arg in "$@"; do
    case "$arg" in
        --skip-setup) skip_setup=1 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

if (( ! skip_setup )); then
    "$script_dir/setup.sh"
fi

case "$(uname -s)" in
    Darwin)
        if [[ "$(uname -m)" == "arm64" ]]; then jfx_dir="$repo_root/jmods/mac-m1-jars"
        else                                    jfx_dir="$repo_root/jmods/mac-jars"
        fi ;;
    Linux)
        jfx_dir="$repo_root/jmods/linux-jars"
        export LD_LIBRARY_PATH="$jfx_dir:${LD_LIBRARY_PATH:-}"
        ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

if [[ ! -d "$jfx_dir" ]]; then
    echo "JavaFX SDK not found at $jfx_dir. Run scripts/setup.sh first." >&2
    exit 1
fi

cd "$repo_root"
echo "[run] mvn -P local-run spring-boot:run"
exec mvn -P local-run spring-boot:run
