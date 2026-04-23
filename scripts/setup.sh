#!/usr/bin/env bash
# scripts/setup.sh
#
# One-shot dev environment setup for AsciidocFX on macOS / Linux.
#
# Default (no args): checks for and installs every "Required" prerequisite
# from README.md, then downloads the JavaFX SDK runtime jars into
# jmods/<os>-jars/. Already-installed tools are detected and skipped.
#
# Required prerequisites handled (via Homebrew on macOS, apt on Debian/Ubuntu):
#   * Eclipse Temurin JDK 25 LTS
#   * Apache Maven 3.9+
#   * Git
#   * Node.js + npm
#   * @mermaid-js/mermaid-cli (mmdc)     (npm install -g)
#   * Graphviz (dot)
#
# Optional flags:
#   --with-ruby    Also install ruby-build and bundle a portable CRuby
#                  runtime into ruby-runtime/<os>/ (~40 MB).
#   --full         Also download the link-time .jmod files for all four
#                  targets (needed for install4j-build / native installers).
#   --skip-prereqs Skip prerequisite check/install; only handle JavaFX SDK
#                  and (optionally) CRuby. Use this in CI.
#   --force        Re-download the JavaFX SDK / CRuby bundle even if present.
#
# After installation, open a NEW terminal (or source your shell rc) so PATH
# / JAVA_HOME from any newly-installed tools become visible.

set -euo pipefail

with_ruby=0
full=0
skip_prereqs=0
force=0
for arg in "$@"; do
    case "$arg" in
        --with-ruby)    with_ruby=1 ;;
        --full)         full=1 ;;
        --skip-prereqs) skip_prereqs=1 ;;
        --force)        force=1 ;;
        -h|--help)      sed -n '2,29p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
internal="$script_dir/internal"

case "$(uname -s)" in
    Darwin) os=mac ;;
    Linux)  os=linux ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

case "$os" in
    mac)
        if [[ "$(uname -m)" == "arm64" ]]; then jfx_dir="$repo_root/jmods/mac-m1-jars"
        else                                    jfx_dir="$repo_root/jmods/mac-jars"
        fi ;;
    linux) jfx_dir="$repo_root/jmods/linux-jars" ;;
esac

# --- helpers ---------------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

# brew install if missing (macOS).
brew_ensure() {
    local cmd="$1" pkg="$2" label="$3" cask="${4:-}"
    if have "$cmd"; then
        echo "[setup] OK: $label detected ($cmd)"
        return
    fi
    if ! have brew; then
        echo "[setup] WARN: Homebrew not installed; install '$label' manually." >&2
        return
    fi
    echo "[setup] Installing $label via Homebrew..."
    if [[ -n "$cask" ]]; then brew install --cask "$pkg"
    else                      brew install "$pkg"
    fi
}

# apt-get install if missing (Debian/Ubuntu).
apt_ensure() {
    local cmd="$1" pkg="$2" label="$3"
    if have "$cmd"; then
        echo "[setup] OK: $label detected ($cmd)"
        return
    fi
    if ! have apt-get; then
        echo "[setup] WARN: apt-get not available; install '$label' manually." >&2
        return
    fi
    echo "[setup] Installing $label via apt..."
    sudo apt-get install -y "$pkg"
}

# Verify `java -version` reports >= 25; install Temurin 25 otherwise.
confirm_jdk25() {
    if have java; then
        local line
        line="$(java -version 2>&1 | head -n1)"
        if [[ "$line" =~ version\ \"([0-9]+) ]]; then
            local major="${BASH_REMATCH[1]}"
            if (( major >= 25 )); then
                echo "[setup] OK: JDK $major detected"
                return
            fi
            echo "[setup] Found JDK $major but need >= 25; installing Temurin 25..."
        fi
    else
        echo "[setup] JDK not found; installing Temurin 25..."
    fi
    case "$os" in
        mac)
            if have brew; then brew install --cask temurin@25
            else echo "[setup] WARN: install JDK 25 manually (https://adoptium.net)" >&2
            fi
            ;;
        linux)
            cat >&2 <<'EOF'
[setup] WARN: Debian/Ubuntu apt does not ship JDK 25 yet.
        Install Eclipse Temurin 25 from https://adoptium.net/installation/linux/
        or via SDKMAN:  curl -s https://get.sdkman.io | bash && sdk install java 25-tem
EOF
            ;;
    esac
}

# --- prerequisite checks ---------------------------------------------------

if (( ! skip_prereqs )); then
    echo "[setup] Checking required tools..."

    confirm_jdk25

    if [[ "$os" == "mac" ]]; then
        brew_ensure mvn  maven    'Apache Maven'
        brew_ensure git  git      'Git'
        brew_ensure node node     'Node.js'
        brew_ensure dot  graphviz 'Graphviz'
    else
        if have apt-get; then sudo apt-get update; fi
        apt_ensure mvn  maven    'Apache Maven'
        apt_ensure git  git      'Git'
        apt_ensure node nodejs   'Node.js'
        apt_ensure npm  npm      'npm'
        apt_ensure dot  graphviz 'Graphviz'
    fi

    # mmdc — npm global. Needs node first.
    if have mmdc; then
        echo "[setup] OK: mmdc (Mermaid CLI) detected"
    elif have npm; then
        echo "[setup] Installing @mermaid-js/mermaid-cli via npm..."
        if [[ "$os" == "linux" ]]; then sudo npm install -g '@mermaid-js/mermaid-cli'
        else                            npm install -g '@mermaid-js/mermaid-cli'
        fi
    else
        echo "[setup] WARN: npm not on PATH yet; re-run setup.sh after opening a new shell." >&2
    fi

    # ruby-build is only needed for the optional CRuby bundle.
    if (( with_ruby )) && ! have ruby-build && [[ ! -x "$HOME/.rbenv/plugins/ruby-build/bin/ruby-build" ]]; then
        case "$os" in
            mac)   if have brew; then brew install ruby-build; fi ;;
            linux) git clone --depth 1 https://github.com/rbenv/ruby-build.git "$HOME/.rbenv/plugins/ruby-build" 2>/dev/null || true ;;
        esac
    fi
fi

# --- JavaFX SDK ------------------------------------------------------------

if (( force )) && [[ -d "$jfx_dir" ]]; then
    echo "[setup] --force: removing existing $jfx_dir"
    rm -rf "$jfx_dir"
fi
if [[ -d "$jfx_dir" && -n "$(ls -A "$jfx_dir" 2>/dev/null)" ]]; then
    echo "[setup] OK: JavaFX SDK present at $jfx_dir (use --force to re-download)"
else
    if (( full )); then
        echo "[setup] Downloading JavaFX SDK + .jmod files for all targets..."
        "$internal/download_javafx.sh"
    else
        echo "[setup] Downloading JavaFX SDK..."
        "$internal/download_javafx_jars.sh"
    fi
fi

# --- Bundled CRuby (optional) ---------------------------------------------

if (( with_ruby )); then
    ruby_dir="$repo_root/ruby-runtime/$os"
    if (( force )) && [[ -d "$ruby_dir" ]]; then
        echo "[setup] --force: removing existing $ruby_dir"
        rm -rf "$ruby_dir"
    fi
    echo "[setup] Bundling portable CRuby runtime..."
    "$internal/bundle_ruby_runtime.sh"
fi

cat <<'EOF'

[setup] Done.
  If any tools were just installed, open a NEW shell (or source ~/.bashrc /
  ~/.zshrc) so PATH / JAVA_HOME refresh.
  Next:  ./scripts/build.sh             (package the app)
         ./scripts/run.sh               (run from source)
EOF
