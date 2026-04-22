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
- Want **fast PDF export** without giving up the zero-install promise
  (this fork can ship a portable CRuby in the install4j package)

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

**Triggers**: opening a chapter and typing kick a re-render
(keystrokes debounced ~600 ms after the last edit). **F5** forces a
manual refresh. Saving the document does *not* trigger a render — the
debounced typing-pause render already covers it. A three-dot pulse in
the preview toolbar shows when a render is in flight.

**Switching back to HTML preview**: set `previewBackend` to `HTML` in
`Settings → Preview Settings`. The right pane swaps live, no restart.

### 4. Optional native-Ruby PDF render path (2-5× faster)

The default JRuby `asciidoctorj-pdf` pipeline is portable but slow on
big books — Prawn-on-the-JVM has measurable per-page overhead. This
fork adds an optional shell-out to a CRuby `asciidoctor-pdf`, used by
both the live preview *and* `Save → PDF`. Two ways in:

- **Bundled with the install4j package** *(zero user setup)*. Run
  `scripts\build.ps1 -WithRuby` (Windows) or
  `scripts/build.sh --with-ruby` (macOS/Linux) — these wrappers invoke
  the underlying [`scripts/internal/bundle_ruby_runtime.ps1`](scripts/internal/bundle_ruby_runtime.ps1)
  / [`bundle_ruby_runtime.sh`](scripts/internal/bundle_ruby_runtime.sh)
  before the Maven package phase. The script downloads a portable
  CRuby + vendors `asciidoctor-pdf`, `asciidoctor-diagram`, `rouge`,
  and `prawn-svg` into `ruby-runtime/<os>/`. The package phase copies
  that tree into `target/appassembler/ruby/`. At runtime
  `BundledRubyResolver` auto-detects `<install-dir>/ruby/bin/asciidoctor-pdf`
  and uses it transparently. End users get the fast path with no extra
  install. Adds ~40 MB to the package.

- **User setting `pdfRendererCommand`** *(power users)*. Set in
  `Settings → PDF Settings`. Examples:
  `bundle exec asciidoctor-pdf` /
  `asciidoctor-pdf` /
  `C:\Ruby34-x64\bin\bundle.bat exec asciidoctor-pdf`.
  When set, this overrides the bundled runtime.

If neither is present, AsciidocFX falls back to the in-process JRuby
path (upstream behavior). Every attribute the JRuby path would have
applied is forwarded as a `-a name=value` flag, so theme,
diagram-cache dir, and `.asciidoctorconfig`-resolved settings carry
over identically.

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

## Setup

The one-shot setup script handles everything: detects what's already
installed, installs missing prerequisites via the platform package
manager, and downloads the JavaFX SDK into `jmods/<os>-jars/` (gitignored).
Idempotent — safe to re-run.

```powershell
# Windows
.\scripts\setup.ps1                # required prereqs + JavaFX SDK
.\scripts\setup.ps1 -WithRuby      # + bundled CRuby for native PDF path
.\scripts\setup.ps1 -SkipPrereqs   # skip dep checks (CI on prebuilt image)
```

```bash
# macOS / Linux
./scripts/setup.sh                  # required prereqs + JavaFX SDK
./scripts/setup.sh --with-ruby      # + bundled CRuby for native PDF path
./scripts/setup.sh --skip-prereqs   # skip dep checks (CI on prebuilt image)
```

What it installs / verifies:

| Tool                          | Version | How it's installed                                       |
| ----------------------------- | ------- | -------------------------------------------------------- |
| **JDK**                       | 25 LTS  | winget `EclipseAdoptium.Temurin.25.JDK` / brew `temurin@25` / *(Linux: manual — see warning)* |
| **Apache Maven**              | 3.9+    | winget `Apache.Maven` / brew / apt                       |
| **Git**                       | any     | winget `Git.Git` / brew / apt                            |
| **Node.js + npm**             | LTS     | winget `OpenJS.NodeJS.LTS` / brew / apt                  |
| **`@mermaid-js/mermaid-cli`** | latest  | `npm install -g @mermaid-js/mermaid-cli`                 |
| **Graphviz** (`dot`)          | any     | winget `Graphviz.Graphviz` / brew / apt; sets `GRAPHVIZ_DOT` |
| **JavaFX 25 SDK**             | 25      | downloaded into `jmods/<os>-jars/` (gitignored)          |

