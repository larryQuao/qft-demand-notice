# Changelog

All notable changes and features are documented here, newest first.
Each implemented change set ships as a version bump of the application
(pom.xml version; the MSI installer follows it automatically):

- **MAJOR** - rework of the letter output or workflow
- **MINOR** - new features
- **PATCH** - fixes without new features

## [1.2.3] - 2026-11-25

### Changed
- Documented the installer toolchain, which previously existed only as tribal
  knowledge: `jpackage` builds the MSI through **WiX Toolset 3.x**, so
  `candle.exe`/`light.exe` must be on `PATH` (WiX 4+ is not supported by the
  JDK 21 `jpackage`). Without it the build stops with
  `Can not find WiX tools (light.exe, candle.exe)`.
- Installed WiX Toolset 3.14 on this machine and added its `bin` folder to the
  machine `PATH`; the installer build was then re-verified from an environment
  built only from the registry (no manual `PATH` fixes), producing
  `QFT Demand Notice Generator-1.2.3.msi` (40.4 MB).

No application behaviour changed in this release.

## [1.2.2] - 2026-11-25

### Fixed
- **Blank letters with `#` instead of text (the important one).** A freshly
  started Microsoft Publisher cannot resolve any font until it has created a
  document, so the *first* `.pub` it **opened** had every glyph stored as `#`:
  the converted PDF was blank, and auto-tokenizing found no dates or amounts.
  Both COM scripts (`pub_to_pdf.ps1`, `tokenize_pub.ps1`) now create and close
  a throwaway document before opening the real file, which initialises the
  font table. Verified against a real Tier 2 letter: the PDF now contains the
  full text and all four fields are recognised.
- **"File is in use" after a successful conversion.** Publisher keeps a handle
  on the temp `.pub`/`.pdf` for a moment after the script exits, so deleting
  them in a `finally` block could throw and turn a good conversion into a
  failure. Cleanup is now best-effort (`TempFiles.deleteQuietly`), and a
  leftover temp file is retried at JVM exit.
- **Edited templates were ignored.** If the `.pub` was saved again while the
  app was open, the previous conversion (and its disk-cache entry) was reused.
  The app now records the file's timestamp *before* converting and re-converts
  whenever the file changed, including when the edit lands mid-conversion.
- **Stale preview could overwrite a newer one.** A preview that finished after
  another template was selected is now discarded instead of being shown.
- **Preview opened at 100 % (cropped).** The preview now fits the whole page to
  the window as soon as it is laid out.
- Cache entries from the affected pipeline are abandoned
  (`TemplateCache.PIPELINE_VERSION` 1 → 2), so previously blank conversions are
  never served again.

### Changed
- The preview window takes its fixed size/position only when it actually opens;
  refreshing an open preview keeps the size and position the user chose.
- **Preview PDF** is disabled while a conversion is running, so it cannot queue
  a second, competing preview.

### Added
- `TempFiles` helper (best-effort temp-file cleanup).

## [1.2.1] - 2026-11-20

### Added
- **AGENTS.md**: an AI-agent-readable project guide containing the architecture,
  build/release commands, hard invariants, and the full memory of the session
  that built the app (decisions, completed work, current state, known caveats,
  and how to continue). OpenCode automatically loads this file as project
  instructions, so a fresh agent starts with the same context.

### Fixed
- `README.md` referenced a stale hard-coded `1.0.0` MSI filename; it now
  documents the pom-derived versioned installer name.

## [1.2.0] - 2026-11-04

### Added
- **Gesture zoom in the preview window**: `Ctrl` + mouse wheel zooms in/out
  anchored at the cursor, two-finger pinch zooms on Windows precision
  touchpads (the OS reports pinch as ctrl + wheel), two-finger strokes on a
  touch screen zoom via JavaFX `ZoomEvent`, and mouse dragging pans the page
  (the ScrollPane is pannable). Zoom stays within the existing 25 %-400 %
  limits and stays in sync with the zoom buttons and the "Fit page"
  controls.

## [1.1.0] - 2026-11-04

### Added
- **Drag & drop template**: dropping a Publisher (`.pub`) file anywhere on the
  main window selects it as the template — same effect as the Browse button.
  The conversion starts immediately in the background; dropping a non-`.pub`
  file is rejected with a clear status message.
- **Background template preparation**: picking a template starts the
  15-20 s Microsoft Publisher conversion right away (single background
  queue), and "Preview PDF" waits on that same conversion instead of
  starting a second one. A failed attempt is retried on the next click.

### Changed
- **Faster previews**: converted templates are cached on disk
  (`%LOCALAPPDATA%\QFT Demand Notice Generator\template-cache`, keyed by a
  SHA-256 of the .pub bytes). Repeated previews — including after restarting
  the app — skip the Publisher conversion entirely (measured: 28 s cold →
  ~2 s warm). Any edit to the .pub changes its hash and forces a fresh,
  correct conversion; cache failures fall back silently to the old path.

### Fixed
- **Missing-placeholder diagnosis**: when a converted template lacks
  `{{PERIOD}}`/`{{DEADLINE}}` (e.g. a .pub letter copy whose month/deadline
  sentence was deleted), the status bar now names the missing fields instead
  of only saying "2 of 4 fields filled in".
- **Window sizing**: the main window opens fitted to the desktop work area
  (screen minus taskbar); the preview window opens at one fixed size,
  centered, on every open.

## [1.0.0] - 2026-10-27

### Added
- First release:
  - Demand-letter generation from a Publisher (`.pub`) letter template or the
    built-in PDFBox layout.
  - PDF preview window showing the exact bytes that will be saved
    (fit-to-page zoom, paging, save-from-preview).
  - Auto-tokenizer: a filled-in .pub with no tokens gets
    `{{LETTER_DATE}}`, `{{PERIOD}}`, `{{AMOUNT}}`, `{{DEADLINE}}` inserted
    into a temporary copy (the original .pub is never modified).
  - Overlap-free, full-size stamping: values are drawn at the body's own
    size; overlapping text is avoided by shifting the rest of the line or
    tightening word gaps — values are never shrunk.
  - Windows MSI installer with a bundled Java runtime (no Java required on
    the machine), including menu shortcut and per-user install.
