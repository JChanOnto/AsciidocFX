# AsciidocFX — Project-Aware Fork

This is a fork of [asciidocfx/AsciidocFX](https://github.com/asciidocfx/AsciidocFX).
It adapts AsciidocFX so it can be used as a GUI editor / preview / publishing
tool for Asciidoctor projects that ship their own extensions, custom PDF
themes, and Mermaid configuration — without forcing the user to copy assets
into AsciidocFX config directories.

For the upstream project description, see [`README.adoc`](README.adoc).

## Why this fork exists

Stock AsciidocFX renders documents with its own bundled JRuby Asciidoctor and
its own configuration in `~/.AsciidocFX-<version>/`. That works well for
casual editing, but breaks down for projects that:

- Use **custom Ruby treeprocessors / postprocessors** (e.g. table layout
  fixes, Mermaid SVG cleanup) committed inside the project repo
- Ship their own **`asciidoctor-pdf` YAML theme** with a `themesdir` at the
  project root
- Ship their own **Mermaid configuration JSON** (fonts, layout,
  `useMaxWidth` for prawn-svg, etc.)
- Want **SVG Mermaid diagrams** in every output (HTML preview, HTML export,
  PDF, EPUB, DocBook, Reveal.js) for crisp rendering
- Want a **preview pane that matches the actual PDF output** (theme,
  fonts, pagination) instead of a CSS approximation

Out of the box, AsciidocFX would ignore all of these unless the user manually
copied them into `~/.AsciidocFX-<version>/asciidoctor_pdf.json` etc. — and
even then, Mermaid was hard-coded to render as PNG screenshots from a hidden
WebView.

This fork makes AsciidocFX **project-aware**: open the project folder, and
the extensions, theme, and Mermaid config are picked up automatically. SVG
Mermaid output is the new default everywhere.

## What changed vs. upstream

### 1. Auto-discovery of project Asciidoctor resources

New utility class
[`ProjectConfigDiscovery`](src/main/java/com/kodedu/service/ProjectConfigDiscovery.java)
walks the project tree (depth-limited, content-validated) to find:

| Resource           | Detection rule                                                     |
| ------------------ | ------------------------------------------------------------------ |
| Ruby extensions    | `*.rb` files referencing `Asciidoctor::Extensions`                 |
| PDF theme          | `*-theme.yml` files containing `asciidoctor-pdf` keys              |
| Mermaid config     | `*mermaid*config*.json` files containing Mermaid keys              |

The discovery is wired into:

- [`AsciidoctorFactory.checkUserExtensions()`](src/main/java/com/kodedu/service/AsciidoctorFactory.java)
  — collects extensions from the conventional `.asciidoctor/lib/` **plus**
  anything else found in the project tree (deduplicated by filename)
- [`AsciidoctorConfigBase.getAsciiDocAttributes()`](src/main/java/com/kodedu/config/AsciidoctorConfigBase.java)
  — auto-injects `pdf-theme`, `pdf-themesdir`, `mermaid-config`, and
  `mermaid-format: svg` when not already set by the user

No hardcoded paths, no marker file required — just open the project.

### 2. SVG Mermaid rendering across **all** output formats

Stock AsciidocFX renders Mermaid in two completely separate paths:

- **HTML preview** — a hidden JavaFX `WebView` runs Mermaid JS, the result
  is screenshotted to a PNG bitmap
- **PDF / HTML export / EPUB / DocBook / Reveal.js** — the JRuby
  `asciidoctor-diagram` extension shells out to the `mmdc` CLI

Both paths now produce **SVG** output:

| File                                                                                                          | Change                                                                              |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| [`MermaidServiceImpl.java`](src/main/java/com/kodedu/service/extension/impl/MermaidServiceImpl.java)          | Added SVG path: extracts raw SVG from WebView via `getSvgContent()`, writes to file or in-memory cache |
| [`mermaid.html`](conf/public/mermaid.html)                                                                    | Exposes `getSvgContent()` JS function returning the rendered SVG string             |
| [`prototypes.js`](conf/public/js/prototypes.js)                                                               | `cachedImageUri(content, format)` is now format-aware (`.svg` or `.png`)            |
| [`asciidoctor-block-extensions.js`](conf/public/js/asciidoctor-block-extensions.js)                           | Mermaid blocks request `.svg` cache URIs                                            |
| [`BinaryCacheServiceImpl.java`](src/main/java/com/kodedu/service/cache/impl/BinaryCacheServiceImpl.java)      | Temp file suffix matches cache key extension (`.svg` or `.png`)                     |
| [`AsciidoctorConfigBase.java`](src/main/java/com/kodedu/config/AsciidoctorConfigBase.java)                    | Auto-sets `:mermaid-format: svg:` for all asciidoctor-diagram backends              |

PNG rendering is preserved as the fallback for non-mermaid blocks and remains
the default for other diagram types.

> **Note**: PDF / EPUB / DocBook still require the `mmdc` CLI on `PATH`
> (`npm install -g @mermaid-js/mermaid-cli`) — that's how
> `asciidoctor-diagram` works, this fork doesn't change it.

### 3. Real PDF preview pane (1:1 with `Save → PDF`)

The right-hand preview now shows the **actual PDF** that
`asciidoctor-pdf` produces — same theme, fonts, pagination, headers,
footers — instead of a WebKit/CSS approximation. The pipeline is shared
with "Save → PDF", so what you see is what you ship.

| File / class                                                                                                | Role                                                                          |
| ----------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| [`PdfPreviewPane`](src/main/java/com/kodedu/component/PdfPreviewPane.java)                                  | `ViewPanel` sibling of `HtmlPane`; rasterizes pages with PDFBox into a scrollable `ImageView` list with a zoom slider. |
| [`PdfRenderer`](src/main/java/com/kodedu/service/convert/pdf/PdfRenderer.java)                              | Single source of truth for PDF generation; reused by the preview *and* `Save → PDF`. |
| [`PreviewSourceResolver`](src/main/java/com/kodedu/service/preview/PreviewSourceResolver.java)              | Synthesizes a single-chapter mini-master when the active file is `include::`d. |
| [`PreviewBackend`](src/main/java/com/kodedu/config/PreviewBackend.java) / `PreviewConfigBean`               | `previewBackend` (PDF default, HTML fallback) + `pdfPreviewScope` (CHAPTER / FULL). |

**Two render scopes** keep the edit loop fast on large documents:

- **Chapter** *(default)* — renders only the active chapter (`~1–3s` per
  save on the 100-page reference doc). Page numbers / TOC / cross-chapter
  xrefs are intentionally not canonical; a status banner says so.
- **Full doc** — renders the entire master with real pagination and
  TOC. First render is slow (`~69s` cold); subsequent renders benefit
  from the `imagesoutdir` / mmdc diagram cache.

**Triggers**: **Ctrl+S** and **F5** kick a re-render. No keystroke
debounce — even chapter renders are too heavy for live-typing feedback.

**Switching back to HTML preview**: set `previewBackend` to `HTML` in
`Settings → Preview Settings`. The right pane swaps live, no restart.

## Project configuration with `.asciidoctorconfig`

Drop a `.asciidoctorconfig` file in your project root to set
project-wide attributes. It uses the standard
[Asciidoctor convention](https://docs.asciidoctor.org/asciidoctor/latest/cli/config-files/) —
the same file the IntelliJ plugin and `asciidoctor` CLI honor.

Discovery walks **up** from the document toward your home directory;
outer files load first, inner files override. The token
`{asciidoctorconfigdir}` (Asciidoctor convention) expands to the
directory containing the config file.

**Syntax**: `:name: value` to set, `:!name:` to unset, `// ...` comment.

### Common attributes

| Attribute                    | Purpose                                                              |
| ---------------------------- | -------------------------------------------------------------------- |
| `pdf-theme` / `pdf-themesdir`| asciidoctor-pdf YAML theme name + dir                                |
| `mermaid-config` / `mermaid-format` | Mermaid JSON config; `svg` (recommended) or `png`             |
| `mmdc` / `vg2png` / `vg2svg` / `nomnoml` / `bytefield` | Override auto-detected CLI paths       |
| `imagesdir`, `source-highlighter`, `icons`, `experimental` | Standard Asciidoctor toggles       |
| `asciidoctor-extensions`     | Explicit list of Ruby extension files/dirs/globs (skips auto-walker) |

### Example

```adoc
:pdf-theme: truedac
:pdf-themesdir: {asciidoctorconfigdir}/theme
:mermaid-config: {asciidoctorconfigdir}/theme/mermaid-config.json
:mermaid-format: svg
:imagesdir: screenshots
:source-highlighter: rouge
:icons: font
:asciidoctor-extensions: {asciidoctorconfigdir}/theme/extensions
```

### CLI executable resolution

For Node-based CLIs (`mmdc` etc.) AsciidocFX tries, in order:
explicit attribute → walk-up `node_modules/.bin/` → OS `PATH` (Windows
prefers `.cmd`/`.exe`) → well-known install locations (`%APPDATA%\npm`,
scoop shims, Homebrew, `/usr/local/bin`). If nothing resolves, a single
warning is logged and conversion continues.

### Ruby extensions

By default, `*.rb` files anywhere under the project are auto-loaded if
they look like Asciidoctor extensions. Set `:asciidoctor-extensions:` to
a comma/semicolon-separated list of files, directories, or globs to
skip the walker and load only what you specify. The conventional
`.asciidoctor/lib/` directory is always loaded regardless.

### Attribute precedence (highest wins)

1. Document-header attributes (`:pdf-theme: …` in the `.adoc`)
2. AsciidocFX GUI attributes table
3. PDF JSON config (`asciidoctor_pdf.json`)
4. `.asciidoctorconfig` (innermost first)
5. Built-in defaults

## Setup — prerequisites for building

You need the following installed and on `PATH` before you can build or run
this project. Versions listed are what the build is currently known to work
with; newer point releases are usually fine.

### Required

| Tool                          | Version | Purpose                                                    |
| ----------------------------- | ------- | ---------------------------------------------------------- |
| **JDK**                       | 25 LTS  | Build + runtime. Eclipse Temurin recommended. The pom pins `<java.version>25</java.version>` — older JDKs will fail with `release version 25 not supported`. |
| **Apache Maven**              | 3.9+    | Build tool. `mvn` must be on `PATH`.                       |
| **Git**                       | any     | Source control + cloning JavaFX jmods on first build.      |

### Required for diagram rendering at export time

| Tool                          | Version  | Purpose                                                  |
| ----------------------------- | -------- | -------------------------------------------------------- |
| **Node.js + npm**             | 18+      | Hosts `mmdc` (Mermaid CLI).                              |
| **`@mermaid-js/mermaid-cli`** | latest   | `asciidoctor-diagram` shells out to `mmdc` for PDF/EPUB. |
| **Graphviz** (`dot`)          | any      | Required by PlantUML diagrams. Set `GRAPHVIZ_DOT` env.   |

### Optional

| Tool                | Purpose                                                              |
| ------------------- | -------------------------------------------------------------------- |
| **Chromium/Chrome** | Some `mmdc` versions require a system browser for SVG rasterization. |
| **MS Core Fonts**   | Linux only — fixes `####` glyphs in PDF output.                      |
| **KindleGen**       | Mobi (`.mobi`) export only.                                          |

### Install — Windows (PowerShell)

Apache Maven is not published on winget, so the Windows instructions use a
mix of winget (for everything else) and either Scoop or a manual Maven
install. Pick **one** Maven option.

```powershell
# JDK 25 + Node + Graphviz via winget
winget install --id EclipseAdoptium.Temurin.25.JDK
winget install --id OpenJS.NodeJS.LTS
winget install --id Graphviz.Graphviz

# --- Maven: pick ONE of the following ---

# Option A: Scoop (recommended, no admin required)
#   If you don't have Scoop: https://scoop.sh
scoop install main/maven

# Option B: Chocolatey (requires admin)
choco install maven -y

# Option C: Manual
#   Download apache-maven-3.9.x-bin.zip from https://maven.apache.org/download.cgi
#   Extract to e.g. C:\Tools\apache-maven-3.9.x
#   Add C:\Tools\apache-maven-3.9.x\bin to PATH
#   setx M2_HOME "C:\Tools\apache-maven-3.9.x"

# Mermaid CLI
npm install -g @mermaid-js/mermaid-cli

# Verify
java -version
mvn -version
node --version
mmdc --version
dot -V
```

### Install — macOS (Homebrew)

```bash
brew install --cask temurin@25
brew install maven node graphviz
npm install -g @mermaid-js/mermaid-cli
```

### Install — Linux (Debian/Ubuntu)

```bash
# Debian/Ubuntu currently package OpenJDK 21 as the newest stable; for JDK 25
# install Eclipse Temurin from https://adoptium.net/installation/linux/ or use
# SDKMAN (`sdk install java 25-tem`).
sudo apt-get update
sudo apt-get install -y maven nodejs npm graphviz ttf-mscorefonts-installer
sudo npm install -g @mermaid-js/mermaid-cli
```

### Environment variables

```powershell
# Windows — the exact JDK folder name includes the patch version, so resolve
# it dynamically. Run in a NEW terminal afterwards so PATH/JAVA_HOME refresh.
$jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory |
        Where-Object Name -Like 'jdk-25*' | Select-Object -First 1).FullName

# JAVA_HOME is short, so setx is fine here.
setx JAVA_HOME $jdk
setx GRAPHVIZ_DOT "C:\Program Files\Graphviz\bin\dot.exe"

# DO NOT use `setx PATH ...` — it silently truncates the User PATH to 1024
# chars (and rewrites it as REG_SZ, losing %VAR% expansion). Instead, append
# new entries to the User PATH directly via the registry:
function Add-UserPath([string]$Entry) {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    $cur = [string]$key.GetValue('Path', '', 'DoNotExpandEnvironmentNames')
    $parts = @($cur -split ';' | Where-Object { $_ })
    if ($parts -notcontains $Entry) {
        $parts += $Entry
        $new = ($parts -join ';')
        # ExpandString lets you use %JAVA_HOME%, %USERPROFILE%, etc.
        $key.SetValue('Path', $new, [Microsoft.Win32.RegistryValueKind]::ExpandString)
    }
    $key.Close()
}
Add-UserPath '%JAVA_HOME%\bin'
# Also add Graphviz to PATH if `dot` is not found:
#   Add-UserPath 'C:\Program Files\Graphviz\bin'
```

```bash
# macOS / Linux (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64   # Linux (Temurin .deb)
export PATH="$JAVA_HOME/bin:$PATH"
export GRAPHVIZ_DOT="$(which dot)"
```

### One-time: download the JavaFX SDK for your platform

Maven needs the JavaFX runtime jars **and** native libraries on the module
path / PATH to launch a JavaFX app. The repo ships only the link-time
`.jmod` files under `jmods/<os>/`; the runtime SDK is downloaded once into
`jmods/<os>-jars/` (gitignored). Pick the script for your OS:

```powershell
# Windows
.\download_javafx_jars.ps1
```

```bash
# macOS / Linux (downloads SDKs for all *nix targets)
bash download_javafx_jars.sh
```

The resulting layout is:

| Platform        | Extracted to                                |
| --------------- | ------------------------------------------- |
| Windows x64     | `jmods/windows-jars/{lib,bin}/`             |
| macOS x86_64    | `jmods/mac-jars/`                           |
| macOS aarch64   | `jmods/mac-m1-jars/`                        |
| Linux x64       | `jmods/linux-jars/`                         |

The pom auto-detects your OS and points `--module-path` at the right
folder. No manual flags needed.

## Building

A plain `mvn package` only produces `target/AsciidocFX.jar`. To get a runnable
launcher script (and a portable zip distribution), build with the
`install4j-package` profile:

```powershell
# Compile + package into target/appassembler/ (launcher + conf + lib)
# Also produces target/asciidocfx-<version>.zip
mvn -P install4j-package clean package -DskipTests
```

After a successful build:

| Artifact                                              | What it is                                                  |
| ----------------------------------------------------- | ----------------------------------------------------------- |
| `target/appassembler/bin/asciidocfx.bat`              | Windows launcher — **double-click or run from a terminal**. |
| `target/appassembler/bin/asciidocfx`                  | macOS / Linux launcher — `chmod +x` then run.               |
| `target/appassembler/lib/`                            | All runtime jars (flat layout).                             |
| `target/appassembler/conf/`                           | Editor / Asciidoctor / theme config.                        |
| `target/asciidocfx-<version>.zip`                     | Portable bundle of the above — unzip anywhere and run.      |
| `target/AsciidocFX.jar`                               | Bare jar (no classpath) — for embedding only.               |
| `target/asciidocfx_*.exe`, `*.dmg`, `*.deb`, `*.rpm`  | Native installers — only when the `install4j-build` profile runs (CI). |

### Run it

The simplest path that works on every platform:

```powershell
# Windows — JavaFX native DLLs must be on PATH for QuantumRenderer to start
$env:Path = "$PWD\jmods\windows-jars\bin;$env:Path"
mvn -P local-run spring-boot:run
```

```bash
# macOS — JavaFX dylibs are loaded from the SDK lib/ folder automatically
mvn -P local-run spring-boot:run
```

```bash
# Linux — same as macOS; if libs aren't picked up, add them to LD_LIBRARY_PATH:
export LD_LIBRARY_PATH="$PWD/jmods/linux-jars:$LD_LIBRARY_PATH"
mvn -P local-run spring-boot:run
```

The `local-run` profile uses Spring Boot's exec plugin and auto-resolves
`--module-path` per OS, so no `-Djavafx.module.path=...` is required unless
you keep the SDK somewhere non-default.

### Run the packaged launcher

The launcher script under `target/appassembler/bin/` is intended for the
install4j-bundled distribution (which ships its own JRE). Running it
directly against a system JDK requires injecting JavaFX yourself:

```powershell
# Windows
$env:Path     = "$PWD\jmods\windows-jars\bin;$env:Path"
$env:JAVA_OPTS = "--module-path `"$PWD\jmods\windows-jars\lib`""
.\target\appassembler\bin\asciidocfx.bat
```

For day-to-day development, prefer `mvn -P local-run spring-boot:run`.

For the full upstream build/run/contributing notes see
[`README.adoc`](README.adoc) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Upstream sync

This fork tracks upstream `master`. When merging upstream changes, watch for
conflicts in:

- `src/main/java/com/kodedu/service/AsciidoctorFactory.java`
- `src/main/java/com/kodedu/config/AsciidoctorConfigBase.java`
- `src/main/java/com/kodedu/config/PreviewConfigBean.java`
- `src/main/java/com/kodedu/controller/ApplicationController.java`
- `src/main/java/com/kodedu/boot/AppStarter.java`
- `src/main/java/com/kodedu/service/convert/pdf/AsciidoctorPdfBookConverter.java`
- `src/main/java/com/kodedu/service/extension/impl/MermaidServiceImpl.java`
- `src/main/java/com/kodedu/service/cache/impl/BinaryCacheServiceImpl.java`
- `conf/public/mermaid.html`
- `conf/public/js/prototypes.js`
- `conf/public/js/asciidoctor-block-extensions.js`

The new `ProjectConfigDiscovery.java`, `PdfPreviewPane.java`,
`PdfRenderer.java`, `PreviewSourceResolver.java`, and `PreviewBackend.java`
are self-contained additions with no upstream conflict surface.

## License

Same as upstream — see [`LICENSE`](LICENSE).