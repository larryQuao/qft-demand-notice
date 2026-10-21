# QFT Demand Notice Generator

Desktop application that generates professional PDF demand letters for Tier 2
pension contributions, matching the QFT (Qlac Financial Trust) sample layout.

## Requirements

- Java 21 (JDK; e.g. Eclipse Temurin)
- Maven 3.9+

## Build

```bash
mvn package
```

This runs the unit tests and produces the fat JAR:

```
target/qft-demand-notice.jar
```

## Run

Double-click the JAR, or from a terminal:

```bash
java -jar target/qft-demand-notice.jar
```

## Use

1. (Optional) Click **Browse...** and select your Microsoft Publisher
   template (`.pub`) — e.g. the QFTL letter head file. The app converts it
   with Microsoft Publisher (which must be installed) and stamps the four
   fields from the form into it.
2. Set the **letter date**, **contribution month/year**, **amount (GHS)** and
   **payment deadline** — the same values as before.
3. Click **Generate PDF** (or press Enter). A **Save Demand Notice As** dialog
   appears, pre-filled with the suggested name (e.g.
   `Demand_Notice_NorthCode_September2026.pdf`) in the default folder
   `Documents/QFT Demand Notices`. Rename the file or pick any folder — the
   chosen folder is remembered for the next generation. If you cancel, nothing
   is generated.
4. **Open Last Generated PDF** opens the file in the system PDF viewer.

### Auto-fill tokens in the Publisher template

Put these placeholders anywhere in the Publisher file (each on a single text
run) to have them replaced automatically:

| Token              | Replaced with               | Example                |
|--------------------|-----------------------------|------------------------|
| `{{LETTER_DATE}}`  | Letter date (ordinal)       | 24th September, 2026   |
| `{{PERIOD}}`       | Contribution month and year | September, 2026        |
| `{{AMOUNT}}`       | Formatted amount            | GHS 1,389.00           |
| `{{DEADLINE}}`     | Payment deadline (ordinal)  | 14th October, 2026     |

If the template contains no tokens it is converted **exactly as-is** — useful
when you typed the values directly in Publisher. The status line after each
generation tells you which tokens were replaced.

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
├── Main.java                      # JavaFX application
├── model/DemandNoticeData.java    # immutable notice data
├── service/
│   ├── DateFormatter.java         # "24th September, 2026"
│   ├── AmountFormatter.java       # "GHS 1,389.00"
│   ├── PdfGenerator.java          # built-in PDFBox layout engine
│   ├── PubToPdfConverter.java     # .pub -> PDF via Publisher COM (PowerShell)
│   └── TemplateStamper.java       # replaces {{...}} tokens in converted PDFs
├── ui/MainController.java         # form logic
└── util/Constants.java            # ALL fixed text + layout constants
src/main/resources/
├── assets/qft_logo.png            # logo for the built-in layout
├── scripts/pub_to_pdf.ps1         # Publisher COM conversion script
├── com/qft/demandnotice/ui/MainView.fxml
└── styles.css
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
