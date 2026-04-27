#!/usr/bin/env bash
# scripts/build.sh
#
# Package AsciidocFX into target/appassembler/ + target/asciidocfx-<ver>.zip.
#
# By default this:
#   1. Runs scripts/setup.sh (downloads JavaFX SDK if missing)
#   2. Invokes `mvn -P install4j-package clean package -DskipTests`
#
# Usage:
#   ./scripts/build.sh                 # standard package
#   ./scripts/build.sh --with-ruby     # also bundle portable CRuby runtime
#   ./scripts/build.sh --skip-setup    # skip the setup step (e.g. CI)
#   ./scripts/build.sh -- -DskipTests=false   # forward extra args to mvn

set -euo pipefail

with_ruby=0
skip_setup=0
mvn_extra=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-ruby)  with_ruby=1; shift ;;
        --skip-setup) skip_setup=1; shift ;;
        --) shift; mvn_extra=("$@"); break ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) mvn_extra+=("$1"); shift ;;
    esac
done

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

if (( ! skip_setup )); then
    setup_args=()
    (( with_ruby )) && setup_args+=("--with-ruby")
    "$script_dir/setup.sh" ${setup_args[@]+"${setup_args[@]}"}
fi

cd "$repo_root"
echo "[build] mvn -P install4j-package clean package -DskipTests ${mvn_extra[*]:-}"
mvn -P install4j-package clean package -DskipTests ${mvn_extra[@]+"${mvn_extra[@]}"}

cat <<'EOF'

[build] Done. Artifacts:
  target/appassembler/bin/asciidocfx       (launcher)
  target/asciidocfx-*.zip                  (portable bundle)
EOF
