# Changelog

All notable changes and features are documented here, newest first.
Each implemented change set ships as a version bump of the application
(pom.xml version; the MSI installer follows it automatically):

- **MAJOR** - rework of the letter output or workflow
- **MINOR** - new features
- **PATCH** - fixes without new features

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