With `-WithRuby` / `--with-ruby` it additionally installs **7-Zip**
(Windows) or **ruby-build** (macOS/Linux) and bundles a portable CRuby
into `ruby-runtime/<os>/` — see [Bundling a CRuby runtime](#bundling-a-cruby-runtime-optional).

### Optional extras

| Tool                | Purpose                                                              |
| ------------------- | -------------------------------------------------------------------- |
| **Chromium/Chrome** | Some `mmdc` versions require a system browser for SVG rasterization. |
| **MS Core Fonts**   | Linux only — fixes `####` glyphs in PDF output (`apt install ttf-mscorefonts-installer`). |
| **KindleGen**       | Mobi (`.mobi`) export only.                                          |

### After installation

Open a **new** terminal so `PATH` and `JAVA_HOME` from any newly-installed
tools become visible. Verify:

```
java -version       # 25.x.x
mvn -version        # 3.9+ on JDK 25
node --version
mmdc --version
dot -V
```

### Linux: JDK 25 caveat

Debian/Ubuntu apt does not ship JDK 25 yet. The setup script will install
everything else and print a warning; install Temurin 25 from
<https://adoptium.net/installation/linux/> or via SDKMAN
(`sdk install java 25-tem`) and re-run setup.

The pom auto-detects your OS and points `--module-path` at the right
`jmods/<os>-jars/` folder. No manual flags needed.

## Building

A plain `mvn package` only produces `target/AsciidocFX.jar`. To get a runnable
launcher script (and a portable zip distribution), use the build wrapper —
it runs setup if needed, then invokes the `install4j-package` Maven profile:

```powershell
# Windows
.\scripts\build.ps1                 # standard package
.\scripts\build.ps1 -WithRuby       # + bundle portable CRuby into install
```

```bash
# macOS / Linux
./scripts/build.sh                  # standard package
./scripts/build.sh --with-ruby      # + bundle portable CRuby into install
```

Under the hood this is just:

```
mvn -P install4j-package clean package -DskipTests
```

so you can always invoke Maven directly if you prefer. Pass extra args after
`--`, e.g. `.\scripts\build.ps1 -- -DskipTests=false`.

### Bundling a CRuby runtime *(optional)*

If `ruby-runtime/<os>/` exists at package time, its contents are copied
into `target/appassembler/ruby/` and the resulting AsciidocFX install
will use that bundled CRuby for PDF render — no separate Ruby install
required from end users. The build still succeeds without it; the app
just falls back to the in-process JRuby renderer.

The `-WithRuby` / `--with-ruby` flag on `build`/`setup` triggers the bundle
step. It requires **7-Zip on PATH** (Windows) or **ruby-build on PATH**
(macOS/Linux). The underlying scripts in [`scripts/internal/`](scripts/internal/)
are idempotent (skip if `ruby-runtime/<os>/` is already populated) and pin
both the Ruby version and gem versions for reproducibility — edit the script
headers to bump versions.

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

Use the run wrapper — it ensures setup, fixes up `PATH` /
`LD_LIBRARY_PATH` so the JavaFX native libs load, and starts the app via
the `local-run` Maven profile:

```powershell
# Windows
.\scripts\run.ps1
```

```bash
# macOS / Linux
./scripts/run.sh
```

Under the hood this is `mvn -P local-run spring-boot:run` with `PATH`
(Windows) / `LD_LIBRARY_PATH` (Linux) prepended with
`jmods/<os>-jars/[bin]`. The profile auto-resolves `--module-path` per OS,
so no `-Djavafx.module.path=...` is required unless you keep the SDK
somewhere non-default.

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
- `src/main/java/com/kodedu/config/PdfConfigBean.java`
- `src/main/java/com/kodedu/controller/ApplicationController.java`
- `src/main/java/com/kodedu/boot/AppStarter.java`
- `src/main/java/com/kodedu/service/convert/pdf/AsciidoctorPdfBookConverter.java`
- `src/main/java/com/kodedu/service/extension/impl/MermaidServiceImpl.java`
- `src/main/java/com/kodedu/service/cache/impl/BinaryCacheServiceImpl.java`
- `conf/public/mermaid.html`
- `conf/public/js/prototypes.js`
- `conf/public/js/asciidoctor-block-extensions.js`
- `pom.xml` (install4j-package profile bundles `ruby-runtime/<os>/`)

The new `ProjectConfigDiscovery.java`, `PdfPreviewPane.java`,
`PdfRenderer.java`, `PreviewSourceResolver.java`, `PreviewBackend.java`,
and `BundledRubyResolver.java` are self-contained additions with no
upstream conflict surface.

## License

Same as upstream — see [`LICENSE`](LICENSE).