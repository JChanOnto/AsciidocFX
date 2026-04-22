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

## Setup — prerequisites for building

You need the following installed and on `PATH` before you can build or run
this project. Versions listed are what the build is currently known to work
with; newer point releases are usually fine.

### Required

| Tool                          | Version | Purpose                                                    |
| ----------------------------- | ------- | ---------------------------------------------------------- |
| **JDK**                       | 17 LTS  | Build + runtime. Eclipse Temurin recommended.              |
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

```powershell
# JDK 17 + Maven + Node + Graphviz via winget
winget install --id EclipseAdoptium.Temurin.17.JDK
winget install --id Apache.Maven
winget install --id OpenJS.NodeJS.LTS
winget install --id Graphviz.Graphviz

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
brew install --cask temurin@17
brew install maven node graphviz
npm install -g @mermaid-js/mermaid-cli
```

### Install — Linux (Debian/Ubuntu)

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven nodejs npm graphviz \
                        ttf-mscorefonts-installer
sudo npm install -g @mermaid-js/mermaid-cli
```

### Environment variables

```powershell
# Windows
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17"
setx GRAPHVIZ_DOT "C:\Program Files\Graphviz\bin\dot.exe"
```

```bash
# macOS / Linux (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # macOS
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # Linux
export GRAPHVIZ_DOT="$(which dot)"
```

> JavaFX is **not** a separate install — the required `jmods` are vendored
> under [`jmods/`](jmods/) per platform and Maven picks the right one
> automatically.

## Building

```powershell
# Full build (skips tests for speed; drop -DskipTests for the full suite)
mvn clean package -DskipTests
```

After a successful build:

- **Run from sources**: `target/appassembler/bin/asciidocfx.bat` (Windows) or
  `asciidocfx.sh` (macOS/Linux).
- **Native installers**: produced by the GitHub Actions workflow using
  install4j; see [`asciidocfx.install4j`](asciidocfx.install4j).

For the full upstream build/run/contributing notes see
[`README.adoc`](README.adoc) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Upstream sync

This fork tracks upstream `master`. When merging upstream changes, watch for
conflicts in:

- `src/main/java/com/kodedu/service/AsciidoctorFactory.java`
- `src/main/java/com/kodedu/config/AsciidoctorConfigBase.java`
- `src/main/java/com/kodedu/service/extension/impl/MermaidServiceImpl.java`
- `src/main/java/com/kodedu/service/cache/impl/BinaryCacheServiceImpl.java`
- `conf/public/mermaid.html`
- `conf/public/js/prototypes.js`
- `conf/public/js/asciidoctor-block-extensions.js`

The new `ProjectConfigDiscovery.java` is a self-contained addition with no
upstream conflict surface.

## License

Same as upstream — see [`LICENSE`](LICENSE).