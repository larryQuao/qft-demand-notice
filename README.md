# QFT Demand Notice Generator

Desktop application that generates professional PDF demand letters for Tier 2
pension contributions, matching the QFT (Qlac Financial Trust) sample layout.

## Requirements

- Java 21 (JDK; e.g. Eclipse Temurin) - only for building from source
- Maven 3.9+
- To *run* the app, no Java is needed if you use the bundled installer (below)
- Microsoft Publisher - only when using a `.pub` letter template

## Build

```bash
mvn package
```

This runs the unit tests and produces the fat JAR:

```
target/qft-demand-notice.jar
```

## Windows installer (no Java needed)

```powershell
powershell -ExecutionPolicy Bypass -File build\setup\build_installer.ps1
```

Produces `target/setup/QFT Demand Notice Generator-1.0.0.msi` (WiX/jpackage;
run `mvn package` first). It bundles its own Java runtime, so end users need
nothing installed. Install silently with `msiexec /i <msi> /qn`; the app then
appears on the Start menu as **QFT Demand Notice Generator**.

## Run from source

Double-click the JAR, or from a terminal:

```bash
java -jar target/qft-demand-notice.jar
```

## Use

1. (Optional) Click **Browse...** and select your Microsoft Publisher
   template (`.pub`) — e.g. the QFTL letter head file. The app converts it
   with Microsoft Publisher (which must be installed) in the background as
   soon as you pick the file, and stamps the four fields from the form into
   it. Converting takes 15-20 s the first time; afterwards it is taken from
   the cache (see below).
2. Set the **letter date**, **contribution month/year**, **amount (GHS)** and
   **payment deadline** — the same values as before.
3. Click **Generate PDF** (or **Preview PDF**). The **preview window** opens
   showing exactly the PDF that will be saved: the converted template with
   the values already stamped inline. Check it, then click **Save PDF...**
   there — the save dialog is pre-filled with the suggested name
   (e.g. `Demand_Notice_NorthCode_September2026.pdf`). Chosen folders are
   remembered. What you see is what gets written.
4. The status line under the form reports how many of the four fields were
   filled in, and names any placeholders **not found in the template** (that
   almost always means the .pub has lost its `{{PERIOD}}`/`{{DEADLINE}}`
   text — the form cannot fill what is not in the template).
5. **Open Last Generated PDF** opens the last saved file in the system PDF
   viewer.

### Windows

- The main window opens sized to the desktop **work area** (screen minus
  taskbar); you can still resize it.
- The preview window always opens at one fixed size, centered — it never
  remembers a previous drag or resize.
- Stamped values are always drawn at full size: when a value is wider than
  its placeholder the rest of the line shifts (or word spacing tightens)
  instead of the text shrinking or overlapping.

### Auto-fill tokens in the Publisher template

Put these placeholders anywhere in the Publisher file (each on a single text
run) to have them replaced automatically:

| Token              | Replaced with               | Example                |
|--------------------|-----------------------------|------------------------|
| `{{LETTER_DATE}}`  | Letter date (ordinal)       | 24th September, 2026   |
| `{{PERIOD}}`       | Contribution month and year | September, 2026        |
| `{{AMOUNT}}`       | Formatted amount            | GHS 1,389.00           |
| `{{DEADLINE}}`     | Payment deadline (ordinal)  | 14th October, 2026     |

If the template contains **no tokens**, it is treated as a filled-in letter:
the app copies it, auto-inserts tokens where it recognises the date/amount
patterns (`{{LETTER_DATE}}`, `{{PERIOD}}`, `{{AMOUNT}}`, `{{DEADLINE}}`) and
converts the copy — the original .pub is never modified. It reports in the
preview note which fields it recognised and which it could not (unrecognised
text stays as it is in the letter). The status line after each generation
tells you which tokens were replaced.

### Template cache

Converted templates are cached under
`%LOCALAPPDATA%\QFT Demand Notice Generator\template-cache`, keyed by a
SHA-256 of the .pub bytes, so repeated previews — including after an app
restart — skip the 15-20 s Publisher conversion. Any edit to the .pub changes
its hash and forces a fresh conversion, so the cache can never serve a stale
letter. Cache problems are silent: if the cache is missing or corrupt the app
converts as before.

Notes:

- The .pub is opened as a **copy**, so it is safe to generate while the file
  is open in Publisher. The app never closes a Publisher instance it did not
  start.
- If Publisher shows a modal dialog (activation, file-in-use, etc.) the
  conversion waits for it; after 4 minutes it gives up with a clear error.
- With no template selected, the app falls back to the built-in layout
  described below.

## Project layout

```
src/main/java/com/qft/demandnotice/
├── Launcher.java                  # fat-JAR entry point
├── Main.java                      # JavaFX application (window fitted to work area)
├── model/DemandNoticeData.java    # immutable notice data
├── service/
│   ├── DateFormatter.java         # "24th September, 2026"
│   ├── AmountFormatter.java       # "GHS 1,389.00"
│   ├── PdfGenerator.java          # built-in PDFBox layout engine
│   ├── PubToPdfConverter.java     # .pub -> PDF via Publisher COM (PowerShell)
│   ├── PubToPdfTokenizer.java     # auto-inserts tokens into filled-in .pub copies
│   ├── TemplateCache.java         # disk cache of converted templates
│   ├── ContentStreamEditor.java   # token removal + line reflow in PDF content streams
│   └── TemplateStamper.java       # replaces {{...}} tokens in converted PDFs
├── ui/MainController.java         # form logic + preview orchestration
├── ui/PdfPreviewController.java   # preview window (rendering, zoom, paging)
└── util/Constants.java            # ALL fixed text + layout constants
src/main/resources/
├── assets/qft_logo.png            # logo for the built-in layout
├── scripts/pub_to_pdf.ps1         # Publisher COM conversion script
├── scripts/tokenize_pub.ps1       # token insertion helper
├── com/qft/demandnotice/ui/MainView.fxml, PdfPreviewView.fxml
└── styles.css
build/setup/                       # MSI installer script + icon assets
```

## Customising

- **Fixed text** (address block, body paragraphs, subject, board line) and all
  colours/coordinates live in `util/Constants.java` - edit there only.
- **Logo**: replace `src/main/resources/assets/qft_logo.png` and keep the same
  filename.
- **Recipient / signatory**: currently fixed in `Constants.java` (out of scope
  for v1 per the plan).

## Known placeholders (pending the original sample PDF)

The following were reconstructed and must be verified against the original
sample letter:

- Body paragraph wording and the exact subject line
- Head office address block
- Board of Directors names in the footer
- Brand orange / footer blue hex values
- Layout coordinates in `Constants.java`

See `DEMAND_NOTICE_PDF_GENERATOR_PLAN.md` for the full specification.
